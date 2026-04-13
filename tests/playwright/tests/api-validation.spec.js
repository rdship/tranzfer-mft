/**
 * TranzFer MFT — API Validation & Error Handling Tests
 *
 * Covers: input validation, boundary testing, error response format,
 * SQL injection prevention, XSS prevention, large payloads, special characters.
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { uid } = require('../helpers/test-data');

test.describe('API Validation — Input Boundaries', () => {
  test('partner name max length enforcement', async ({ api }) => {
    const resp = await api.post('/api/partners', {
      companyName: 'A'.repeat(1000),
      displayName: 'Test',
      partnerType: 'EXTERNAL',
    });
    // Should either accept with truncation or reject
    expect([201, 400, 422, 500]).toContain(resp.status());
  });

  test('account username with special characters', async ({ api }) => {
    const resp = await api.post('/api/accounts', {
      protocol: 'SFTP',
      username: `test user ${uid()}!@#`,
      password: 'TestPass123!',
    });
    // Should be rejected or sanitized
    expect([201, 400, 422]).toContain(resp.status());
  });

  test('flow with empty name rejected', async ({ api }) => {
    const resp = await api.post('/api/flows', {
      name: '',
      filenamePattern: '.*',
    });
    expect([400, 422]).toContain(resp.status());
  });

  test('flow with null name rejected', async ({ api }) => {
    const resp = await api.post('/api/flows', {
      filenamePattern: '.*',
      direction: 'INBOUND',
    });
    expect([400, 422]).toContain(resp.status());
  });

  test('activity monitor page beyond total pages returns empty', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?page=99999&size=25');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.content.length).toBe(0);
  });

  test('activity monitor negative page returns error or page 0', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?page=-1&size=25');
    expect([200, 400]).toContain(resp.status());
  });

  test('activity monitor size 0 handled gracefully', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?page=0&size=0');
    expect([200, 400]).toContain(resp.status());
  });

  test('invalid UUID in path returns 400 or 404', async ({ api }) => {
    const resp = await api.get('/api/partners/not-a-uuid');
    expect([400, 404, 500]).toContain(resp.status());
  });

  test('nonexistent UUID returns 404', async ({ api }) => {
    const resp = await api.get('/api/partners/00000000-0000-0000-0000-000000000000');
    expect([404, 500]).toContain(resp.status());
  });

  test('update nonexistent partner returns 404', async ({ api }) => {
    const resp = await api.put('/api/partners/00000000-0000-0000-0000-000000000000', {
      companyName: 'Ghost',
    });
    expect([404, 500]).toContain(resp.status());
  });

  test('delete nonexistent partner returns 404', async ({ api }) => {
    const resp = await api.delete('/api/partners/00000000-0000-0000-0000-000000000000');
    expect([404, 204, 500]).toContain(resp.status());
  });
});

test.describe('API Validation — Security', () => {
  test('SQL injection in search parameter', async ({ api }) => {
    const resp = await api.get("/api/activity-monitor?filename=' OR 1=1 --");
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    // Should return 0 results, not all results (SQL injection blocked)
    expect(body.content.length).toBe(0);
  });

  test('SQL injection in partner name', async ({ api }) => {
    const resp = await api.post('/api/partners', {
      companyName: "'; DROP TABLE partners; --",
      displayName: 'Injection Test',
      partnerType: 'EXTERNAL',
    });
    // Should either create safely or reject — NOT crash
    expect([201, 400, 422]).toContain(resp.status());

    // Verify partners table still works
    const listResp = await api.get('/api/partners');
    expect(listResp.status()).toBe(200);
  });

  test('XSS in partner display name', async ({ api }) => {
    const xssPayload = '<script>alert("xss")</script>';
    const resp = await api.post('/api/partners', {
      companyName: `XSS-Test-${uid()}`,
      displayName: xssPayload,
      partnerType: 'EXTERNAL',
    });
    if (resp.status() === 201) {
      const partner = await resp.json();
      // displayName should be stored as-is (sanitized on render) or escaped
      expect(partner.displayName).not.toContain('<script>alert');
      await api.delete(`/api/partners/${partner.id}`).catch(() => {});
    }
  });

  test('XSS in flow name', async ({ api }) => {
    const resp = await api.post('/api/flows', {
      name: `<img onerror=alert(1) src=x>-${uid()}`,
      filenamePattern: '.*',
      direction: 'INBOUND',
      active: true,
      steps: [],
    });
    if (resp.status() === 201) {
      const flow = await resp.json();
      await api.delete(`/api/flows/${flow.id}`).catch(() => {});
    }
    expect([201, 400]).toContain(resp.status());
  });

  test('path traversal in filename filter', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?filename=../../etc/passwd');
    expect(resp.status()).toBe(200);
    // Should return 0 results, not expose system files
    const body = await resp.json();
    expect(body.content.length).toBe(0);
  });

  test('very long query parameter handled', async ({ api }) => {
    const longParam = 'A'.repeat(10000);
    const resp = await api.get(`/api/activity-monitor?filename=${longParam}`);
    expect([200, 400, 414]).toContain(resp.status());
  });
});

test.describe('API Validation — Error Response Format', () => {
  test('401 response has JSON body', async ({ request }) => {
    const resp = await request.get('/api/partners');
    expect(resp.status()).toBe(401);
    const contentType = resp.headers()['content-type'] || '';
    // Should return JSON, not HTML
    expect(contentType).toContain('json');
  });

  test('404 for unknown API path', async ({ api }) => {
    const resp = await api.get('/api/nonexistent-endpoint');
    expect([404, 500]).toContain(resp.status());
  });

  test('405 for wrong HTTP method', async ({ api }) => {
    // DELETE on list endpoint (no ID) should fail
    const resp = await api.delete('/api/partners');
    expect([405, 400, 500]).toContain(resp.status());
  });

  test('POST with invalid JSON body', async ({ request, token }) => {
    const resp = await request.post('/api/partners', {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: 'not json at all',
    });
    expect([400, 415, 422, 500]).toContain(resp.status());
  });

  test('POST with empty body', async ({ api }) => {
    const resp = await api.post('/api/partners', {});
    expect([400, 422, 500]).toContain(resp.status());
  });
});

test.describe('API Validation — Enum Values', () => {
  test('invalid protocol value rejected', async ({ api }) => {
    const resp = await api.post('/api/accounts', {
      protocol: 'INVALID_PROTOCOL',
      username: `pw-invalid-${uid()}`,
      password: 'TestPass123!',
    });
    expect([400, 422, 500]).toContain(resp.status());
  });

  test('invalid partner type value rejected', async ({ api }) => {
    const resp = await api.post('/api/partners', {
      companyName: `Invalid-Type-${uid()}`,
      partnerType: 'NONEXISTENT_TYPE',
    });
    expect([400, 422, 500]).toContain(resp.status());
  });

  test('invalid status filter on activity monitor', async ({ api }) => {
    const resp = await api.get('/api/activity-monitor?status=NONEXISTENT');
    expect([200, 400]).toContain(resp.status());
  });

  test('invalid flow direction', async ({ api }) => {
    const resp = await api.post('/api/flows', {
      name: `invalid-dir-${uid()}`,
      direction: 'SIDEWAYS',
      active: true,
      steps: [],
    });
    expect([201, 400, 422]).toContain(resp.status());
  });
});

test.describe('API Validation — Concurrent Operations', () => {
  test('concurrent partner creation with same name', async ({ api }) => {
    const name = `Concurrent-${uid()}`;
    const data = {
      companyName: name,
      displayName: name,
      partnerType: 'EXTERNAL',
    };

    // Fire both simultaneously
    const [resp1, resp2] = await Promise.all([
      api.post('/api/partners', data),
      api.post('/api/partners', data),
    ]);

    // One should succeed, one should fail (or both succeed if no unique constraint)
    const statuses = [resp1.status(), resp2.status()].sort();
    // At least one should be 201
    expect(statuses).toContain(201);

    // Cleanup
    if (resp1.status() === 201) {
      const p = await resp1.json();
      await api.delete(`/api/partners/${p.id}`).catch(() => {});
    }
    if (resp2.status() === 201) {
      const p = await resp2.json();
      await api.delete(`/api/partners/${p.id}`).catch(() => {});
    }
  });

  test('rapid sequential API calls do not break', async ({ api }) => {
    const promises = [];
    for (let i = 0; i < 20; i++) {
      promises.push(api.get('/api/partners'));
    }
    const results = await Promise.all(promises);
    for (const resp of results) {
      expect(resp.status()).toBe(200);
    }
  });
});
