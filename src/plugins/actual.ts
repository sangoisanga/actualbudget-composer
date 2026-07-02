import fs from 'node:fs';
import fp from 'fastify-plugin';
import * as api from '@actual-app/api';

export default fp(async (fastify, _opts) => {
  const dataDir = process.env.ACTUAL_BUDGET_DATA_DIR || './data';

  await fs.promises.mkdir(dataDir, { recursive: true });
  // await api.init({
  //   dataDir,
  //   serverURL: process.env.ACTUAL_BUDGET_URL!,
  //   password: process.env.ACTUAL_BUDGET_PASSWORD!,
  // });
  // await api.downloadBudget(process.env.ACTUAL_BUDGET_SYNC_ID!);

  fastify.decorate('actual', api);
  fastify.decorate('actualAccountId', process.env.ACTUAL_BUDGET_ACCOUNT_ID!);

  fastify.addContentTypeParser('*', { parseAs: 'string' }, (_req, _payload, done) => done(null));
});

declare module 'fastify' {
  export interface FastifyInstance {
    actual: typeof api;
    actualAccountId: string;
  }
}
