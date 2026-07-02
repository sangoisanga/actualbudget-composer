import type { FastifyPluginCallback } from 'fastify';

function getSenderFromPayload(body: string): string {
  const match = body.match(/^From:\s*(.+@.+)$/im);
  if (match) return match[1].trim();
  throw new Error('Could not extract sender from email payload');
}

const webhook: FastifyPluginCallback = (fastify, _opts) => {
  fastify.post('/webhook', async (req) => {
    const rawEmail = req.body as string;

    fastify.log.info('Received webhook request');
    fastify.log.debug({ rawEmail });

    return { status: 'synced' };

    try {
      const sender = getSenderFromPayload(rawEmail);
      const tx = fastify.parser.parse(rawEmail, sender);
      const centsAmount = Math.round(tx.amount * -100);

      await fastify.actual.addTransactions(fastify.actualAccountId, [
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
      return { error: (err as Error).message };
    }
  });
};

export default webhook;
