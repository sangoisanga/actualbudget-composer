import type { FastifyPluginCallback } from 'fastify';

const example: FastifyPluginCallback = (fastify, _opts) => {
  fastify.get('/', (_request, _reply) => {
    return 'this is an example';
  });
};

export default example;
