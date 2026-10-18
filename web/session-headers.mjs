// Only server-provided, bounded numeric deadlines cross the proxy.
export function forwardSessionHeaders(result, response) {
  for (const key of ['X-BPM-Session-Expires-At', 'X-BPM-Session-Idle-Expires-At']) {
    const value = result.headers.get(key);
    if (value && /^[1-9][0-9]{0,11}$/.test(value)) response.setHeader(key, value);
  }
}
