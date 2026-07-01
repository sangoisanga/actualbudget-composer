import { describe, it, expect } from 'vitest';
import { build } from '../helper';

describe('root route', () => {
  it('returns { root: true }', async () => {
    const app = await build();
    const res = await app.inject({ url: '/' });
    expect(JSON.parse(res.payload)).toEqual({ root: true });
  });
});
