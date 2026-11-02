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
| [`client/`](client) | `kadent-ai-client`, a Kotlin Multiplatform library for Android, iOS, desktop and Web. It sends Messages requests through the function, refreshes an expired sign-in once, and waits out a short rate limit before giving up. Its `ToolLoop` runs an agent: it runs each tool the model calls and sends the answers back until one call finishes the run. |
| [`receipt/`](receipt) | `kadent-ai-receipt`. Reads a receipt's photos or PDF with Muse Spark into structured lines: the till's text and a clean name ("SHRD CHEDDAR 16Z" is "Shredded cheddar · 16oz"), counts, weights, codes, an aisle and a budget envelope from the household's own lists, taxes by kind, fees, deposits and totals, all in exact cents. It also measures how far the reading is from the printed total. |
| [`planner/`](planner) | `kadent-ai-planner`. An agent that drafts a shopping list's next trip. Muse Spark reads the household's rows and buying rhythm, store prices and budget through read-only tools, and proposes the trip with a one-line reason for each item. Every item due by the household's rhythm stays on the draft. The person can ask for a change in a sentence. |
| [`lists/`](lists) | `kadent-ai-lists`. One sentence, typed or dictated, becomes changes to the household's shopping lists: "we're out of milk and eggs, and add bananas to the Costco list" puts milk on one list's trip and adds eggs and bananas, each with an aisle. An item a list already has is never added twice. |
| [`ask/`](ask) | `kadent-ai-ask`. Ask Kadent: a question about the household's own spending, receipts and tax ("why was October higher than September?") answered by an agent that reads Budget's figures, receipt lines and tax through read-only tools. The model never writes an amount: it cites the ids the tools gave each number, and the library puts the number in, so every figure on screen has a row behind it. `WeekWriter` uses the same check to write Home's weekly card about last week's spending against the week before. |
| [`notes/`](notes) | `kadent-ai-notes`. One budget note, typed or dictated, becomes one checked line: "dinner at the Keg with Sam, 86 plus 15% tip, yesterday" is $98.90, yesterday, The Keg, in the household's Eating out envelope. An amount, day or envelope that fails the checks is never half used. |

## Built during the event

**Everything in this repo was written during the hackathon.** The first commit is Oct 27, 2026, the
day after the kickoff, and the commit history is the record.

Kadent, the app these libraries plug into, is private and **existed before the event**. Its
shopping lists, receipt scanner with the phone's own text reader, receipt review and filing,
Budget, Home and household sharing were built from September 2026 and are not part of this entry.

What was built from Oct 26 to Nov 4, 2026:

| Feature | In this repo | In Kadent's app |
|---|---|---|
| A key the app never holds | The `ai` Cloud Function, with its token check, model list and quota | The AI features switch under Me, off until the person turns it on |
| Muse Spark reads a receipt | `kadent-ai-receipt` | Asking the model first and falling back to the phone's reader, and "Read by Kadent AI" on the review screen |
| A clean receipt files itself | The check of the reading against the printed total, in `kadent-ai-receipt` | Filing with no taps, and 8 seconds to Undo before anything is written |
| Plan next week | `kadent-ai-planner` and the client's `ToolLoop` | The plan sheet, its reasons, a change asked in a sentence, and Add to trip |
| A sentence onto the right lists | `kadent-ai-lists` | Shop home's "Tell Kadent what you need" and the sheet that shows each list's changes |
| A spoken or typed budget note | `kadent-ai-notes` | The Add transaction sheet reading a long note through the model |
| Ask Kadent | `kadent-ai-ask` | Home's "Ask about your spending" sheet, its tools over Budget's data, and each figure opening where Budget shows it |
| The weekly card | `WeekWriter` in `kadent-ai-ask` | The card at the front of Home's Insight row, and the sheet of its figures |
| Scoring the readers | | A script that scores Muse Spark and the phone's reader against the paper of 45 real receipts. It lives in the private app because the receipts are real. |

**The scores** (45 real Canadian receipts, Oct 29, 2026): Muse Spark read 223 of 226 lines right,
and every total, tax, store and date. The phone's own reader read 210 of 226 lines right.

## Meta models used

Every feature runs on **Muse Spark** through the Meta Model API, called only from the `ai` function,
with effort `low`.

| Library | How it uses Muse Spark |
|---|---|
| `kadent-ai-receipt` | Image and PDF input, answered as JSON matching a schema |
| `kadent-ai-planner` | Tool calling with `tool_choice: any`: four tools, the last of which is the draft itself |
| `kadent-ai-ask` | Tool calling over seven read-only tools; amounts come back as ids the library fills in |
| `kadent-ai-lists`, `kadent-ai-notes` | Free text or dictation answered as JSON matching a schema |
| `WeekWriter` | Writing sentences around figures Kadent supplies, checked before they are shown |

## Privacy rules this code keeps

- The model key stays in the Cloud Function's secret store; no app holds it.
- Meta's `-contributor` models are refused, because Meta may train on what they receive.
- Each call sends only what one feature needs, and nothing is stored after the answer.

## Running it

```bash
(cd functions && npm ci && npm test)      # the function's tests
./gradlew :kadent-ai-client:jvmTest :kadent-ai-receipt:jvmTest :kadent-ai-planner:jvmTest :kadent-ai-lists:jvmTest :kadent-ai-ask:jvmTest :kadent-ai-notes:jvmTest   # the Kotlin tests
```

`AGENTS.md` has the deploy commands and the rules for working in this repo.

## License

Apache-2.0. See [LICENSE](LICENSE).
