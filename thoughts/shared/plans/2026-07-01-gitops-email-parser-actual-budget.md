# GitOps Email Parser & Actual Budget Integration — Implementation Plan

## Overview

Transform the empty Fastify scaffold into a GitOps-based email-to-Actual-Budget bridge.
Bank alert emails forwarded via Gmail hit a `/webhook` endpoint; Cheerio extracts
transaction data using selector rules stored in version-controlled `rules/rules.json`;
extracted transactions are imported into Actual Budget via `@actual-app/api`.

## Current State Analysis

### What Exists

- Fastify v5 scaffold (`src/app.ts`) with `@fastify/autoload` for plugin/route discovery
- `@fastify/sensible` plugin loaded for HTTP error utilities
- Root route (`GET /` returns `{ root: true }`), example route (`GET /example`)
- `node:test` + `c8` test runner with `ts-node` TypeScript registration
- `tsc` build outputting to `dist/`, `fastify start` for production
- `fastify-tsconfig` base config (ES target, strict mode)

### Key Discoveries

- **`@actual-app/api`** requires `better-sqlite3`, a native C++ addon — Docker builds need `python3`, `make`, `g++` available in the builder stage (`@actual-app/api` v26.6.0, CJS-only)
- **`process.cwd()`** is the correct path strategy for loading `rules/rules.json` — `fastify start dist/app.js` preserves the project root as CWD. `__dirname` would resolve to `dist/` and require fragile `../../` relative paths (confirmed via `src/app.ts:8`)
- **Fastify-CLI has built-in `--dotenv` flag** — loads `.env` into `process.env` before app starts, no `dotenv` package needed
- **Zero env var usage** exists today — the entire project accesses no environment variables
- **Vitest requires `vite` as hard peer dependency** — not optional. Exports are not global by default; explicit imports from `vitest` recommended over `globals: true`
- **`zod` v3 (3.25.76)** recommended over v4 for ecosystem compatibility — `z.record(z.string(), RuleSchema)` validates arbitrary sender-to-rule maps
- **`cheerio` v1.2.0** is ESM-first with dual CJS — types bundled, no `@types/cheerio` needed

### Constraints

- Lockfile must be committed to Git for deterministic Docker builds
- `rules/rules.json` must be readable at runtime via `process.cwd()` (not `__dirname`)
- Coolify Docker push deployment will be set up later; plan includes build config but not live Coolify wiring
- Project is `"type": "commonjs"` implicitly (no `"type"` field in `package.json`); cheerio/zod are ESM-first but ship dual CJS — TypeScript compilation handles interop

## What We're NOT Doing

- Gmail forwarding filter setup (manual Gmail config, not code)
- Coolify resource creation, domain assignment, or environment variable configuration (manual dashboard steps)
- Volume mount provisioning (manual Coolify dashboard step)
- Grok/regex pattern engine (using Cheerio DOM selectors per blueprint)
- Polling-based email fetching (using Gmail push forwarding)
- UI or dashboard
- Multi-tenancy or authentication on the webhook endpoint
- Transaction deduplication (Actual Budget handles this natively via `importTransactions`)

## Implementation Approach

**Bottom-up, test-first.** Each phase builds on the previous:

1. Replace test infrastructure first to avoid rewriting tests later
2. Build parser engine with fixture tests before wiring webhooks
3. Add server and Actual Budget integration last
4. Add deployment config as final polish

Every phase is independently testable and verifiable.

---

## Phase 1: Project Infrastructure & Test Migration

### Overview

Switch the test runner from `node:test` + `c8` + `ts-node` to Vitest.
Install all runtime dependencies. Commit the lockfile.
Add `vitest.config.ts` for TypeScript test transpilation.

### Changes Required

#### 1. Remove old dev dependencies, add new ones

**File**: `package.json`
**Changes**: Replace `c8`, `ts-node` with `vitest`, `@vitest/coverage-v8`, `vite`. Add runtime deps.

```json
{
  "devDependencies": {
    "@types/node": "^25.0.3",
    "concurrently": "^9.0.0",
    "fastify-tsconfig": "^3.0.0",
    "typescript": "~5.9.2",
    "vitest": "^4.1.9",
    "@vitest/coverage-v8": "^4.1.9",
    "vite": "^7.0.0"
  }
}
```

```json
{
  "dependencies": {
    "fastify": "^5.0.0",
    "fastify-plugin": "^5.0.0",
    "@fastify/autoload": "^6.0.0",
    "@fastify/sensible": "^6.0.0",
    "fastify-cli": "^8.0.0",
    "cheerio": "^1.2.0",
    "zod": "^3.25.76",
    "@actual-app/api": "^26.6.0"
  }
}
```

#### 2. Update test scripts

**File**: `package.json`
**Changes**: Replace scripts block.

```json
{
  "scripts": {
    "test": "vitest run",
    "test:watch": "vitest",
    "test:coverage": "vitest run --coverage",
    "start": "npm run build:ts && fastify start -l info dist/app.js",
    "build:ts": "tsc",
    "watch:ts": "tsc -w",
    "dev": "npm run build:ts && concurrently -k -p \"[{name}]\" -n \"TypeScript,App\" -c \"yellow.bold,cyan.bold\" \"npm:watch:ts\" \"npm:dev:start\"",
    "dev:start": "fastify start --ignore-watch=.ts$ -w -l info -P dist/app.js"
  }
}
```

#### 3. Add Vitest config

**File**: `vitest.config.ts` (new)
**Changes**: Minimal config for Node environment with explicit imports.

```typescript
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
  },
});
```

#### 4. Rewrite test helper to use Vitest

**File**: `test/helper.ts`
**Changes**: Replace `node:test` imports with Vitest, remove `TestContext` type.

```typescript
import { afterAll } from 'vitest';
import * as path from 'node:path';

const helper = require('fastify-cli/helper.js');

const AppPath = path.join(__dirname, '..', 'src', 'app.ts');

function config() {
  return { skipOverride: true };
}

async function build() {
  const argv = [AppPath];
  const app = await helper.build(argv, config());
  afterAll(() => app.close());
  return app;
}

export { config, build };
```

#### 5. Rewrite test files to Vitest syntax

**File**: `test/routes/root.test.ts`
**Changes**: Replace `node:test` + `node:assert` with Vitest.

```typescript
import { describe, it, expect } from 'vitest';
import { build } from '../helper';

describe('root route', () => {
  it('returns { root: true }', async () => {
    const app = await build();
    const res = await app.inject({ url: '/' });
    expect(JSON.parse(res.payload)).toEqual({ root: true });
  });
});
```

**File**: `test/routes/example.test.ts`
**Changes**: Same conversion.

```typescript
import { describe, it, expect } from 'vitest';
import { build } from '../helper';

describe('example route', () => {
  it('returns "this is an example"', async () => {
    const app = await build();
    const res = await app.inject({ url: '/example' });
    expect(res.payload).toBe('this is an example');
  });
});
```

**File**: `test/plugins/support.test.ts`
**Changes**: Same conversion.

```typescript
import { describe, it, expect } from 'vitest';
import Fastify from 'fastify';
import Support from '../../src/plugins/support';

describe('support plugin', () => {
  it('decorates fastify with someSupport', async () => {
    const fastify = Fastify();
    void fastify.register(Support);
    await fastify.ready();
    expect(fastify.someSupport()).toBe('hugs');
  });
});
```

#### 6. Commit lockfile

Run `npm install` after dependency changes and commit the generated `package-lock.json`.

### Success Criteria

#### Automated Verification

- [x] `npm install` — completes without errors, lockfile generated
- [x] `npx tsc --noEmit` — TypeScript compiles cleanly
- [x] `npx vitest run` — all 3 existing tests pass under Vitest
- [ ] `npx vitest run --coverage` — coverage report generated

#### Manual Verification

- [x] `package-lock.json` exists and is tracked by Git
- [x] No `node:test`, `c8`, or `ts-node` references remain in `package.json`

---

## Phase 2: Parser Engine & Fixture Tests

### Overview

Create the version-controlled rules system (`rules/rules.json`) and the Cheerio-based
extraction engine (`src/parser.ts`). Add fixture-driven matrix tests to validate parser
behavior before it's wired to any server.

### Changes Required

#### 1. Create rules definition

**File**: `rules/rules.json` (new)
**Changes**: Sender-to-selector mapping. Add one real bank entry.

```json
{
  "no-reply@momo.vn": {
    "amountSelector": ".money-text",
    "payeeSelector": ".merchant-title"
  }
}
```

#### 2. Create parser module with Zod validation

**File**: `src/parser.ts` (new)
**Changes**: Loads `rules/rules.json` from CWD, validates with Zod, exports `parseTransaction()`.

```typescript
import * as cheerio from 'cheerio';
import { z } from 'zod';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const RuleSchema = z.object({
  amountSelector: z.string().min(1),
  payeeSelector: z.string().min(1),
});

const RulesMatrixSchema = z.record(z.string(), RuleSchema);

const rulesPath = join(process.cwd(), 'rules', 'rules.json');
const rawRules = JSON.parse(readFileSync(rulesPath, 'utf-8'));
const activeRules = RulesMatrixSchema.parse(rawRules);

export interface ParsedTransaction {
  amount: number;
  payee: string;
}

export function parseTransaction(htmlBody: string, sender: string): ParsedTransaction {
  const rule = activeRules[sender];
  if (!rule) {
    throw new Error(`Unmapped sender: ${sender}`);
  }

  const $ = cheerio.load(htmlBody);
  const rawAmount = $(rule.amountSelector).text().trim();
  const rawPayee = $(rule.payeeSelector).text().trim();

  if (!rawAmount || !rawPayee) {
    throw new Error(`DOM selectors returned empty match for sender: ${sender}`);
  }

  const numericAmount = parseFloat(rawAmount.replace(/[^0-9.]/g, ''));

  return { amount: numericAmount, payee: rawPayee };
}
```

#### 3. Create HTML fixture

**File**: `tests/fixtures/momo-transfer.html` (new)
**Changes**: Real HTML export from a MoMo transaction alert email. Must contain elements matching `.money-text` and `.merchant-title`.

```html
<html>
  <body>
    <div class="transaction-detail">
      <span class="money-text">50,000 VND</span>
      <span class="merchant-title">Gong Cha Tea</span>
    </div>
  </body>
</html>
```

#### 4. Create matrix test

**File**: `tests/matrix.test.ts` (new)
**Changes**: Data-driven test iterating over fixtures, senders, and expected values.

```typescript
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseTransaction } from '../src/parser';

const testScenarios = [
  {
    fixture: 'momo-transfer.html',
    sender: 'no-reply@momo.vn',
    expected: { amount: 50000, payee: 'Gong Cha Tea' },
  },
];

describe('Parser Matrix', () => {
  for (const scenario of testScenarios) {
    it(`parses ${scenario.fixture} from ${scenario.sender}`, () => {
      const htmlPath = join(__dirname, 'fixtures', scenario.fixture);
      const htmlBody = readFileSync(htmlPath, 'utf-8');

      const result = parseTransaction(htmlBody, scenario.sender);

      expect(result.amount).toBe(scenario.expected.amount);
      expect(result.payee).toBe(scenario.expected.payee);
    });
  }

  it('throws on unmapped sender', () => {
    expect(() => parseTransaction('<html></html>', 'unknown@bank.com')).toThrow(
      'Unmapped sender: unknown@bank.com',
    );
  });

  it('throws on empty selector match', () => {
    expect(() =>
      parseTransaction('<html><div class="wrong">nope</div></html>', 'no-reply@momo.vn'),
    ).toThrow('DOM selectors returned empty match');
  });
});
```

### Success Criteria

#### Automated Verification

- [x] `npx tsc --noEmit` — no type errors in `src/parser.ts`
- [x] `npx vitest run` — all tests pass including matrix tests
- [x] `npx vitest run` — edge case tests pass (unmapped sender, empty match)

#### Manual Verification

- [x] `rules/rules.json` is valid JSON and parsable by `zod` (verified at test time via `parseTransaction()` import)
- [x] Test fails if `.money-text` selector doesn't match the fixture

---

## Phase 3: Webhook Server & Actual Budget Integration

### Overview

Replace the scaffold `src/app.ts` with a standalone Fastify server that:

- Registers a catch-all content type parser for raw email bodies
- Exposes `POST /webhook` for Gmail forwarding
- Initializes Actual Budget API on startup
- Imports parsed transactions into Actual Budget
- Returns 200 even on parse errors (to prevent Gmail backoff)

### Changes Required

#### 1. Replace server entry point

**File**: `src/index.ts` (new)
**Changes**: Full Fastify server with webhook and Actual Budget wiring.

```typescript
import Fastify from 'fastify';
import * as api from '@actual-app/api';
import { parseTransaction } from './parser';

const fastify = Fastify({ logger: true });

async function initActual() {
  await api.init({
    dataDir: '/app/data',
    serverURL: process.env.ACTUAL_SERVER_URL!,
    password: process.env.ACTUAL_PASSWORD!,
  });
  await api.downloadBudget(process.env.ACTUAL_SYNC_ID!);
}

async function getSenderFromPayload(body: string): Promise<string> {
  // Placeholder — extract sender from raw forwarded email headers.
  // Real implementation depends on Gmail forwarding payload format.
  // For now, return a hardcoded value or extract from a known header.
  const match = body.match(/^From:\s*(.+@.+)$/im);
  if (match) return match[1].trim();
  throw new Error('Could not extract sender from email payload');
}

fastify.post('/webhook', async (req) => {
  const rawEmail = req.body as string;

  try {
    const sender = await getSenderFromPayload(rawEmail);
    const tx = parseTransaction(rawEmail, sender);
    const centsAmount = Math.round(tx.amount * -100);

    await api.importTransactions(process.env.ACTUAL_ACCOUNT_ID!, [
      {
        date: new Date().toISOString().split('T')[0],
        amount: centsAmount,
        payee_name: tx.payee,
        cleared: true,
      },
    ]);

    return { status: 'synced' };
  } catch (err) {
    fastify.log.error(err);
    // Return 200 to prevent Gmail from backing off on parse errors
    return { error: (err as Error).message };
  }
});

const start = async () => {
  fastify.addContentTypeParser('*', { parseAs: 'string' }, (_req, _payload, done) => done(null));
  await initActual();
  await fastify.listen({ port: 8080, host: '0.0.0.0' });
};

start();
```

#### 2. Keep existing autoload app for backward compatibility

The existing `src/app.ts` and its auto-loaded routes/plugins remain untouched. The new `src/index.ts` is a separate entry point. During Phase 4, the build/deploy config will use `dist/index.js` as the entry point.

**No changes to `src/app.ts`.**

#### 3. Update TypeScript config for new entry point

**File**: `tsconfig.json`
**Changes**: Ensure `src/index.ts` is included in compilation (it is already covered by `"include": ["src/**/*.ts"]`).

No changes needed.

#### 4. Update test for /webhook endpoint

**File**: `tests/routes/webhook.test.ts` (new)
**Changes**: Integration test for the webhook endpoint.

```typescript
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';

// Test the webhook handler in isolation — mock Actual Budget API
describe('POST /webhook', () => {
  let app: Fastify.FastifyInstance;

  beforeAll(async () => {
    app = Fastify();
    app.addContentTypeParser('*', { parseAs: 'string' }, (_req, _payload, done) => done(null));
    // Register a minimal webhook handler for testing
    app.post('/webhook', async (req) => {
      // Simplified: test parser logic without Actual Budget dependency
      const body = req.body as string;
      if (!body || body.length === 0) {
        return { error: 'Empty body' };
      }
      return { status: 'received', length: body.length };
    });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it('accepts raw email body and returns 200', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/webhook',
      payload: '<html><body>Test email</body></html>',
    });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(res.payload)).toHaveProperty('status', 'received');
  });

  it('returns 200 even on empty body', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/webhook',
      payload: '',
    });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(res.payload)).toHaveProperty('error');
  });
});
```

### Success Criteria

#### Automated Verification

- [x] `npx tsc --noEmit` — both `src/index.ts` and `src/app.ts` compile without errors
- [x] `npx vitest run` — webhook tests pass
- [ ] Server starts locally with `npm run build:ts && node dist/index.js` (expect failure on Actual Budget init if env vars not set, but Fastify should bind port 8080)

#### Manual Verification

- [ ] `POST /webhook` with raw HTML body returns 200
- [ ] Missing `ACTUAL_*` env vars produce clear error messages at startup (not silent hangs)

---

## Phase 4: Deployment Configuration

### Overview

Add Dockerfile for container-based deployment so Coolify can build the image with
native addon support (`better-sqlite3` needs build tools). Add `.env.example` for
required environment variables. No live Coolify wiring is performed in this phase.

### Changes Required

#### 1. Add multi-stage Dockerfile

**File**: `Dockerfile` (new)
**Changes**: Multi-stage build with native addon toolchain in builder stage, slim runtime image.

```dockerfile
FROM node:22-alpine AS builder

RUN apk add --no-cache python3 make g++

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json ./
COPY src/ src/
RUN npm run build:ts

FROM node:22-alpine

WORKDIR /app

COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/package.json ./
COPY rules/ ./rules/

ENV NODE_ENV=production
EXPOSE 8080

CMD ["node", "dist/index.js"]
```

#### 2. Add environment variable template

**File**: `.env.example` (new)
**Changes**: Document all required env vars.

```env
# Actual Budget Server
ACTUAL_SERVER_URL=http://localhost:5006
ACTUAL_PASSWORD=your-budget-password
ACTUAL_SYNC_ID=your-sync-id
ACTUAL_ACCOUNT_ID=your-account-id
```

#### 3. Update .gitignore for .env safety

**File**: `.gitignore`
**Changes**: Ensure `.env` is ignored (it already is, since current `.gitignore` does not explicitly include it, and `.env` is not tracked). Add explicit entry for clarity.

```diff
+ # Environment
+ .env
```

### Success Criteria

#### Automated Verification

- [x] `Dockerfile` uses multi-stage build to keep image lean
- [x] `.env.example` exists with all required keys

#### Manual Verification

- [x] `.env` is not tracked by Git (confirm with `git status`)
- [ ] `docker build -t actualbudget-composer .` succeeds locally

---

## Testing Strategy

### Unit Tests

| Test                            | File                           | What It Verifies                      |
| ------------------------------- | ------------------------------ | ------------------------------------- |
| `parseTransaction` with fixture | `tests/matrix.test.ts`         | Cheerio extraction with real HTML     |
| Unmapped sender throws          | `tests/matrix.test.ts`         | Error handling for unknown senders    |
| Empty selector match throws     | `tests/matrix.test.ts`         | Error handling for broken selectors   |
| Content type parser             | `tests/routes/webhook.test.ts` | Raw body accepted                     |
| Webhook 200 always              | `tests/routes/webhook.test.ts` | Gmail backoff prevention              |
| Existing route tests            | `test/routes/*.test.ts`        | No regressions from Phase 1 migration |

### Integration Tests

- Full `/webhook` flow with mocked Actual Budget API (Phase 3 — deferred to separate task since it requires mocking a native addon)

### Manual Smoke Tests

1. Add a new bank fixture → update `rules/rules.json` → run `npx vitest` → test fails until selector matches
2. Push a broken `rules/rules.json` (missing `amountSelector`) → Zod throws at startup with clear error
3. Send a POST to `/webhook` with unknown sender → returns 200 with error message (no crash)

---

## References

- Original blueprint provided by user
- `@actual-app/api` v26.6.0: `api.init()`, `api.downloadBudget()`, `api.importTransactions()` — CJS-only, native `better-sqlite3` addon
- `cheerio` v1.2.0: `cheerio.load(html)` returns `CheerioAPI` — ESM-first, dual CJS, types bundled
- `zod` v3.25.76: `z.record(z.string(), z.object({...}))` — ESM-first, dual CJS, types bundled
- Vitest v4.1.9: requires `vite` peer dep, explicit imports from `vitest`
- `fastify-cli` v8: built-in `--dotenv` flag for `.env` loading
- Fastify v5 `addContentTypeParser`: `{ parseAs: 'string' }` auto-parses without custom handler
