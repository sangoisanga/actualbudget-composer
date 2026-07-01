import { afterAll } from 'vitest'
import Fastify from 'fastify'
import appPlugin from '../src/app'

async function build() {
  const fastify = Fastify()
  await fastify.register(appPlugin)
  afterAll(() => fastify.close())
  return fastify
}

export { build }
