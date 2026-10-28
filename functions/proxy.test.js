import assert from "node:assert/strict";
import { test } from "node:test";
import { createHandler, MAX_BODY_BYTES, MAX_TOKENS } from "./proxy.js";

const KEY = "test-key-not-real";
const PROMPT = "milk 2.99 eggs 4.49 a line only the model should see";

function request(overrides = {}) {
  const body = {
    model: "muse-spark-1.3",
    max_tokens: 1024,
    messages: [{ role: "user", content: PROMPT }],
    ...overrides,
  };
  return { method: "POST", authorization: "Bearer good-token", rawBody: JSON.stringify(body) };
}

function setup({ upstream, quota } = {}) {
  const calls = [];
  const logs = [];
  const handler = createHandler({
    apiKey: () => KEY,
    verifyIdToken: async (token) => {
      if (token !== "good-token") throw new Error("bad token");
      return { uid: "user-1" };
    },
    takeQuota: async () => quota ?? { allowed: true },
    fetch: async (url, init) => {
      calls.push({ url, init, body: JSON.parse(init.body) });
      return upstream ? upstream() : jsonResponse(200, { id: "msg_1", type: "message", content: [], usage: { input_tokens: 10, output_tokens: 2 } });
    },
    log: (level, message, fields) => logs.push({ level, message, fields }),
    now: () => 0,
  });
  return { handler, calls, logs };
}

function jsonResponse(status, body, headers = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

test("anything but a POST is refused", async () => {
  const { handler, calls } = setup();
  const res = await handler({ ...request(), method: "GET" });
  assert.equal(res.status, 405);
  assert.equal(calls.length, 0);
});

test("a request with no ID token is refused as signed out", async () => {
  const { handler, calls } = setup();
  const res = await handler({ ...request(), authorization: undefined });
  assert.equal(res.status, 401);
  assert.equal(res.body.error.type, "unauthenticated");
  assert.equal(calls.length, 0);
});

test("a request with an ID token Firebase rejects is refused as signed out", async () => {
  const { handler, calls } = setup();
  const res = await handler({ ...request(), authorization: "Bearer forged" });
  assert.equal(res.status, 401);
  assert.equal(calls.length, 0);
});

test("a body over the size limit is refused before it is read", async () => {
  const { handler, calls } = setup();
  const res = await handler({ ...request(), rawBody: "x".repeat(MAX_BODY_BYTES + 1) });
  assert.equal(res.status, 413);
  assert.equal(calls.length, 0);
});

test("a body that is not JSON is refused", async () => {
  const { handler } = setup();
  const res = await handler({ ...request(), rawBody: "{not json" });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.type, "invalid_request");
});

test("a request missing its messages is refused", async () => {
  const { handler } = setup();
  const res = await handler(request({ messages: undefined }));
  assert.equal(res.status, 400);
});

test("a contributor model is refused, because Meta may train on what it receives", async () => {
  const { handler, calls } = setup();
  const res = await handler(request({ model: "muse-spark-1.3-contributor" }));
  assert.equal(res.status, 403);
  assert.equal(res.body.error.type, "model_not_allowed");
  assert.equal(calls.length, 0);
});

test("a model not on the list is refused", async () => {
  const { handler } = setup();
  const res = await handler(request({ model: "some-other-model" }));
  assert.equal(res.status, 403);
});

test("streaming is refused, because the function returns one answer", async () => {
  const { handler } = setup();
  const res = await handler(request({ stream: true }));
  assert.equal(res.status, 400);
});

test("max_tokens above the cap is refused", async () => {
  const { handler } = setup();
  const res = await handler(request({ max_tokens: MAX_TOKENS + 1 }));
  assert.equal(res.status, 400);
});

test("a person over their quota is told when to try again, and nothing is sent", async () => {
  const { handler, calls } = setup({ quota: { allowed: false, retryAfterSeconds: 17 } });
  const res = await handler(request());
  assert.equal(res.status, 429);
  assert.equal(res.headers["retry-after"], "17");
  assert.equal(calls.length, 0);
});

test("an allowed request goes to Meta's Messages endpoint with the key as a bearer token", async () => {
  const { handler, calls } = setup();
  const res = await handler(request());
  assert.equal(res.status, 200);
  assert.equal(res.body.id, "msg_1");
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://api.meta.ai/v1/messages");
  assert.equal(calls[0].init.headers.authorization, `Bearer ${KEY}`);
  assert.equal(calls[0].body.messages[0].content, PROMPT);
});

test("a key stored with whitespace around it is sent without it", async () => {
  const calls = [];
  const handler = createHandler({
    apiKey: () => `  ${KEY}\n`,
    verifyIdToken: async () => ({ uid: "user-1" }),
    takeQuota: async () => ({ allowed: true }),
    fetch: async (url, init) => {
      calls.push(init);
      return jsonResponse(200, { id: "msg_1", content: [] });
    },
    log: () => {},
    now: () => 0,
  });
  await handler(request());
  assert.equal(calls[0].headers.authorization, `Bearer ${KEY}`);
});

test("fields the function does not pass on are dropped before forwarding", async () => {
  const { handler, calls } = setup();
  await handler(request({ metadata: { user_id: "someone" }, output_config: { format: { type: "json_schema", schema: {} } } }));
  assert.equal(calls[0].body.metadata, undefined);
  assert.deepEqual(calls[0].body.output_config, { format: { type: "json_schema", schema: {} } });
});

test("Meta's rate limit reaches the phone with its Retry-After", async () => {
  const { handler } = setup({ upstream: () => jsonResponse(429, { message: "slow down", type: "rate_limit_exceeded" }, { "retry-after": "9" }) });
  const res = await handler(request());
  assert.equal(res.status, 429);
  assert.equal(res.headers["retry-after"], "9");
});

test("Meta being overloaded reaches the phone as unavailable, with its Retry-After", async () => {
  const { handler } = setup({ upstream: () => jsonResponse(503, { message: "busy", type: "service_overloaded" }, { "retry-after": "60" }) });
  const res = await handler(request());
  assert.equal(res.status, 503);
  assert.equal(res.headers["retry-after"], "60");
});

test("Meta refusing Kadent's key is the function's fault, not the phone's", async () => {
  for (const status of [401, 402, 403, 404]) {
    const { handler, logs } = setup({ upstream: () => jsonResponse(status, { message: "nope", type: "authentication_error" }) });
    const res = await handler(request());
    assert.equal(res.status, 502, `upstream ${status}`);
    assert.equal(res.body.error.type, "upstream_unavailable");
    assert.ok(logs.some((l) => l.level === "error"), `upstream ${status} is logged as an error`);
  }
});

test("Meta refusing the request itself passes its message to the phone", async () => {
  const { handler } = setup({ upstream: () => jsonResponse(400, { message: "max_tokens too large", type: "invalid_request_error" }) });
  const res = await handler(request());
  assert.equal(res.status, 400);
  assert.equal(res.body.error.message, "max_tokens too large");
});

test("Meta not answering in time is a timeout", async () => {
  const { handler } = setup({ upstream: () => { throw Object.assign(new Error("timed out"), { name: "TimeoutError" }); } });
  const res = await handler(request());
  assert.equal(res.status, 504);
});

test("Meta unreachable is unavailable", async () => {
  const { handler } = setup({ upstream: () => { throw new TypeError("fetch failed"); } });
  const res = await handler(request());
  assert.equal(res.status, 503);
});

test("the log records the call but never what was sent or answered", async () => {
  const { handler, logs } = setup({
    upstream: () => jsonResponse(200, { id: "msg_1", content: [{ type: "text", text: "the model's private answer" }], usage: { input_tokens: 10, output_tokens: 2 } }),
  });
  await handler(request());
  const written = JSON.stringify(logs);
  assert.ok(written.includes("muse-spark-1.3"));
  assert.ok(!written.includes(PROMPT));
  assert.ok(!written.includes("the model's private answer"));
  assert.ok(!written.includes(KEY));
});
