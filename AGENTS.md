# AGENTS.md

Read `CONTRIBUTING.md` first. It holds the design principle and the general rules. This file holds the details an agent needs to change the code.

## Project

This project imports bank transactions into Actual Budget. It reads notification emails from Gmail and parses them with a rules matrix. It supports two Vietnamese senders:

- Cake by VPBank (`no-reply@cake.vn`).
- VPBank Online (`vpbankonline@vpb.com.vn`).

The code is Scala 3 on Scala.js, built with Mill. It links to one ES module, `dist/main.js`, with the subcommands `bot`, `poll`, `auth-gmail`, `inspect-mail`, `check-rules`, and `healthcheck`.

The main entry is the **Telegram bot** (`node dist/main.js bot`). It imports on a timer and on `/poll`, and it uses Telegram long polling. It opens no port. The other subcommands are local tools for tests and debugging, except `healthcheck`, which a container health check runs.

Personal rules and fixtures do not belong in this repo. Keep them in a private folder and point `RULES_DIR` at it. `examples/` holds anonymized examples only.

## Structure

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
examples/                            a RULES_DIR: rules.json (sender to selectors), payees.json, fixtures/ (anonymized emails)
```

| Path        | Content                                                       |
| ----------- | ------------------------------------------------------------- |
| `bin/`      | The npm `bin` entry. It imports `dist/main.js`.               |
| `thoughts/` | Plans and research notes. Git ignores this folder.            |
| `.github/`  | CI (lint, test, build) and the npm release on a `v*` tag.     |

## Flow

Each poll does these steps:

1. `LedgerSession.open`: `api.init`, then `api.downloadBudget`.
2. `Collector.collect`: list Gmail messages from the senders in `rules.json`, inside the lookback window.
3. Parse each email with `BankEmail.parse`. Map the payee and category with `PayeeMap`.
4. Route each entry to the account from the card number or phone number in the email.
5. `Importer.importAll`: `api.importTransactions`, one batch for each account.
6. `LedgerSession.close`: `api.shutdown()`, which syncs.

The bot holds back transactions with no known account. It shows inline account buttons and imports one group for each press. The budget stays open until the last pick, `/cancel`, or a 15-minute timeout.

`/auth` rotates the Gmail refresh token without a restart. The bot stores the token in `bot.sqlite` inside `ACTUAL_BUDGET_DATA_DIR`. `/cron` saves the poll interval in the same file. The `poll` subcommand reads `GMAIL_REFRESH_TOKEN` only.

## Stack

- Scala 3.9 LTS, Scala.js 1.22 with ES module output, Node 26 or later. Mill 1.3.1 through the `./mill` launcher.
- `-deprecation -feature -Wunused:all -Werror` in every module.
- Direct style: `js.Promise` with `js.async`/`js.await`. No effect library.
- upickle reads the rule files. `Config.scala` checks env with plain code.
- utest for tests. scalafmt with `.scalafmt.conf` (`maxColumn = 200`).
- npm packages: `cheerio` parses email HTML, `grammy` runs the bot, `@googleapis/gmail` and `google-auth-library` read Gmail, `@actual-app/api` writes the budget.

## Commands

```sh
npm run build    # Link with Mill to dist/main.js. All other scripts run dist/.
npm test         # utest in all modules.
npm run lint     # scalafmt check.
npm run format   # scalafmt.
npm run bot      # Start the bot. Needs .env.
npm run poll     # Local test: one poll. Needs .env.
npm run auth:gmail  # Print a new GMAIL_REFRESH_TOKEN.
npm run inspect:mail -- <id>...  # Save failed emails as fixtures and trace the rules.
npm run check-rules -- examples [--update]  # Check each fixture against its expected file. --update rewrites them.
./mill domain.test  # Test one module.
```

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

An ignore rule has the shape `{ "ignore": true, "match_selector": "..." }`. It marks a template that moves no money. The poller counts it as skipped. Put ignore rules first.

`payees.json` is an ordered list of `{ match, payee, note?, category? }`. The first match wins. `imported_payee` keeps the raw string for Actual's own payee rules.

- Write each selector from a real email in `fixtures/`. Do not write a selector from a screenshot.
- Each `fixtures/<name>.html` needs a `fixtures/<name>.expected.json`. Each rule needs a fixture that it matches first. `check-rules` fails otherwise.

## Key patterns

- **Idempotency**: `imported_id` is the bank reference, or the Gmail message ID when the email has none. A second import updates the row and does not add a copy.
- **Amounts**: Cake writes `17.000 đ`. VPBank writes `17,000.00 VND`. `Money.parseMajorUnits` finds the separators from the digits. A negative amount is money out.
- **Poll state**: `PollService` moves between `Idle`, `Polling`, and `AwaitingPicks`. A second poll gets `Busy` or `PicksPending`.
- **Failures**: each email that no rule matches becomes a `FailedEmail`. The bot shows it as a link to the Gmail message. The report ends with one copyable `/fix-mail <id>...` line.
- **Fix a failed email**: run `npm run inspect:mail -- <id>...`. It saves each email and its expected file in `fixtures/` of `RULES_DIR`. Write the rule, run `check-rules --update`, and review the diff.

## Code rules

### Modules

- `domain` is synchronous and has no JS calls. The one exception is the cheerio facade, which is pure.
- `application` has no JS facades. It sees the outside world only through `Ports.scala`.
- Only `app/src/Config.scala` reads `process.env`.
- Only `Main` decides the exit code. No other code calls `process.exit`.
- A folder under `<module>/src/` names the package below the module's base package. `adapters/src/facade/` holds `composer.adapters.facade`.
- The one silenced warning is "unused explicit parameter" in `adapters/src/facade/`. A JS facade constructor hands its parameters to JS.

### Types and control flow

- Category enums: `ParseResult`, `AccountRef`, `ImportVerdict`, `PollState`.
- Build a value only when it is valid. `EntryDraft.assignTo` makes a `LedgerEntry` only after the account is known.
- Use an `opaque type` for a value with units. `Money` holds minor units.
- Return an expected failure as `ParseResult.Unrecognized` or `Either[String, A]`. Throw for an I/O error or an invalid config file at start.
- Use `match` on an enum. Use `for` over `Option` or `Either`. Do not nest `if` blocks.
- Start a boolean name with `is`, `has`, or `can`. Do not use single-letter names, except `i` and `j` in a loop.
- Write a call or a signature on one line. Do not wrap it by hand.

### Async and state

- An async function returns `js.Promise[A]`. Write its body as `js.async { ... }` and wait with `js.await(...)`.
- `js.await` must sit directly inside `js.async`. It cannot sit inside a lambda. Use `Async.traverse(list)(x => promise)` for a list.
- Catch a failure with `try`/`catch` around `js.await`. A rejected JS promise arrives as `JavaScriptException`. Read its text with `Async.messageOf`, which removes the `"Error: "` prefix.
- Release a resource with `try`/`finally`.
- Use `scala.scalajs.js.timers` for `setTimeout` and `setInterval`. Do not write a facade for them.
- JS runs one callback at a time. A class may keep its state in a private `var` of an `enum`. Check and change the state before the first `js.await`. See `PollService`.
- Synchronous work stays synchronous. `Log`, `BotStore`, and `RuleFiles` return plain values.

### JS facades

- Put each npm or Node facade in `adapters/src/facade/`.
- Declare only the members that the code calls. Copy each signature from the installed `.d.ts` file.
- Type a nullable JS field as `js.UndefOr[String | Null]`. Convert it to `Option` in the adapter, at once.
- Use `js.Dynamic` only to build an object literal or to read `process`.
- Scala.js passes no arguments to `main`. `Main` reads them from `process.argv`.

### JSON

- Use upickle. Derive a codec with `derives ReadWriter`.
- Write JSON keys in snake_case. Map each key to a camelCase Scala field with `@key("amount_selector")`.
- When the JSON shape differs from the domain type, read it into a private `...Json` case class first. See `RuleJson` in `BankEmail.scala`.

### Logs

- Write logs only through the `Log` port. `JsonLog` writes one JSON object per line to stdout.
- The format is GCP structured logging: `severity`, `time`, `message`, then the event fields.
- `severity` is `DEBUG`, `INFO`, `WARNING`, or `ERROR`. Log platforms read it to color and filter each line.
- Write each failure with `log.error`. Log platforms trust the declared severity over words such as "failed".
- Do not log raw operation data. Keep the aggregate `Poll complete` summary and the `log.error` failure lines; no per-transaction or per-message payload lines.
- `time` is RFC 3339 in `BankZone`, for example `2026-11-06T10:00:00.000+07:00`.
- Write event field keys in snake_case.
- Do not log a token, a password, or an email body.

### Dates and money

- Dates are `LocalDate` in `BankZone`, a fixed `+07:00` offset. Vietnam has no daylight saving time.
- Do not add `scala-java-time-tzdb`. It adds about 1 MB to the bundle.
- Convert an amount from text only with `Money.parseMajorUnits`.

### Tests

- Name a suite after the object it tests, for example `CollectorSuite`. Write it as `object XSuite extends TestSuite` with `val tests = Tests { ... }`. Keep helpers outside `Tests`.
- Check equality with `actual ==> expected`. Use `assert` for other checks.
- Use the fakes in `application/test/src/Fakes.scala`. Make a fake fail with `Fakes.rejected`.
- In `application`, extend `AsyncSuite` and end an async test with a `js.Promise`. In other modules, end it with `.toFuture`.
- Assert on the state the code leaves, not on cleanup that runs later.
- Read example files with `Fixtures.read` and `Fixtures.example`, and test resources with `Fixtures.html`. `build.mill` sets `COMPOSER_ROOT` and `MILL_TEST_RESOURCE_DIR`.
- utest finds no test inside a loop. Write one `test` for each case. Group cases in a nested `test`.
- Do not put `.` in a test name. utest reads `.` as a path separator.
- utest has no `fail`. Throw `java.lang.AssertionError` in a match arm that must not run.
- Do not write `val (a, b) = ...` inside `Tests`. `-Wunused` reports the variables after the utest macro. Use a small case class.

### Dependencies

- In `build.mill`, write `mvn"org::name::version"`. The first `::` adds `_3`. The second `::` adds `_sjs1`.
- The Scala libraries today are upickle and scala-java-time.

### Release

- Bump `version` in `package.json`, then push a `v<version>` tag.
- The tag runs `.github/workflows/release.yml`. It publishes to npm with trusted publishing, so the repo holds no npm token.
- `npm publish` runs `prepack`, which builds `dist/main.js`. The package holds only `bin/` and `dist/main.js`.

## How to

### Add a bot command

1. Add a method `def name(context: BotContext): js.Promise[Unit]` to class `Commands` in `app/src/telegram/Commands.scala`.
2. Register it in `Commands.register` with `bot.command("name", guard(commands.name))`. The `guard` catches and logs each error.
3. Add it to `Commands.all`. This list feeds the Telegram command menu and the reply to an unknown command.
4. Put the message text in `Format.scala`. Test it in `FormatSuite`.
5. Add the command to the table in `README.md`.

### Read a new env var

1. Add a field to the config case class in `app/src/Config.scala`.
2. Read the value with `env.required`, `env.optional`, `env.positiveInt`, `env.isTrue`, or `env.long`. `env.checked` throws once with every problem.
3. Pass the value as a constructor argument from `BotApp` or `cli/Clis.scala`.
4. Add the variable to `.env.example` and to the table in `README.md`.

### Add a port and an adapter

1. Add a small trait to `application/src/Ports.scala`. Use domain types in its signatures.
2. Write the implementation in `adapters/src/<area>/`.
3. Add a fake to `application/test/src/Fakes.scala`.
4. Pass the implementation as a constructor argument in `BotApp.run` or `cli/Clis.scala`.

### Call a new JS function

1. Find the signature in the installed `.d.ts` file under `node_modules/<package>/`.
2. Add only that member to the facade in `adapters/src/facade/`. Each member body is `= js.native`.

   | JS                               | Scala facade                                                         |
   | -------------------------------- | -------------------------------------------------------------------- |
   | `import * as fs from "node:fs"`  | `@JSImport("node:fs", JSImport.Namespace) object NodeFs`             |
   | `import { load } from "cheerio"` | `@JSImport("cheerio", "load") object loadHtml` with `def apply(...)` |
   | `new Bot(token)`                 | `@JSImport("grammy", "Bot") class Bot(token: String)`                |
   | the global `URL`                 | `@JSGlobal("URL") class URL(input: String)`                          |
   | an object that JS returns        | `@js.native trait ImportResponse extends js.Object`                  |
   | `field?: string \| null`         | `val field: js.UndefOr[String \| Null]`                              |
   | a field named `type` or `match`  | ``val `type`: String``                                               |

3. Call the facade only from an adapter. Convert each `js.UndefOr` to `Option` there.
4. Run `POLL_DRY_RUN=true npm run poll`.

## Gotchas

- **A write to the budget is a push.** The sync engine sends each change to the server about one second later. To keep data off the server, do not write. Use `POLL_DRY_RUN=true`.
- Do not run the `poll` subcommand and the bot against the same budget at the same time.
- The Gmail scope is `gmail.readonly`. Do not change the mailbox.
- The compiler does not check the facades against the `.d.ts` files. After each bump of `@actual-app/api`, grammy, or the Google packages, run a dry poll and test the bot.
- Gmail `invalid_grant` means the refresh token is not valid. Run `npm run auth:gmail`, or send `/auth` to the bot.
- Do not edit `.env` or commit `data/`. Only the user edits `.env`. Use `.env.example` as the reference.
