// Unity Ads rewarded S2S callback. Never grant coins from the Android completion callback.
import { createHmac, timingSafeEqual } from "node:crypto";
import { Buffer } from "node:buffer";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const UNITY_ADS_S2S_SECRET = Deno.env.get("UNITY_ADS_S2S_SECRET") ?? "";
const EXPECTED_PRODUCT_ID = "gold_coins";
const WALLET_REWARD_ITEM = "gold_coins";
const EXPECTED_REWARD_AMOUNT = 15;

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function isValidTransactionId(value: string): boolean {
  return value.length >= 1 && value.length <= 512 && !/[\u0000-\u001f\u007f]/.test(value);
}

function signedParameterString(params: URLSearchParams): string {
  const entries = [...params.entries()];
  const keys = entries.map(([key]) => key);
  if (new Set(keys).size !== keys.length) throw new Error("Duplicate callback parameter");
  if (keys.some((key) => !["hmac", "oid", "productid", "sid"].includes(key))) {
    throw new Error("Unexpected callback parameter");
  }
  return entries
    .filter(([key]) => key !== "hmac")
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
    .map(([key, value]) => `${key}=${value}`)
    .join(",");
}

function isValidSignature(params: URLSearchParams): boolean {
  const received = params.get("hmac") ?? "";
  if (!/^[0-9a-f]{32}$/i.test(received)) return false;
  const expected = createHmac("md5", UNITY_ADS_S2S_SECRET)
    .update(signedParameterString(params), "utf8")
    .digest();
  const actual = Buffer.from(received, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

Deno.serve(async (request) => {
  if (request.method !== "GET") return new Response("Method not allowed", { status: 405 });
  if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY || !UNITY_ADS_S2S_SECRET) {
    return new Response("Unity S2S is not configured", { status: 503 });
  }

  try {
    const params = new URL(request.url).searchParams;
    if (!isValidSignature(params)) return new Response("Invalid Unity S2S signature", { status: 403 });

    const sessionId = params.get("sid") ?? "";
    const transactionId = params.get("oid") ?? "";
    if (params.get("productid") !== EXPECTED_PRODUCT_ID) {
      console.warn("Unity S2S rejected: unexpected product ID");
      return new Response("Unexpected Unity reward product", { status: 400 });
    }
    if (!isUuid(sessionId) || !isValidTransactionId(transactionId)) {
      console.warn("Unity S2S rejected: invalid reward identity", {
        sessionIdIsUuid: isUuid(sessionId),
        transactionIdLength: transactionId.length,
      });
      return new Response("Invalid Unity reward identity", { status: 400 });
    }

    const headers = {
      "apikey": SUPABASE_SERVICE_ROLE_KEY,
      "Authorization": `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
      "Content-Type": "application/json",
    };
    const sessionResponse = await fetch(
      `${SUPABASE_URL}/rest/v1/ad_reward_sessions?select=user_id&id=eq.${sessionId}&limit=1`,
      { headers },
    );
    if (!sessionResponse.ok) {
      console.error("Unity reward session lookup failed", sessionResponse.status);
      return new Response("Reward session lookup unavailable", { status: 500 });
    }
    const sessions = await sessionResponse.json();
    const userId = Array.isArray(sessions) ? String(sessions[0]?.user_id ?? "") : "";
    if (!isUuid(userId)) {
      console.warn("Unity S2S rejected: reward session not found");
      return new Response("Reward session not found", { status: 400 });
    }

    const grantResponse = await fetch(`${SUPABASE_URL}/rest/v1/rpc/grant_ad_reward`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        p_session_id: sessionId,
        p_user_id: userId,
        p_transaction_id: transactionId,
        p_reward_amount: EXPECTED_REWARD_AMOUNT,
        p_reward_item: WALLET_REWARD_ITEM,
      }),
    });
    if (!grantResponse.ok) {
      const detail = await grantResponse.text();
      console.error("Unity reward grant rejected", grantResponse.status, detail.slice(0, 300));
      return new Response("Reward grant unavailable", { status: 500 });
    }

    // Unity expects a successful HTTP response containing "1" after accepting a reward callback.
    return new Response("1", { status: 200, headers: { "Content-Type": "text/plain" } });
  } catch (error) {
    console.error("Unity S2S callback verification failed", error);
    return new Response("Invalid Unity S2S callback", { status: 400 });
  }
});
