import type { FastifyPluginCallback } from 'fastify'

const root: FastifyPluginCallback = (fastify, _opts) => {
  fastify.get('/', (_request, _reply) => {
    return { root: true }
  })
}

export default root
