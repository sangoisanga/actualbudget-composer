import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseTransaction } from '../src/parser.js';

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
