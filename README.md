# kadent-ai

The AI behind [Kadent](https://withkadent.web.app), a shopping and budget app for families on a
budget. Built in the open for the Meta Global AI Developer Hackathon (Oct 26 to Nov 4, 2026), on
Meta's Muse models.

**What it is for.** One receipt photo should update everything at once: the household's shopping
lists, the prices it pays, its budget and the tax it paid. Then an agent plans next week's shopping
from that history. Kadent's app is private; this repo holds every AI piece it uses, and the app
depends on it as a library.

## What is here so far

| Path | What it does |
|---|---|
| [`functions/`](functions) | `ai`, a Firebase Cloud Function and the only place the model key lives. A phone sends a Messages request with the person's Firebase ID token. The function checks the token, the model, the size and the person's quota, forwards the request to Meta's Model API, and returns the answer. It logs that a call happened, never what was in it. |
| [`client/`](client) | `kadent-ai-client`, a Kotlin Multiplatform library for Android, iOS, desktop and Web. It sends Messages requests through the function, refreshes an expired sign-in once, and waits out a short rate limit before giving up. |
| [`receipt/`](receipt) | `kadent-ai-receipt`. Reads a receipt's photos or PDF with Muse Spark into structured lines: the till's text and a clean name ("SHRD CHEDDAR 16Z" is "Shredded cheddar · 16oz"), counts, weights, codes, an aisle and a budget envelope from the household's own lists, taxes by kind, fees, deposits and totals, all in exact cents. It also measures how far the reading is from the printed total. |

Coming next, tracked in Kadent's epic: filing a clean receipt with nothing to fix, an agent that plans next week's list, and a
script that scores each reader on the same receipts.

## Privacy rules this code keeps

- The model key stays in the Cloud Function's secret store; no app holds it.
- Meta's `-contributor` models are refused, because Meta may train on what they receive.
- Each call sends only what one feature needs, and nothing is stored after the answer.

## Running it

```bash
(cd functions && npm ci && npm test)      # the function's tests
./gradlew :client:jvmTest :receipt:jvmTest   # the Kotlin tests
```

`AGENTS.md` has the deploy commands and the rules for working in this repo.

## License

Apache-2.0. See [LICENSE](LICENSE).
