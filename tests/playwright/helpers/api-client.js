/**
 * TranzFer MFT — High-level API client for test data setup/teardown.
 *
 * Every method returns the parsed JSON body and throws on non-2xx responses
 * (unless `expectStatus` is explicitly set).
 */

async function assertOk(resp, context) {
  if (resp.status() >= 400) {
    const text = await resp.text().catch(() => '(no body)');
    throw new Error(`API ${context} failed (${resp.status()}): ${text}`);
  }
  if (resp.status() === 204) return null;
  return resp.json().catch(() => null);
}

class ApiClient {
  constructor(requestContext, token) {
    this.request = requestContext;
    this.headers = { Authorization: `Bearer ${token}` };
  }

  async get(path) {
    const r = await this.request.get(path, { headers: this.headers });
    return assertOk(r, `GET ${path}`);
  }

  async post(path, data) {
    const r = await this.request.post(path, { data, headers: this.headers });
    return assertOk(r, `POST ${path}`);
  }

  async put(path, data) {
    const r = await this.request.put(path, { data, headers: this.headers });
    return assertOk(r, `PUT ${path}`);
  }

  async patch(path, data) {
    const r = await this.request.patch(path, { data, headers: this.headers });
    return assertOk(r, `PATCH ${path}`);
  }

  async del(path) {
    const r = await this.request.delete(path, { headers: this.headers });
    return assertOk(r, `DELETE ${path}`);
  }

  async getRaw(path) {
    return this.request.get(path, { headers: this.headers });
  }

  async postRaw(path, data) {
    return this.request.post(path, { data, headers: this.headers });
  }

  async putRaw(path, data) {
    return this.request.put(path, { data, headers: this.headers });
  }

  async deleteRaw(path) {
    return this.request.delete(path, { headers: this.headers });
  }
}

module.exports = { ApiClient, assertOk };
