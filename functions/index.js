// The `ai` function: a phone's Messages request in, Meta's answer out, with Kadent's key added on
// the way. Everything it decides is in proxy.js and quota.js, which the tests run; this file only
// connects them to Firebase. Deployed by hand with the owner's go-ahead (AGENTS.md, "Commands").
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getDatabase } from "firebase-admin/database";
import { logger } from "firebase-functions";
import { defineSecret } from "firebase-functions/params";
import { onRequest } from "firebase-functions/v2/https";
import { createHandler } from "./proxy.js";
import { takeQuota } from "./quota.js";

initializeApp();

const META_MODEL_API_KEY = defineSecret("META_MODEL_API_KEY");

const handle = createHandler({
  apiKey: () => META_MODEL_API_KEY.value(),
  // checkRevoked: a signed-out or deleted account's token stops working at once, not when it expires.
  verifyIdToken: (token) => getAuth().verifyIdToken(token, true),
  takeQuota: (uid, now) => takeQuota(getDatabase(), uid, now),
  fetch: globalThis.fetch,
  log: (level, message, fields) => logger[level](message, fields),
  now: () => Date.now(),
});

export const ai = onRequest(
  {
    region: "us-central1",
    secrets: [META_MODEL_API_KEY],
    timeoutSeconds: 120,
    memory: "512MiB",
    // A ceiling on what a flood of requests can cost before the quota catches up.
    maxInstances: 10,
    // Phones call this, not web pages; Kadent's Web target has no AI until it has sign-in (Kadent #73).
    cors: false,
  },
  async (req, res) => {
    const result = await handle({
      method: req.method,
      authorization: req.get("authorization"),
      rawBody: req.rawBody?.toString("utf8") ?? "",
    });
    res.status(result.status).set(result.headers).json(result.body);
  },
);
