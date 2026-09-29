const BASE = import.meta.env.VITE_API_URL ?? '';

async function request(path, options = {}) {
  const response = await fetch(`${BASE}/api/orders${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error ?? `HTTP ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

export const api = {
  dashboard: (signal) => request('', { signal }),
  send: (customerName, forceFail = false) =>
    request('/send', { method: 'POST', body: JSON.stringify({ customerName, forceFail }) }),
  burst: (count) => request('/burst', { method: 'POST', body: JSON.stringify({ count }) }),
  clear: () => request('', { method: 'DELETE' }),
};
