# AGENTS.md

Rules for coding agents in this repo.

- Read `CONTRIBUTING.md` before you change code. It holds the coding style, and every rule in it applies to you.
- This file adds the hard limits, the project map, and step-by-step recipes.

## Hard limits

Each limit applies to every task. Stop and ask the user if a task needs you to break one.

1. Do not edit `.env`. Only the user edits it. Use `.env.example` as the reference.
2. Do not write to a real budget. Each write syncs to the server about one second later. Use `POLL_DRY_RUN=true`.
3. Do not run the `poll` subcommand and the bot against the same budget at the same time.
4. Do not change the Gmail scope. It is `gmail.readonly`, and the tool never changes the mailbox.
5. Do not add personal rules, real emails, or account IDs to this repo. They live in a private folder that `RULES_DIR` points to. `examples/` holds anonymized data only.
6. Do not log a token, a password, an email body, or one line for each transaction.
7. Do not guess a JS signature. Copy it from the installed `.d.ts` file under `node_modules/<package>/`.
8. Do not silence a compiler warning. Fix the code.
9. Do not commit `.env`, `data/`, `out/`, or `dist/`.

## Done checklist

Run each step before you report a task as done.

1. `npm run format`
2. `npm run lint`
3. `npm test`
4. `npm run build`
5. After a facade change or an npm bump: `POLL_DRY_RUN=true npm run poll`
6. Confirm that each code change has a test change.

## Project

This project imports bank transactions into Actual Budget. It reads notification emails from Gmail and parses them with a rules matrix.

| Sender         | Address                   | Amount format   |
| -------------- | ------------------------- | --------------- |
| Cake by VPBank | `no-reply@cake.vn`        | `17.000 đ`      |
| VPBank Online  | `vpbankonline@vpb.com.vn` | `17,000.00 VND` |

The code is Scala 3 on Scala.js, built with Mill. It links to one ES module, `dist/main.js`.

| Subcommand     | Use                                                       |
| -------------- | --------------------------------------------------------- |
| `bot`          | Main entry. Telegram long polling. Opens no port.         |
| `poll`         | Local test: one poll. Reads `GMAIL_REFRESH_TOKEN` only.   |
| `auth-gmail`   | Print a new Gmail refresh token.                          |
| `inspect-mail` | Save failed emails as fixtures and trace the rules.       |
| `check-rules`  | Check each fixture against its expected file.             |
| `healthcheck`  | Container health check. Fails after 5 minutes with no Telegram poll. |

## Stack

- Scala 3.9 LTS, Scala.js 1.22 with ES module output, Node 26 or later.
- Mill 1.3.1 through the `./mill` launcher.
- `-deprecation -feature -Wunused:all -Werror` in every module.
- Direct style with `js.Promise`, `js.async`, and `js.await`. No effect library.
- Scala libraries: upickle and scala-java-time. Tests: utest.
- npm packages: `cheerio` parses email HTML, `grammy` runs the bot, `@googleapis/gmail` and `google-auth-library` read Gmail, `@actual-app/api` writes the budget.

## File map

```
domain/src/                          composer.domain: pure types and rules, no I/O
  Money.scala                        Money (minor units), amount parsing
  BankEmail.scala                    EmailRule, ParseResult, AccountRef, parse, trace, rule reader
  PayeeMap.scala                     payee mapping and its decoder
  RuleCheck.scala                    RuleConfig, fixture snapshots, check-rules logic
  Report.scala                       EntryDraft, LedgerEntry, ImportVerdict, PollReport
  PollInterval.scala                 parses and shows the poll interval (6h, 1d)
  html/Cheerio.scala                 the only JS facade in domain
application/src/                     composer.application: use cases over ports, no JS facades
  Ports.scala                        Mailbox, Ledger, LedgerSession, Log
  Async.scala                        traverse over promises, JS error messages
  Collector.scala                    list, parse, map, and route each email
  Importer.scala                     one import batch for each account
  PollService.scala                  poll and pick state machine (private var of PollState)
adapters/src/                        composer.adapters: port implementations
  facade/                            hand-written @js.native facades for npm and Node APIs
  actual/  gmail/  sqlite/  log/     Ledger, Mailbox, BotStore (token and poll interval), JSON log
  telegram/Heartbeat.scala           getUpdates heartbeat and health check
  RuleFiles.scala                    loads rules.json and payees.json from RULES_DIR
app/src/                             composer: entry points
  Main.scala                         reads process.argv, runs the subcommand, sets the exit code
  Config.scala                       the only code that reads env
  telegram/                          BotApp (wiring), Commands (handlers), Format (message text), ScheduledPoll (timer)
  cli/Clis.scala                     poll, inspect-mail, check-rules, auth-gmail
<module>/test/src/                   utest tests for each module
<module>/test/resources/             test data
examples/                            a RULES_DIR: rules.json, payees.json, fixtures/ (anonymized emails)
bin/                                 npm bin entry, imports dist/main.js
.github/                             CI (lint, test, build) and the npm release on a v* tag
thoughts/                            plans and research notes, ignored by git
```

## Commands

```sh
npm run build    # Link with Mill to dist/main.js. All other scripts run dist/.
npm test         # utest in all modules, and check-rules over examples/.
npm run lint     # scalafmt check.
npm run format   # scalafmt.
npm run bot      # Start the bot. Needs .env.
npm run poll     # One poll. Needs .env.
npm run auth:gmail  # Print a new GMAIL_REFRESH_TOKEN.
npm run inspect:mail -- <id>...  # Save failed emails as fixtures and trace the rules.
npm run check-rules -- examples [--update]  # Check fixtures. --update rewrites the expected files.
./mill domain.test  # Test one module.
```

## Poll flow

1. `LedgerSession.open`: `api.init`, then `api.downloadBudget`.
2. `Collector.collect`: list Gmail messages from the senders in `rules.json`, inside the lookback window.
3. Parse each email with `BankEmail.parse`. Map the payee and category with `PayeeMap`.
4. Route each entry to the account from the card number or phone number in the email.
5. `Importer.importAll`: `api.importTransactions`, one batch for each account.
6. `LedgerSession.close`: `api.shutdown()`, which syncs.

The bot holds back entries with no known account. It shows inline account buttons and imports one group for each press. The budget stays open until the last pick, `/cancel`, or a 15-minute timeout.

`PollService` moves between `Idle`, `Polling`, and `AwaitingPicks`. A second poll gets `Busy` or `PicksPending`.

`/auth` and `/cron` save the Gmail refresh token and the poll interval in `bot.sqlite` inside `ACTUAL_BUDGET_DATA_DIR`.

## Domain facts

- **Idempotency**: `imported_id` is the bank reference, or the Gmail message ID when the email has none. A second import updates the row and adds no copy.
- **Amounts**: `Money.parseMajorUnits` finds the separators from the digits. A negative amount is money out.
- **Failures**: each email that no rule matches becomes a `FailedEmail`. The bot shows it as a Gmail link. The report ends with one `/fix-mail <id>...` line.
- **Gmail `invalid_grant`**: the refresh token is not valid. The user runs `npm run auth:gmail` or sends `/auth` to the bot.

## Rule files

`rules.json` maps each sender to an ordered list of rules. The first rule that finds an amount and a payee wins.

| Field              | Required | Purpose                                                                   |
| ------------------ | -------- | ------------------------------------------------------------------------- |
| `amount_selector`  | Yes      | CSS selector for the amount.                                              |
| `payee_selector`   | Yes      | CSS selector for the payee.                                               |
| `payee_default`    | No       | Payee to use when `payee_selector` finds no text.                         |
| `date_selector`    | No       | CSS selector for the date.                                                |
| `id_selector`      | No       | CSS selector for the bank reference. It becomes `imported_id`.            |
| `notes_selector`   | No       | CSS selector for the notes.                                               |
| `amount_sign`      | No       | `expense` (default) makes the amount negative. `explicit` keeps its sign. |
| `account_selector` | No       | CSS selector for the card number or phone number.                         |
| `account_map`      | No       | Maps the account value to an Actual account ID.                           |
| `account_default`  | No       | Account ID when the map has no match, or when there is no selector.       |
| `account_choices`  | No       | Account IDs for the bot buttons when the account is not known.            |

- An ignore rule has the shape `{ "ignore": true, "match_selector": "..." }`. It marks a template that moves no money. Put ignore rules first.
- `payees.json` is an ordered list of `{ match, payee, note?, category? }`. The first match wins. `imported_payee` keeps the raw string.
- Write each selector from a real email in `fixtures/`. Do not write a selector from a screenshot.
- Each `fixtures/<name>.html` needs a `fixtures/<name>.expected.json`. Each rule needs a fixture that it matches first. `check-rules` fails otherwise.

## Recipes

### Add a bot command

1. Add `def name(context: BotContext): js.Promise[Unit]` to class `Commands` in `app/src/telegram/Commands.scala`.
2. Register it in `Commands.register` with `bot.command("name", guard(commands.name))`. `guard` catches and logs each error.
3. Add it to `Commands.all`. This list feeds the Telegram menu and the reply to an unknown command.
4. Put the message text in `Format.scala`. Test it in `FormatSuite`.
5. Add the command to the bot table in `README.md`.

### Read a new env var

1. Add a field to the config case class in `app/src/Config.scala`.
2. Read it with `env.required`, `env.optional`, `env.positiveInt`, `env.interval`, `env.isTrue`, or `env.long`. `env.checked` throws once with every problem.
3. Pass the value as a constructor argument from `BotApp` or `cli/Clis.scala`.
4. Add the variable to `.env.example` with a one-line comment.

### Add a port and an adapter

1. Add a small trait to `application/src/Ports.scala`. Use domain types in its signatures.
2. Write the implementation in `adapters/src/<area>/`.
3. Add a fake to `application/test/src/Fakes.scala`.
4. Pass the implementation as a constructor argument in `BotApp.run` or `cli/Clis.scala`.

### Call a new JS function

1. Find the signature in the installed `.d.ts` file under `node_modules/<package>/`.
2. Add only that member to the facade in `adapters/src/facade/`. Each member body is `= js.native`.
3. Call the facade only from an adapter. Convert each `js.UndefOr` to `Option` there.
4. Run `POLL_DRY_RUN=true npm run poll`. The compiler does not check facades against the `.d.ts` files.

| JS                               | Scala facade                                                         |
| -------------------------------- | -------------------------------------------------------------------- |
| `import * as fs from "node:fs"`  | `@JSImport("node:fs", JSImport.Namespace) object NodeFs`             |
| `import { load } from "cheerio"` | `@JSImport("cheerio", "load") object loadHtml` with `def apply(...)` |
| `new Bot(token)`                 | `@JSImport("grammy", "Bot") class Bot(token: String)`                |
| the global `URL`                 | `@JSGlobal("URL") class URL(input: String)`                          |
| an object that JS returns        | `@js.native trait ImportResponse extends js.Object`                  |
| `field?: string \| null`         | `val field: js.UndefOr[String \| Null]`                              |
| a field named `type` or `match`  | ``val `type`: String``                                               |

Scala.js passes no arguments to `main`. `Main` reads them from `process.argv`.

### Fix a failed email

1. Run `npm run inspect:mail -- <id>...`. It saves each email and its expected file in `fixtures/` of `RULES_DIR`.
2. Write or change the rule in `rules.json`.
3. Run `npm run check-rules -- <dir> --update`. Review the diff of the expected files.

### Add a Scala library

1. Confirm that it has a Scala.js artifact for Scala 3 and a release at least two weeks old.
2. Add it in `build.mill` as `mvn"org::name::version"`. The first `::` adds `_3`. The second adds `_sjs1`.

### Cut a release

1. Bump `version` in `package.json`.
2. Push a `v<version>` tag. `.github/workflows/release.yml` publishes to npm with trusted publishing.
3. `npm publish` runs `prepack`, which builds `dist/main.js`. The package holds only `bin/` and `dist/main.js`.
