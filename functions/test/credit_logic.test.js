// Offline test: runs against the COMPILED lib/. No Firebase project or network needed.
// Runs the COMPILED callback + startSession logic against an in-memory Firestore stand-in.
// Verifies: one credit per transaction id, replays are no-ops, wallet totals, unlock charging is atomic.
process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "tapnex-74d6d";
process.env.ADMOB_REWARD_AD_UNIT_IDS = "ca-app-pub-1234567890123456/9999999999";
const crypto = require("crypto");
const path = require("path");
const fnDir = require("path").join(__dirname, "..");
const store = new Map();
const INC = Symbol("inc");
const fakeFS = {
  getFirestore: () => ({
    doc: (p) => ({ path: p }),
    runTransaction: async (fn) => {
      // Real Firestore retries/serialises; here a single transaction runs at a time (sufficient for logic checks).
      const writes = [];
      const tx = {
        get: async (ref) => ({ exists: store.has(ref.path), get: (f) => (store.get(ref.path) || {})[f], _data: store.get(ref.path) }),
        create: (ref, data) => { if (store.has(ref.path)) throw new Error("ALREADY_EXISTS " + ref.path); writes.push(["create", ref.path, data]); },
        set: (ref, data, opts) => { writes.push(["set", ref.path, data, opts]); },
        update: (ref, data) => { if (!store.has(ref.path)) throw new Error("NOT_FOUND"); writes.push(["update", ref.path, data]); },
      };
      const result = await fn(tx);
      for (const [op, p, data, opts] of writes) {
        const cur = store.get(p) || {};
        const applied = { ...cur };
        for (const [k, v] of Object.entries(data)) {
          if (v && v[INC] !== undefined) applied[k] = (typeof cur[k] === "number" ? cur[k] : 0) + v[INC];
          else applied[k] = v;
        }
        store.set(p, applied);
      }
      return result;
    },
  }),
  FieldValue: { increment: (n) => ({ [INC]: n }), serverTimestamp: () => "TS" },
};
const fsPath = require.resolve("firebase-admin/firestore", { paths: [fnDir] });
require.cache[fsPath] = { id: fsPath, filename: fsPath, loaded: true, exports: fakeFS };

const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
global.fetch = async () => ({ ok: true, json: async () => ({ keys: [{ keyId: 7, pem: publicKey.export({ type: "spki", format: "pem" }) }] }) });
const lib = require(require("path").join(fnDir, "lib", "index.js"));

function signed(params) {
  const message = Object.entries(params).map(([k, v]) => `${k}=${v}`).join("&");
  const sig = crypto.sign("sha256", Buffer.from(message), privateKey).toString("base64url");
  return `/x?${message}&signature=${sig}&key_id=7`;
}
function hit(url) {
  return new Promise((resolve, reject) => {
    const res = { code: 200, status(c) { this.code = c; return this; }, send(b) { resolve({ code: this.code, body: b }); } };
    Promise.resolve(lib.admobRewardCallback({ method: "GET", originalUrl: url, url }, res)).catch(reject);
  });
}
const base = { ad_network: "1", ad_unit: "ca-app-pub-1234567890123456/9999999999", reward_amount: "5", reward_item: "Credits", timestamp: "1700000000000", user_id: "uidA" };
const wallet = () => store.get("users/uidA/wallet/current");
(async () => {
  const out = [];
  const t = (name, ok, extra="") => out.push(`${ok ? "PASS" : "FAIL"}  ${name} ${extra}`);
  // 1. first verified callback credits exactly 5
  let r = await hit(signed({ ...base, transaction_id: "txn-0001-aaaa" }));
  t("first callback credits", r.code === 200 && wallet().balance === 5 && wallet().totalEarned === 5, `(code ${r.code}, balance ${wallet().balance}, earned ${wallet().totalEarned})`);
  // 2. identical replay credits nothing
  r = await hit(signed({ ...base, transaction_id: "txn-0001-aaaa" }));
  t("replay of same transaction is a no-op", r.code === 200 && r.body === "already credited" && wallet().balance === 5, `(body "${r.body}", balance ${wallet().balance})`);
  // 3. a different transaction id credits again
  r = await hit(signed({ ...base, transaction_id: "txn-0002-bbbb" }));
  t("new transaction id credits once more", wallet().balance === 10 && wallet().totalEarned === 10, `(balance ${wallet().balance})`);
  // 4. reward_amount in the callback is ignored (always 5)
  r = await hit(signed({ ...base, reward_amount: "500", transaction_id: "txn-0003-cccc" }));
  t("callback reward_amount ignored (fixed 5)", wallet().balance === 15, `(balance ${wallet().balance})`);
  // 5. claim + transaction records written
  t("claim doc and transaction record written", store.has("rewardClaims/txn-0001-aaaa") && store.has("users/uidA/transactions/reward_txn-0001-aaaa"));
  // 6. reserved and malformed ids are rejected before any credit
  r = await hit(signed({ ...base, transaction_id: "__reserved__" })); t("reserved transaction id rejected", r.code === 400 && wallet().balance === 15, `(code ${r.code})`);
  r = await hit(signed({ ...base, user_id: "uidA/../../other", transaction_id: "txn-0009-zzzz" })); t("path-like user_id rejected", r.code === 400 && !store.has("users/uidA/../../other/wallet/current") && wallet().balance === 15, `(code ${r.code})`);
  // 7. unlock charging: startSession via the real callable handler
  store.set("users/uidA/wallet/current", { balance: 15, totalEarned: 15, totalSpent: 0 });
  const callable = lib.startSession;
  const call = (data, auth = { uid: "uidA" }) => callable.run({ data, auth });
  const rid = "req-0001-abcdef";
  let s = await call({ requestId: rid, targetPackage: "com.whatsapp" });
  t("unlock charges 5 and records session", wallet().balance === 10 && wallet().totalSpent === 5 && store.has("sessions/" + rid), `(balance ${wallet().balance}, spent ${wallet().totalSpent})`);
  s = await call({ requestId: rid, targetPackage: "com.whatsapp" });
  t("same requestId is idempotent (no second charge)", wallet().balance === 10, `(balance ${wallet().balance})`);
  let err = "";
  try { await call({ requestId: "req-0002-abcdef", targetPackage: "com.google.android.youtube" }, { uid: "uidA" }); await call({ requestId: "req-0003-abcdef", targetPackage: "com.a.b" }); await call({ requestId: "req-0004-abcdef", targetPackage: "com.c.d" }); await call({ requestId: "req-0005-abcdef", targetPackage: "com.e.f" }); await call({ requestId: "req-0006-abcdef", targetPackage: "com.g.h" }); await call({ requestId: "req-0007-abcdef", targetPackage: "com.i.j" }); await call({ requestId: "req-0008-abcdef", targetPackage: "com.k.l" }); await call({ requestId: "req-0009-abcdef", targetPackage: "com.m.n" }); await call({ requestId: "req-0010-abcdef", targetPackage: "com.o.p" }); } catch (e) { err = e.message; }
  t("insufficient credits refuses unlock (no negative balance)", /Insufficient credits/.test(err) && wallet().balance >= 0, `(balance ${wallet().balance}, err "${err}")`);
  // 8. client-supplied invalid package rejected before any charge
  const before = wallet().balance;
  let bad = ""; try { await call({ requestId: "req-bad-000001", targetPackage: "com.tapnex.autoclicker" }); } catch (e) { bad = e.message; }
  t("own package / invalid package rejected, no charge", /Invalid target package/.test(bad) && wallet().balance === before);
  bad = ""; try { await call({ requestId: "req-bad-000002", targetPackage: "x; rm -rf" }); } catch (e) { bad = e.message; }
  t("malicious package string rejected", /Invalid target package/.test(bad) && wallet().balance === before);
  console.log(out.join("\n"));
  process.exit(out.some(l => l.startsWith("FAIL")) ? 1 : 0);
})().catch(e => { console.error("HARNESS ERROR", e); process.exit(1); });
