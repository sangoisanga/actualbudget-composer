import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import Fastify from 'fastify';
import AutoLoad, { type AutoloadPluginOptions } from '@fastify/autoload';
import type { FastifyPluginAsync, FastifyServerOptions } from 'fastify';

export interface AppOptions extends FastifyServerOptions, Partial<AutoloadPluginOptions> {}

const app: FastifyPluginAsync<AppOptions> = async (fastify, opts) => {
  await fastify.register(AutoLoad, {
    dir: join(import.meta.dirname, 'plugins'),
    options: opts,
  });

  await fastify.register(AutoLoad, {
    dir: join(import.meta.dirname, 'routes'),
    options: opts,
  });
};

export default app;

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT) || 3000;
  const isProduction = process.env.NODE_ENV === 'production';
  const fastify = Fastify({
    logger: isProduction
      ? true
      : { transport: { target: 'pino-pretty', options: { colorize: true } } },
  });
  await fastify.register(app);
  await fastify.listen({ host: '::', port });
}
