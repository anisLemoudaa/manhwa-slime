// Verifies one-time Google Play coin purchases server-side before crediting the wallet.
// Required Edge Function secrets: SUPABASE_URL, SUPABASE_ANON_KEY,
// SUPABASE_SERVICE_ROLE_KEY, GOOGLE_PLAY_SERVICE_ACCOUNT_JSON, ANDROID_PACKAGE_NAME.
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANDROID_PACKAGE_NAME = Deno.env.get("ANDROID_PACKAGE_NAME") || "com.manhwaslime.app";
const SERVICE_ACCOUNT_RAW = Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")!;
const PRODUCTS: Record<string, number> = {
  gold_coins_15: 15,
  gold_coins_40: 40,
  gold_coins_90: 90,
  gold_coins_200: 200,
};

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function pemBytes(pem: string): Uint8Array {
  const contents = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  return Uint8Array.from(atob(contents), (c) => c.charCodeAt(0));
}

async function getPublisherAccessToken(serviceAccount: { client_email: string; private_key: string }): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })));
  const unsigned = `${header}.${claims}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemBytes(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const jwt = `${unsigned}.${base64Url(signature)}`;
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
  });
  if (!response.ok) throw new Error(`Google OAuth failed (${response.status})`);
  const result = await response.json();
  return String(result.access_token);
}

async function getUserId(accessToken: string): Promise<string> {
  const response = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { apikey: SUPABASE_ANON_KEY, Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) throw new Error("Invalid user session");
  const user = await response.json();
  if (!user.id) throw new Error("User id is missing");
  return String(user.id);
}

async function callWalletRpc(name: string, body: unknown): Promise<Response> {
  return await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: {
      apikey: SERVICE_ROLE_KEY,
      Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return Response.json({ error: "Method not allowed" }, { status: 405 });
  if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SERVICE_ROLE_KEY || !SERVICE_ACCOUNT_RAW) {
    return Response.json({ error: "Purchase verification backend is not configured" }, { status: 503 });
  }
  try {
    const authorization = request.headers.get("Authorization") ?? "";
    const bearer = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
    if (!bearer) return Response.json({ error: "Authentication required" }, { status: 401 });
    const userId = await getUserId(bearer);

    const body = await request.json();
    const productId = String(body.productId ?? "");
    const purchaseToken = String(body.purchaseToken ?? "");
    const coinAmount = PRODUCTS[productId];
    if (!coinAmount || purchaseToken.length < 20 || purchaseToken.length > 4096) {
      return Response.json({ error: "Invalid product or purchase token" }, { status: 400 });
    }

    const serviceAccount = JSON.parse(SERVICE_ACCOUNT_RAW);
    if (!serviceAccount.client_email || !serviceAccount.private_key) throw new Error("Service account secret is malformed");
    const accessToken = await getPublisherAccessToken(serviceAccount);
    const encodedProduct = encodeURIComponent(productId);
    const encodedToken = encodeURIComponent(purchaseToken);
    const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(ANDROID_PACKAGE_NAME)}/purchases/products/${encodedProduct}/tokens/${encodedToken}`;
    const detailsResponse = await fetch(base, { headers: { Authorization: `Bearer ${accessToken}` } });
    if (!detailsResponse.ok) {
      console.error("Google Play purchase lookup failed", detailsResponse.status, (await detailsResponse.text()).slice(0, 300));
      return Response.json({ error: "Google Play could not verify this purchase" }, { status: 400 });
    }
    const details = await detailsResponse.json();
    if (Number(details.purchaseState) !== 0) {
      return Response.json({ error: "Purchase is not completed", pending: Number(details.purchaseState) === 2 }, { status: 409 });
    }

    const accountDigest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(userId)));
    const expectedAccountId = Array.from(accountDigest, (byte) => byte.toString(16).padStart(2, "0")).join("");
    if (details.obfuscatedExternalAccountId !== expectedAccountId) {
      return Response.json({ error: "Purchase account does not match the authenticated user" }, { status: 403 });
    }

    const grant = await callWalletRpc("grant_play_coin_purchase", {
      p_user_id: userId,
      p_product_id: productId,
      p_purchase_token: purchaseToken,
      p_coin_amount: coinAmount,
    });
    if (!grant.ok) {
      console.error("Wallet rejected verified Play purchase", grant.status, (await grant.text()).slice(0, 300));
      return Response.json({ error: "Purchase could not be credited" }, { status: 409 });
    }
    const balance = Number(await grant.text());

    // Consume only after the transaction is verified and durably credited.
    // A repeated callback remains safe: the wallet RPC is idempotent by purchase token.
    if (Number(details.consumptionState) !== 1) {
      const consumeResponse = await fetch(`${base}:consume`, {
        method: "POST",
        headers: { Authorization: `Bearer ${accessToken}` },
      });
      if (!consumeResponse.ok) {
        console.error("Play purchase was credited but consumption failed", consumeResponse.status, (await consumeResponse.text()).slice(0, 300));
        return Response.json({ balance, consumePending: true }, { status: 202 });
      }
    }
    return Response.json({ balance, credited: true }, { status: 200 });
  } catch (error) {
    console.error("Play purchase verification failed", error);
    return Response.json({ error: "Purchase verification failed" }, { status: 400 });
  }
});
