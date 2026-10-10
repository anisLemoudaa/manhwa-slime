// Supabase Edge Function receiving AdMob rewarded-SSV callbacks.
// Configure this function URL on the rewarded ad unit in AdMob. Never trust the client reward callback.
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
const EXPECTED_REWARD_ITEM = "gold_coins";
const EXPECTED_REWARD_AMOUNT = 15;
const MAX_KEY_CACHE_MS = 24 * 60 * 60 * 1000;

let cachedKeys: Map<number, CryptoKey> | null = null;
let cachedAt = 0;

function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

function readDerLength(bytes: Uint8Array, start: number): { length: number; next: number } {
  const first = bytes[start];
  if (first < 0x80) return { length: first, next: start + 1 };
  const count = first & 0x7f;
  if (count < 1 || count > 2 || start + count >= bytes.length) throw new Error("Bad DER length");
  let length = 0;
  for (let i = 0; i < count; i++) length = length * 256 + bytes[start + 1 + i];
  return { length, next: start + 1 + count };
}

function derEcdsaToRaw(signature: Uint8Array): Uint8Array {
  if (signature[0] !== 0x30) throw new Error("Bad ECDSA sequence");
  const seq = readDerLength(signature, 1);
  let pos = seq.next;
  if (signature[pos++] !== 0x02) throw new Error("Bad ECDSA r");
  const rLen = readDerLength(signature, pos); pos = rLen.next;
  const r = signature.slice(pos, pos + rLen.length); pos += rLen.length;
  if (signature[pos++] !== 0x02) throw new Error("Bad ECDSA s");
  const sLen = readDerLength(signature, pos); pos = sLen.next;
  const s = signature.slice(pos, pos + sLen.length);
  if (r.length > 33 || s.length > 33) throw new Error("Bad ECDSA scalar length");
  const raw = new Uint8Array(64);
  const cleanR = r.length === 33 && r[0] === 0 ? r.slice(1) : r;
  const cleanS = s.length === 33 && s[0] === 0 ? s.slice(1) : s;
  if (cleanR.length > 32 || cleanS.length > 32) throw new Error("Bad ECDSA scalar");
  raw.set(cleanR, 32 - cleanR.length);
  raw.set(cleanS, 64 - cleanS.length);
  return raw;
}

async function getKeys(forceRefresh = false): Promise<Map<number, CryptoKey>> {
  if (!forceRefresh && cachedKeys && Date.now() - cachedAt < MAX_KEY_CACHE_MS) return cachedKeys;
  const response = await fetch(KEYS_URL, { headers: { "Accept": "application/json" } });
  if (!response.ok) throw new Error(`AdMob key fetch failed (${response.status})`);
  const payload = await response.json();
  const result = new Map<number, CryptoKey>();
  for (const entry of payload.keys ?? []) {
    const keyBytes = decodeBase64Url(String(entry.base64 ?? ""));
    const key = await crypto.subtle.importKey(
      "spki",
      keyBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    result.set(Number(entry.keyId), key);
  }
  if (!result.size) throw new Error("AdMob returned no signing keys");
  cachedKeys = result;
  cachedAt = Date.now();
  return result;
}

async function verifyCallback(url: URL): Promise<URLSearchParams> {
  const rawQuery = url.search.startsWith("?") ? url.search.slice(1) : url.search;
  const signatureMarker = "&signature=";
  const signatureAt = rawQuery.lastIndexOf(signatureMarker);
  const suffix = signatureAt < 0 ? "" : rawQuery.slice(signatureAt + 1);
  const suffixParts = suffix.split("&");
  if (signatureAt < 0 || suffixParts.length !== 2 || !suffixParts[0].startsWith("signature=") || !suffixParts[1].startsWith("key_id=")) {
    throw new Error("Missing ordered signature/key_id parameters");
  }
  const signedQuery = rawQuery.slice(0, signatureAt);
  const suffixParams = new URLSearchParams(suffix);
  const signature = suffixParams.get("signature");
  const keyIdText = suffixParams.get("key_id");
  if (!signature || !keyIdText || !/^\d+$/.test(keyIdText) || !Number.isSafeInteger(Number(keyIdText))) {
    throw new Error("Missing signature parameters");
  }
  let key = (await getKeys()).get(Number(keyIdText));
  if (!key) key = (await getKeys(true)).get(Number(keyIdText));
  if (!key) throw new Error("Unknown AdMob key id");
  const derSignature = decodeBase64Url(signature);
  const rawSignature = derEcdsaToRaw(derSignature);
  const valid = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    rawSignature,
    new TextEncoder().encode(signedQuery),
  );
  if (!valid) throw new Error("Invalid AdMob SSV signature");
  return new URLSearchParams(signedQuery);
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

Deno.serve(async (request) => {
  if (request.method !== "GET") return new Response("Method not allowed", { status: 405 });
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) return new Response("Backend is not configured", { status: 503 });
  try {
    const url = new URL(request.url);
    const params = await verifyCallback(url);
    const sessionId = params.get("custom_data") ?? "";
    const userId = params.get("user_id") ?? "";
    const transactionId = params.get("transaction_id") ?? "";
    const rewardAmount = Number(params.get("reward_amount"));
    const rewardItem = params.get("reward_item") ?? "";
    if (!isUuid(sessionId) || !isUuid(userId)) {
      return new Response("Invalid reward identity", { status: 400 });
    }
    if (rewardAmount !== EXPECTED_REWARD_AMOUNT || rewardItem !== EXPECTED_REWARD_ITEM || transactionId.length < 16 || transactionId.length > 512) {
      return new Response("Unexpected reward payload", { status: 400 });
    }

    const grant = await fetch(`${SUPABASE_URL}/rest/v1/rpc/grant_ad_reward`, {
      method: "POST",
      headers: {
        "apikey": SERVICE_ROLE_KEY,
        "Authorization": `Bearer ${SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        p_session_id: sessionId,
        p_user_id: userId,
        p_transaction_id: transactionId,
        p_reward_amount: rewardAmount,
        p_reward_item: rewardItem,
      }),
    });
    if (!grant.ok) {
      const detail = await grant.text();
      console.error("AdMob reward grant rejected", grant.status, detail.slice(0, 300));
      return new Response("Reward grant unavailable", { status: 500 });
    }
    return new Response("OK", { status: 200 });
  } catch (error) {
    console.error("AdMob SSV verification failed", error);
    const message = error instanceof Error ? error.message : "unknown";
    const clientError = message.startsWith("Bad ") || message.startsWith("Missing ") || message.startsWith("Unknown ") || message.startsWith("Invalid ");
    return new Response(clientError ? "Invalid SSV callback" : "SSV verification unavailable", { status: clientError ? 400 : 500 });
  }
});
