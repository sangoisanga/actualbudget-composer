import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';

describe('POST /webhook', () => {
  let app: ReturnType<typeof Fastify>;

  beforeAll(async () => {
    app = Fastify();
    app.addContentTypeParser('*', { parseAs: 'string' }, (_req, _payload, done) => done(null));
    app.post('/webhook', async (req) => {
      const body = req.body as string;
      if (!body || body.length === 0) {
        return { error: 'Empty body' };
      }
      return { status: 'received', length: body.length };
    });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it('accepts raw email body and returns 200', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/webhook',
      headers: { 'content-type': 'text/plain' },
      payload: '<html><body>Test email</body></html>',
    });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(res.payload)).toHaveProperty('status', 'received');
  });

  it('returns 200 even on empty body', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/webhook',
      payload: '',
    });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(res.payload)).toHaveProperty('error');
  });
});
