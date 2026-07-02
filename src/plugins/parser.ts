import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import * as cheerio from 'cheerio';
import { z } from 'zod';
import fp from 'fastify-plugin';

const RuleSchema = z.object({
  amountSelector: z.string().min(1),
  payeeSelector: z.string().min(1),
});

const RulesMatrixSchema = z.record(z.string(), RuleSchema);

type RuleMatrix = z.infer<typeof RulesMatrixSchema>;

export interface ParsedTransaction {
  amount: number;
  payee: string;
}

export default fp(async (fastify, _opts) => {
  const rulesPath = join(process.cwd(), 'rules', 'rules.json');
  const rawRules: unknown = JSON.parse(await readFile(rulesPath, 'utf-8'));
  const rules: RuleMatrix = RulesMatrixSchema.parse(rawRules);

  fastify.decorate('parser', {
    parse(htmlBody: string, sender: string): ParsedTransaction {
      const rule = rules[sender];
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
    },
  });
});

declare module 'fastify' {
  export interface FastifyInstance {
    parser: {
      parse(htmlBody: string, sender: string): ParsedTransaction;
    };
  }
}
