# Contributing

These rules apply to every change. CI enforces the rules marked **(CI)**. `AGENTS.md` holds the file map, the code recipes, and the detailed Scala rules.

## Before you push

Run these three commands. All must pass.

```sh
npm run lint   # scalafmt check (CI)
npm test       # utest in all modules (CI)
npm run build  # link dist/main.js (CI)
```

After a change to a JS facade or a bump of an npm package, also run one dry poll:

```sh
POLL_DRY_RUN=true npm run poll
```

## Design principle: Principle of Least Power

This repository follows Li Haoyi, ["Strategic Scala Style: Principle of Least Power"](https://www.lihaoyi.com/post/StrategicScalaStylePrincipleofLeastPower.html). Use the least powerful tool that solves the problem. Use a more powerful tool only when a simpler one fails, and write down why.

| Topic | Order of preference | In this repository |
| --- | --- | --- |
| Mutability | Immutable by default. Mutability only when the thing is mutable or for speed. Keep its scope small. | `val` everywhere. A `var` only inside one function or one class, for example `PollService`. |
| Published interfaces | Plain methods on an `object`, then plain classes. Avoid inheritance from classes. | Ports are small traits. Logic lives in `object` methods such as `Collector.collect`. |
| Data types | Built-ins, then functions, then simple case classes, then sealed traits | `String`, `List`, `Option`, `case class`, and `enum` |
| Error handling | `Option`, then a simple sealed trait, then exceptions | `Option`, `Either`, the `ParseResult` enum, and exceptions for I/O failures |
| Async return types | Return `T`, then return a `Future`, then take a callback | Plain values where the work is synchronous. `js.Promise` where it is async. |
| Dependency injection | Hardcode it, then a method parameter, then constructor injection | Constructor arguments. No implicit parameters and no DI framework. |
| Libraries | The standard library, then small focused libraries | Mill, upickle, scala-java-time. No effect library. |

On Scala.js the platform's own async type is `js.Promise`, so it takes the place of `Future`.

## Architecture

The code has four Mill modules. A module may depend only on the modules before it. The compiler enforces this rule through `moduleDeps` in `build.mill`.

```
domain  <-  application  <-  adapters  <-  app
```

| Put the code in  | When it                                                             |
| ---------------- | ------------------------------------------------------------------- |
| `domain/`        | is pure: it has no I/O and needs no other module.                   |
| `application/`   | is a use case that needs I/O through a port.                        |
| `adapters/`      | calls a JS library, Node, the file system, or a remote service.     |
| `app/`           | reads env, wires objects, talks to Telegram, or runs a CLI command. |

## Code

- The compiler treats each warning as an error. Fix the warning in the code. Do not silence it. **(CI)**
- scalafmt formats all code. Run `npm run format`. **(CI)**
- Model each category as an `enum`. Do not use a string, an int, or a boolean flag.
- Use `Option` for a missing value. Never use `null` or a placeholder.
- Return an expected failure as a value. Throw only for a failure that must stop the work.
- Give each function one job. Start its name with a verb.
- A comment is one line. It states a reason that the code cannot show. If a better name can replace it, rename and delete the comment.

## Tests

- Pair each code change with a test change.
- Test use cases with fakes for the ports. Do not call a real service from a test.
- Write each email rule from a real email. Every old fixture case must still pass.
- Never commit real personal data. Before you add a fixture to `examples/fixtures/`, replace each name, phone number, card number, account number, and bank reference with a fake value.

## Dependencies

- Pin each version. Use only a release that is at least two weeks old.
- Prefer the standard library. Check the Least Power table before you add a library.
- A Scala library must have a Scala.js artifact for Scala 3.

## Commits

- Use Conventional Commits: `feat(bot): ...`, `fix(parser): ...`, `chore: ...`.
- Do not commit `.env`, `data/`, `out/`, or `dist/`.

## Writing

- Write docs, comments, and commit messages in plain English. Use short sentences with one idea each.
- Do not use em dashes or semicolons.
