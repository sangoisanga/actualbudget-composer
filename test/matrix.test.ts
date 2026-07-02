import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Fastify from 'fastify';
import parserPlugin from '../src/plugins/parser.js';

const testScenarios = [
  {
    fixture: 'momo-transfer.html',
    sender: 'no-reply@momo.vn',
    expected: { amount: 50000, payee: 'Gong Cha Tea' },
  },
];

describe('Parser Matrix', () => {
  let fastify: ReturnType<typeof Fastify>;

  beforeAll(async () => {
    fastify = Fastify();
    await fastify.register(parserPlugin);
    await fastify.ready();
  });

  afterAll(async () => {
    await fastify.close();
  });

  for (const scenario of testScenarios) {
    it(`parses ${scenario.fixture} from ${scenario.sender}`, () => {
      const htmlPath = join(__dirname, 'fixtures', scenario.fixture);
      const htmlBody = readFileSync(htmlPath, 'utf-8');

      const result = fastify.parser.parse(htmlBody, scenario.sender);

      expect(result.amount).toBe(scenario.expected.amount);
      expect(result.payee).toBe(scenario.expected.payee);
    });
  }

  it('throws on unmapped sender', () => {
    expect(() => fastify.parser.parse('<html></html>', 'unknown@bank.com')).toThrow(
      'Unmapped sender: unknown@bank.com',
    );
  });

  it('throws on empty selector match', () => {
    expect(() =>
      fastify.parser.parse('<html><div class="wrong">nope</div></html>', 'no-reply@momo.vn'),
    ).toThrow('DOM selectors returned empty match');
  });
});
