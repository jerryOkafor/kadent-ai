// What the `ai` function does with one request, apart from the HTTP plumbing in index.js, so the
// tests can run it without a server, a database or the network. Every dependency is passed in.
//
// The request a phone sends is an Anthropic Messages request, which Meta's Model API accepts at
// /v1/messages. The phone sends its Firebase ID token where the key would go; the function swaps it
// for Kadent's key. Everything else about the request is the phone's to decide, within the checks
// below.

export const META_MESSAGES_URL = "https://api.meta.ai/v1/messages";

/**
 * The models a phone may ask for. A list rather than a pattern, so a new Meta model is used only
 * after someone has read its terms. The `-contributor` models are never on it: they are cheaper
 * because Meta may train on what they receive, and Kadent sends a household's receipts.
 */
export const ALLOWED_MODELS = new Set(["muse-spark-1.3", "muse-spark-1.2", "muse-wonder-1.0"]);

/**
 * The request fields passed on to Meta. Anything else a phone sends is dropped, so a field Meta adds
 * later (or `metadata`, which would carry an id to Meta) is never forwarded by accident.
 */
const FORWARDED_FIELDS = ["model", "max_tokens", "messages", "system", "tools", "tool_choice", "temperature", "top_p", "output_config", "thinking"];

/**
 * 20 MiB. A receipt photo sent as base64 is 1 to 3 MB, and a long receipt is several photos. Cloud
 * Run refuses anything over 32 MiB before the function runs, so this limit only has to be below that.
 */
export const MAX_BODY_BYTES = 20 * 1024 * 1024;

/** The longest answer a phone may ask for. A filed receipt's JSON is a few thousand tokens. */
export const MAX_TOKENS = 16_384;

/** Just under the function's own 120 s timeout (index.js), so a slow answer is reported, not cut. */
const UPSTREAM_TIMEOUT_MS = 110_000;

/**
 * @param deps.apiKey        () => the Meta key, read when a call needs it
 * @param deps.verifyIdToken (token) => { uid }, throwing for a token Firebase rejects
 * @param deps.takeQuota     (uid, now) => { allowed, retryAfterSeconds }
 * @param deps.fetch         the Fetch API
 * @param deps.log           (level, message, fields) => void
 * @param deps.now           () => milliseconds since the epoch
 * @returns (req: { method, authorization, rawBody }) => { status, headers, body }
 */
export function createHandler(deps) {
  return async function handle(req) {
    if (req.method !== "POST") return error(405, "method_not_allowed", "Use POST.");

    const token = /^Bearer (.+)$/.exec(req.authorization ?? "")?.[1];
    if (!token) return error(401, "unauthenticated", "Sign in to use AI features.");
    let uid;
    try {
      ({ uid } = await deps.verifyIdToken(token));
    } catch {
      return error(401, "unauthenticated", "Sign in again to use AI features.");
    }

    if (Buffer.byteLength(req.rawBody ?? "") > MAX_BODY_BYTES) {
      return error(413, "payload_too_large", `The request is over ${MAX_BODY_BYTES} bytes.`);
    }
    let body;
    try {
      body = JSON.parse(req.rawBody);
    } catch {
      return error(400, "invalid_request", "The body is not JSON.");
    }
    const refusal = check(body);
    if (refusal) return refusal;

    const quota = await deps.takeQuota(uid, deps.now());
    if (!quota.allowed) {
      return error(429, "rate_limited", "Too many AI requests. Try again later.", { "retry-after": String(quota.retryAfterSeconds) });
    }

    const forwarded = Object.fromEntries(FORWARDED_FIELDS.filter((f) => body[f] !== undefined).map((f) => [f, body[f]]));
    const started = deps.now();
    const fields = { uid, model: body.model };

    let upstream;
    try {
      upstream = await deps.fetch(META_MESSAGES_URL, {
        method: "POST",
        headers: { authorization: `Bearer ${deps.apiKey()}`, "content-type": "application/json", "anthropic-version": "2023-06-01" },
        body: JSON.stringify(forwarded),
        signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      });
    } catch (failure) {
      const timedOut = failure?.name === "TimeoutError" || failure?.name === "AbortError";
      deps.log("warn", "the model did not answer", { ...fields, ms: deps.now() - started, failure: failure?.name });
      return timedOut
        ? error(504, "timeout", "The model took too long to answer.")
        : error(503, "unavailable", "The model could not be reached.");
    }

    const answer = await upstream.json().catch(() => null);
    fields.ms = deps.now() - started;
    fields.upstreamStatus = upstream.status;

    if (upstream.ok) {
      deps.log("info", "answered", { ...fields, inputTokens: answer?.usage?.input_tokens, outputTokens: answer?.usage?.output_tokens });
      return { status: 200, headers: {}, body: answer };
    }

    const retryAfter = upstream.headers.get("retry-after");
    const retryHeaders = retryAfter ? { "retry-after": retryAfter } : {};
    const message = answer?.error?.message ?? answer?.message ?? "The model refused the request.";

    switch (upstream.status) {
      case 400:
      case 413:
        // The phone's request was wrong: say what Meta said, so it can be fixed.
        deps.log("warn", "the model refused the request", fields);
        return error(upstream.status, "invalid_request", message);
      case 429:
        deps.log("warn", "the model's rate limit", fields);
        return error(429, "rate_limited", "The model is busy. Try again shortly.", retryHeaders);
      case 401:
      case 402:
      case 403:
      case 404:
        // Kadent's key, billing or model access is broken. That is not the phone's doing and not
        // something a person can fix, so it is a 502, and it is an error in the log for the owner.
        deps.log("error", "the model refused Kadent's key or model", fields);
        return error(502, "upstream_unavailable", "AI features are unavailable right now.");
      case 504:
        deps.log("warn", "the model timed out", fields);
        return error(504, "timeout", "The model took too long to answer.");
      default:
        deps.log("warn", "the model is unavailable", fields);
        return error(503, "unavailable", "The model is unavailable. Try again shortly.", retryHeaders);
    }
  };
}

/** The first thing wrong with a request's body, as the response that refuses it, or null. */
function check(body) {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return error(400, "invalid_request", "The body must be a JSON object.");
  }
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    return error(400, "invalid_request", "messages must be a non-empty array.");
  }
  if (!Number.isInteger(body.max_tokens) || body.max_tokens < 1) {
    return error(400, "invalid_request", "max_tokens must be a positive integer.");
  }
  if (body.max_tokens > MAX_TOKENS) {
    return error(400, "invalid_request", `max_tokens may be at most ${MAX_TOKENS}.`);
  }
  if (body.stream) {
    return error(400, "invalid_request", "Streaming is not supported.");
  }
  if (!ALLOWED_MODELS.has(body.model)) {
    return error(403, "model_not_allowed", `${body.model} is not a model Kadent uses.`);
  }
  return null;
}

function error(status, type, message, headers = {}) {
  return { status, headers, body: { error: { type, message } } };
}
