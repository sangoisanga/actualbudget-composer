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
const rawRules: unknown = JSON.parse(readFileSync(rulesPath, 'utf-8'));
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
