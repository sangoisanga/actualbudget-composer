import { describe, it, expect } from 'vitest'
import Fastify from 'fastify'
import Support from '../../src/plugins/support'

describe('support plugin', () => {
  it('decorates fastify with someSupport', async () => {
    const fastify = Fastify()
    void fastify.register(Support)
    await fastify.ready()
    expect(fastify.someSupport()).toBe('hugs')
  })
})
