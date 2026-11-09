# Contributing

This file holds the coding style for every change. CI checks the rules marked **(CI)**.

## Before you push

Run these commands. All must pass.

```sh
npm run lint   # scalafmt check (CI)
npm test       # utest in all modules (CI)
npm run build  # link dist/main.js (CI)
```

After a change to a JS facade or a bump of an npm package, also run one dry poll:

```sh
POLL_DRY_RUN=true npm run poll
```

## Design principle: Least Power

This repo follows Li Haoyi, ["Strategic Scala Style: Principle of Least Power"](https://www.lihaoyi.com/post/StrategicScalaStylePrincipleofLeastPower.html). Use the least powerful tool that solves the problem. Use a more powerful tool only when a simpler one fails, and write down why.

| Topic | Order of preference | In this repo |
| --- | --- | --- |
| Mutability | Immutable by default. Mutability only when the thing is mutable or for speed. Keep its scope small. | `val` everywhere. A `var` only inside one function or one class, for example `PollService`. |
| Published interfaces | Plain methods on an `object`, then plain classes. Avoid inheritance from classes. | Ports are small traits. Logic lives in `object` methods such as `Collector.collect`. |
| Data types | Built-ins, then functions, then simple case classes, then sealed traits | `String`, `List`, `Option`, `case class`, and `enum` |
| Error handling | `Option`, then a simple sealed trait, then exceptions | `Option`, `Either`, the `ParseResult` enum, and exceptions for I/O failures |
| Async return types | Return `T`, then return a `Future`, then take a callback | Plain values where the work is synchronous. `js.Promise` where it is async. |
| Dependency injection | Hardcode it, then a method parameter, then constructor injection | Constructor arguments. No implicit parameters and no DI framework. |
| Libraries | The standard library, then small focused libraries | Mill, upickle, scala-java-time. No effect library. |

On Scala.js, `js.Promise` takes the place of `Future`.

## Architecture

The code has four Mill modules. A module depends only on the modules to its left. `moduleDeps` in `build.mill` enforces this rule.

```mermaid
flowchart LR
  domain --> application --> adapters --> app
```

| Put the code in | When it                                                             |
| --------------- | ------------------------------------------------------------------- |
| `domain/`       | is pure: it has no I/O and needs no other module.                   |
| `application/`  | is a use case that needs I/O through a port.                        |
| `adapters/`     | calls a JS library, Node, the file system, or a remote service.     |
| `app/`          | reads env, wires objects, talks to Telegram, or runs a CLI command. |

- `domain` is synchronous and makes no JS calls. The cheerio facade is the one exception because it is pure.
- `application` has no JS facades. It sees the outside world only through `Ports.scala`.
- Only `app/src/Config.scala` reads `process.env`.
- Only `Main` sets the exit code. No other code calls `process.exit`.
- A folder under `<module>/src/` names a sub-package. `adapters/src/facade/` holds `composer.adapters.facade`.

## Scala style

### Compiler and format

- The compiler treats each warning as an error. Fix the warning in the code. Do not silence it. **(CI)**
- The one silenced warning is "unused explicit parameter" in `adapters/src/facade/`. A facade constructor hands its parameters to JS.
- scalafmt formats all code with `maxColumn = 200`. Run `npm run format`. **(CI)**
- Write a call or a signature on one line. Do not wrap it by hand.

### Types

- Model each category as an `enum`, for example `ParseResult`, `AccountRef`, `ImportVerdict`, and `PollState`. Do not use a string, an int, or a boolean flag.
- Build a value only when it is valid. `EntryDraft.assignTo` makes a `LedgerEntry` only after the account is known.
- Use an `opaque type` for a value with units. `Money` holds minor units.
- Use `Option` for a missing value. Never use `null` or a placeholder.

### Control flow and errors

- Return an expected failure as `ParseResult.Unrecognized` or `Either[String, A]`.
- Throw only for an I/O error or for an invalid config file at start.
- Use `match` on an enum. Use `for` over `Option` or `Either`. Do not nest `if` blocks.

### Names and comments

- Give each function one job. Start its name with a verb.
- Start a boolean name with `is`, `has`, or `can`.
- Do not use single-letter names, except `i` and `j` in a loop.
- A comment is one line. It states a reason that the code cannot show. If a better name can replace it, rename and delete the comment.

### Async and state

- An async function returns `js.Promise[A]`. Write its body as `js.async { ... }` and wait with `js.await(...)`.
- Put `js.await` directly inside `js.async`. It cannot sit inside a lambda. Use `Async.traverse(list)(x => promise)` for a list.
- Catch a failure with `try`/`catch` around `js.await`. A rejected JS promise arrives as `JavaScriptException`. Read its text with `Async.messageOf`.
- Release a resource with `try`/`finally`.
- Use `scala.scalajs.js.timers` for `setTimeout` and `setInterval`. Do not write a facade for them.
- JS runs one callback at a time. A class can keep its state in a private `var` of an `enum`. Check and change the state before the first `js.await`. See `PollService`.
- Keep synchronous work synchronous. `Log`, `BotStore`, and `RuleFiles` return plain values.

### JS facades

- Put each npm or Node facade in `adapters/src/facade/`.
- Declare only the members that the code calls. Copy each signature from the installed `.d.ts` file.
- Type a nullable JS field as `js.UndefOr[String | Null]`. Convert it to `Option` in the adapter at once.
- Use `js.Dynamic` only to build an object literal or to read `process`.

### JSON

- Use upickle. Derive a codec with `derives ReadWriter`.
- Write JSON keys in snake_case. Map each key to a camelCase field with `@key("amount_selector")`.
- When the JSON shape differs from the domain type, read it into a private `...Json` case class first. See `RuleJson` in `BankEmail.scala`.

### Logs

- Write logs only through the `Log` port. `JsonLog` writes one JSON object per line to stdout.
- Use the GCP structured logging format: `severity`, `time`, `message`, then the event fields.
- Set `severity` to `DEBUG`, `INFO`, `WARNING`, or `ERROR`. Write each failure with `log.error`.
- Write `time` as RFC 3339 in `BankZone`, for example `2026-11-06T10:00:00.000+07:00`.
- Write event field keys in snake_case.
- Keep the `Poll complete` summary and the `log.error` lines. Do not log each transaction or each message.
- Do not log a token, a password, or an email body.

### Dates and money

- Dates are `LocalDate` in `BankZone`, a fixed `+07:00` offset. Vietnam has no daylight saving time.
- Do not add `scala-java-time-tzdb`. It adds about 1 MB to the bundle.
- Convert an amount from text only with `Money.parseMajorUnits`.

## Tests

- Pair each code change with a test change.
- Test use cases with the fakes in `application/test/src/Fakes.scala`. Do not call a real service from a test. Make a fake fail with `Fakes.rejected`.
- Name a suite after the object it tests, for example `CollectorSuite`. Write it as `object XSuite extends TestSuite` with `val tests = Tests { ... }`. Keep helpers outside `Tests`.
- Check equality with `actual ==> expected`. Use `assert` for other checks.
- In `application`, extend `AsyncSuite` and end an async test with a `js.Promise`. In other modules, end it with `.toFuture`.
- Assert on the state that the code leaves. Do not assert on cleanup that runs later.
- Read example files with `Fixtures.read` and `Fixtures.example`. Read test resources with `Fixtures.html`.
- Write each email rule from a real email. Every old fixture case must still pass.
- Never commit real personal data. Before you add a fixture to `examples/fixtures/`, replace each name, phone number, card number, account number, and bank reference with a fake value.

utest has these limits:

- It finds no test inside a loop. Write one `test` for each case, and group cases in a nested `test`.
- It reads `.` in a test name as a path separator. Do not put `.` in a name.
- It has no `fail`. Throw `java.lang.AssertionError` in a match arm that must not run.
- `-Wunused` reports `val (a, b) = ...` inside `Tests`. Use a small case class.

## Dependencies

- Pin each version. Use only a release that is at least two weeks old.
- Prefer the standard library. Check the Least Power table before you add a library.
- A Scala library must have a Scala.js artifact for Scala 3. In `build.mill`, write `mvn"org::name::version"`.

## Commits

- Use Conventional Commits: `feat(bot): ...`, `fix(parser): ...`, `chore: ...`.
- Do not commit `.env`, `data/`, `out/`, or `dist/`.

## Writing

- Write docs, comments, and commit messages in plain English. Use short sentences with one idea each.
- Do not use em dashes or semicolons.
