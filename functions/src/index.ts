import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { onCall, onRequest, HttpsError } from "firebase-functions/v2/https";
import { verify as cryptoVerify } from "node:crypto";

initializeApp();
const db = getFirestore();

const SESSION_COST = 5;
/** Fixed server-side reward. The reward_amount sent by AdMob is never trusted. */
const REWARD_CREDIT = 5;

/** Google publishes the public keys used to sign SSV callbacks here. */
const ADMOB_VERIFIER_KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
/** Test ad units start with this prefix. They must never grant production credits. */
const ADMOB_TEST_PREFIX = "ca-app-pub-3940256099942544/";

const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const REQUEST_ID_RE = /^[A-Za-z0-9._:-]{8,128}$/;
// Firestore reserves ids of the form __x__, so reject them before any write.
const TRANSACTION_ID_RE = /^(?!__.*__$)[A-Za-z0-9._:-]{8,200}$/;
// Firebase Auth uids are alphanumeric with _ or -. Anything else could alter a document path.
const UID_RE = /^[A-Za-z0-9_-]{1,128}$/;

function authUid(request: any): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign-in is required.");
  return uid;
}

/** Callable is idempotent by client-provided requestId; all balance mutation occurs server-side. */
export const startSession = onCall(async (request) => {
  const uid = authUid(request);
  const requestId = String(request.data?.requestId ?? "");
  const target = String(request.data?.targetPackage ?? "");
  if (!REQUEST_ID_RE.test(requestId)) throw new HttpsError("invalid-argument", "Invalid requestId.");
  // The authenticated user chooses a launchable app from the on-device picker.
  // Validate package-name syntax; never accept an arbitrary command or intent from the client.
  if (!PACKAGE_RE.test(target) || target === "com.tapnex.autoclicker") {
    throw new HttpsError("invalid-argument", "Invalid target package.");
  }
  const walletRef = db.doc(`users/${uid}/wallet/current`);
  const sessionRef = db.doc(`sessions/${requestId}`);
  const txRef = db.doc(`users/${uid}/transactions/${requestId}`);
  return db.runTransaction(async tx => {
    const [walletSnap, sessionSnap] = await Promise.all([tx.get(walletRef), tx.get(sessionRef)]);
    if (sessionSnap.exists) {
      if (sessionSnap.get("uid") !== uid) throw new HttpsError("already-exists", "Request ID already used.");
      return { sessionId: requestId, status: sessionSnap.get("status"), idempotent: true };
    }
    if (!walletSnap.exists) throw new HttpsError("failed-precondition", "Wallet is not initialized.");
    const balance = Number(walletSnap.get("balance") ?? 0);
    if (balance < SESSION_COST) throw new HttpsError("failed-precondition", "Insufficient credits.");
    tx.update(walletRef, {
      balance: balance - SESSION_COST,
      totalSpent: FieldValue.increment(SESSION_COST),
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.create(sessionRef, {
      uid,
      status: "accepted",
      startedAt: FieldValue.serverTimestamp(),
      chargedCredits: SESSION_COST,
      selectedTarget: target
    });
    tx.create(txRef, {
      transactionId: requestId,
      type: "session_charge",
      amount: -SESSION_COST,
      description: "App unlock (5 credits)",
      sessionId: requestId,
      createdAt: FieldValue.serverTimestamp(),
      status: "posted"
    });
    return { sessionId: requestId, status: "accepted", idempotent: false };
  });
});

/**
 * Client-side reward claims are never accepted. Rewards are granted only by `admobRewardCallback`,
 * after Google's signature on the SSV request has been verified.
 */
export const rewardClaimClientBlocked = onCall(async (request) => {
  authUid(request);
  throw new HttpsError("permission-denied", "Rewards are granted only by verified AdMob server-side callbacks.");
});

export const initializeWallet = onCall(async (request) => {
  const uid = authUid(request);
  const ref = db.doc(`users/${uid}/wallet/current`);
  await db.runTransaction(async tx => {
    const snap = await tx.get(ref);
    if (!snap.exists) tx.create(ref, { balance: 0, totalEarned: 0, totalSpent: 0, updatedAt: FieldValue.serverTimestamp() });
  });
  return { initialized: true, initialBalance: 0 };
});

// ---------------------------------------------------------------------------
// AdMob Server-Side Verification (SSV)
// ---------------------------------------------------------------------------

type VerifierKey = { keyId: number; pem: string };
let verifierKeyCache: { fetchedAt: number; keys: VerifierKey[] } | null = null;
const KEY_CACHE_MS = 60 * 60 * 1000;

async function fetchVerifierKeys(force: boolean): Promise<VerifierKey[]> {
  const fresh = verifierKeyCache !== null && Date.now() - verifierKeyCache.fetchedAt < KEY_CACHE_MS;
  if (!force && fresh && verifierKeyCache) return verifierKeyCache.keys;
  const response = await fetch(ADMOB_VERIFIER_KEYS_URL);
  if (!response.ok) throw new Error(`Verifier key download failed: ${response.status}`);
  const body = (await response.json()) as { keys?: Array<{ keyId: number; pem: string }> };
  const keys = (body.keys ?? [])
    .filter(k => typeof k.keyId === "number" && typeof k.pem === "string")
    .map(k => ({ keyId: k.keyId, pem: k.pem }));
  verifierKeyCache = { fetchedAt: Date.now(), keys };
  return keys;
}

async function findVerifierKey(keyId: number): Promise<string | null> {
  const cached = (await fetchVerifierKeys(false)).find(k => k.keyId === keyId);
  if (cached) return cached.pem;
  // Keys rotate. If this key id is unknown, refresh once before rejecting.
  const refreshed = (await fetchVerifierKeys(true)).find(k => k.keyId === keyId);
  return refreshed ? refreshed.pem : null;
}

/** Ad units allowed to grant credits, comma-separated, set in functions/.env. Empty means reject all. */
function allowedRewardAdUnits(): Set<string> {
  const raw = process.env.ADMOB_REWARD_AD_UNIT_IDS ?? "";
  return new Set(raw.split(",").map(s => s.trim()).filter(s => s.length > 0));
}

function decodeSignature(sig: string): Buffer {
  const normalized = sig.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  return Buffer.from(padded, "base64");
}

/**
 * Receives AdMob's SSV callback. Google signs the raw query string before "&signature=",
 * so verification uses the original, undecoded bytes of the request URL.
 * Responses: 200 when credited or already credited (idempotent), 4xx when rejected.
 */
export const admobRewardCallback = onRequest(async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).send("Method not allowed");
    return;
  }
  const rawUrl = req.originalUrl ?? req.url ?? "";
  const qIndex = rawUrl.indexOf("?");
  if (qIndex < 0) {
    res.status(400).send("Missing query string");
    return;
  }
  const query = rawUrl.slice(qIndex + 1);
  const sigPos = query.indexOf("&signature=");
  if (sigPos < 0) {
    res.status(400).send("Missing signature");
    return;
  }
  const message = query.slice(0, sigPos);
  const params = new URLSearchParams(query);
  const signature = params.get("signature") ?? "";
  const keyId = Number(params.get("key_id"));
  if (!signature || !Number.isFinite(keyId)) {
    res.status(400).send("Malformed signature parameters");
    return;
  }

  let pem: string | null;
  try {
    pem = await findVerifierKey(keyId);
  } catch (e) {
    console.error("SSV key fetch failed", e);
    res.status(503).send("Verifier unavailable");
    return;
  }
  if (!pem) {
    res.status(403).send("Unknown key id");
    return;
  }
  const valid = cryptoVerify("sha256", Buffer.from(message, "utf8"), pem, decodeSignature(signature));
  if (!valid) {
    res.status(403).send("Invalid signature");
    return;
  }

  const adUnit = params.get("ad_unit") ?? "";
  const transactionId = params.get("transaction_id") ?? "";
  const uid = params.get("user_id") ?? "";

  if (adUnit.startsWith(ADMOB_TEST_PREFIX)) {
    res.status(403).send("Test ad units never grant credits");
    return;
  }
  if (!allowedRewardAdUnits().has(adUnit)) {
    res.status(403).send("Ad unit is not enabled for rewards");
    return;
  }
  if (!TRANSACTION_ID_RE.test(transactionId)) {
    res.status(400).send("Invalid transaction id");
    return;
  }
  if (!UID_RE.test(uid)) {
    res.status(400).send("Invalid user id");
    return;
  }

  // Atomic and idempotent: the claim doc keyed by transaction_id makes replays a no-op.
  const claimRef = db.doc(`rewardClaims/${transactionId}`);
  const walletRef = db.doc(`users/${uid}/wallet/current`);
  const txRef = db.doc(`users/${uid}/transactions/reward_${transactionId}`);
  const outcome = await db.runTransaction(async tx => {
    const claimSnap = await tx.get(claimRef);
    if (claimSnap.exists) return "duplicate";
    tx.create(claimRef, {
      uid,
      transactionId,
      adUnit,
      credits: REWARD_CREDIT,
      source: "admob_ssv",
      createdAt: FieldValue.serverTimestamp()
    });
    tx.set(
      walletRef,
      {
        balance: FieldValue.increment(REWARD_CREDIT),
        totalEarned: FieldValue.increment(REWARD_CREDIT),
        updatedAt: FieldValue.serverTimestamp()
      },
      { merge: true }
    );
    tx.create(txRef, {
      transactionId: `reward_${transactionId}`,
      type: "reward",
      amount: REWARD_CREDIT,
      description: "Rewarded ad (5 credits)",
      status: "posted",
      createdAt: FieldValue.serverTimestamp()
    });
    return "credited";
  });

  res.status(200).send(outcome === "duplicate" ? "already credited" : "credited");
});
