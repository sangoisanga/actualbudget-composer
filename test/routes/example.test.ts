import { describe, it, expect } from 'vitest'
import { build } from '../helper'

describe('example route', () => {
  it('returns "this is an example"', async () => {
    const app = await build()
    const res = await app.inject({ url: '/example' })
    expect(res.payload).toBe('this is an example')
  })
})
