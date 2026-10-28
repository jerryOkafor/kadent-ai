# kadent-ai

The AI behind Kadent, a household shopping and budget app, built in the open for the Meta Global
AI Developer Hackathon (Oct 26 to Nov 4, 2026). Kadent itself is a private Kotlin Multiplatform app;
it depends on this repo as a library, the way it depends on its own database library.

The work is tracked in Kadent's private epic, avontech/Kadent#269. This repo is public, so nothing
here may depend on reading that issue: every decision that matters is written down in this file or
beside the code that makes it.

## What is here

| Path | What it is |
|---|---|
| `functions/` | The Cloud Function `ai`: the only place the model key lives. A phone posts a Messages request with its Firebase ID token; the function checks the user, the model and the user's quota, forwards the request to Meta's Model API, and returns the answer. |
| `client/` | `kadent-ai-client`, a Kotlin Multiplatform library (Android, JVM, iOS, Web) that phones use to call the function. Its request and response types follow Anthropic's Messages format, which Meta's Model API accepts. |
| `receipt/` | `kadent-ai-receipt`: `ReceiptReader` reads a receipt's photos or PDF with Muse Spark into a `ReadReceipt`, with each line's clean name, aisle and envelope, the taxes and charges, and `ReceiptChecks`, how far the reading is from the printed total. |
| `scripts/draw-receipt.swift` | Draws a synthetic receipt as a tilted JPEG for the tests. Real receipts never go in this repo. |

## Rules that come first

- **The model key never leaves the function.** No app, test fixture or script in this repo holds a
  real key. The function reads `META_MODEL_API_KEY` from Firebase's secret store.
- **Never call a `-contributor` model.** Meta may train on what those receive, and Kadent sends
  household data. The function refuses any model not on its list (`functions/proxy.js`).
- **This library never imports Kadent's types.** It defines its own, and Kadent converts. An agent's
  tools are interfaces here that Kadent implements over its own database.
- **Nothing private goes in.** No real receipt (use synthetic or redacted ones), no `.env`, no
  Firebase app config file, no Apple team or App Store Connect id. Run `gitleaks detect` before
  every push.

## Commands

```bash
(cd functions && npm ci && npm test)            # the function's tests, no network, no emulator
./gradlew :client:jvmTest :receipt:jvmTest     # the Kotlin tests, no network
META_MODEL_API_KEY=... ./gradlew :receipt:jvmTest --tests '*LiveReadingTest*'   # one real read; report in receipt/build/live-reading.txt
./gradlew publishToMavenLocal                   # what Kadent resolves both libraries from (~/.m2)
gitleaks detect                                 # before every push
```

Deploying is by hand and only with the owner's go-ahead. It goes to Kadent's Firebase project,
`withkadent`, under this repo's own functions codebase, `kadent-ai` (`firebase.json`):

```bash
firebase functions:secrets:set META_MODEL_API_KEY --project withkadent   # once
firebase deploy --only functions:kadent-ai --project withkadent
```

The codebase is what keeps the two repos from deleting each other's functions. Kadent deploys its
own functions (the household relay's daily clean-up) from its `default` codebase; a deploy scoped
to one codebase never deletes a function that belongs to another.
