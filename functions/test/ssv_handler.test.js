// Offline test: runs against the COMPILED lib/. No Firebase project or network needed.
// Exercises the COMPILED admobRewardCallback handler with a local key pair standing in for Google's verifier keys.
process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "tapnex-74d6d";
process.env.ADMOB_REWARD_AD_UNIT_IDS = "ca-app-pub-1234567890123456/9999999999";
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:1"; // closed port: DB calls fail fast, nothing real is touched
const crypto = require("crypto");
const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
const pem = publicKey.export({ type: "spki", format: "pem" });
let keyFetches = 0;
global.fetch = async () => { keyFetches++; return { ok: true, json: async () => ({ keys: [{ keyId: 4242, pem }] }) }; };

const fn = require(require("path").join(__dirname, "..", "lib", "index.js")).admobRewardCallback;

function signedUrl(params, keyId = 4242, key = privateKey) {
  const message = Object.entries(params).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
  const sig = crypto.sign("sha256", Buffer.from(message), key).toString("base64url");
  return `/admobRewardCallback?${message}&signature=${sig}&key_id=${keyId}`;
}
function call(url, method = "GET") {
  return new Promise((resolve) => {
    const res = { code: 200, status(c) { this.code = c; return this; }, send(b) { resolve({ code: this.code, body: b }); } };
    fn({ method, originalUrl: url, url }, res);
    setTimeout(() => resolve({ code: "NO_RESPONSE(reached DB or pending)", body: "" }), 3000);
  });
}
const good = { ad_network: "5450213213286189855", ad_unit: "ca-app-pub-1234567890123456/9999999999", reward_amount: "5", reward_item: "Credits", timestamp: "1700000000000", transaction_id: "txn-abcdef-123456", user_id: "uid_abc123" };
(async () => {
  const results = [];
  const check = async (name, url, expectCode) => {
    const r = await call(url);
    const ok = String(r.code) === String(expectCode);
    results.push(`${ok ? "PASS" : "FAIL"}  ${name}  -> ${r.code} ${r.body}`);
  };
  await check("POST rejected", signedUrl(good).replace("?", "?"), 200).catch(()=>{}); // placeholder, replaced below
  results.length = 0;
  await check("missing query -> 400", "/admobRewardCallback", 400);
  await check("unsigned -> 400", "/admobRewardCallback?a=1", 400);
  await check("tampered reward_amount -> 403", signedUrl(good).replace("reward_amount=5", "reward_amount=500"), 403);
  await check("tampered user_id -> 403", signedUrl(good).replace("user_id=uid_abc123", "user_id=attacker"), 403);
  const wrongKey = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" }).privateKey;
  await check("signed by unknown key -> 403", signedUrl(good, 4242, wrongKey), 403);
  await check("unknown key id -> 403", signedUrl(good, 999), 403);
  await check("TEST ad unit (valid sig) -> 403", signedUrl({ ...good, ad_unit: "ca-app-pub-3940256099942544/5224354917" }), 403);
  await check("unlisted production ad unit -> 403", signedUrl({ ...good, ad_unit: "ca-app-pub-1111111111111111/2222222222" }), 403);
  await check("bad transaction id -> 400", signedUrl({ ...good, transaction_id: "x" }), 400);
  await check("valid allowed unit reaches DB (not rejected at checks)", signedUrl(good), "NO_RESPONSE(reached DB or pending)");
  console.log(results.join("\n"));
  console.log("key downloads:", keyFetches);
  process.exit(results.some(l => l.startsWith("FAIL")) ? 1 : 0);
})();
