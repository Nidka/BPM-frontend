// Contrato compartido por los dos proxies de lectura; no acepta filtros arbitrarios.
export function pageQuery(url, { workspace = false, detail = false, statuses = [] } = {}) {
  if (detail && url.search) throw new Error('Consulta no permitida');
  const result = new URLSearchParams();
  for (const [key, value] of url.searchParams) {
    if (result.has(key)) throw new Error('Parámetro repetido');
    if (key === 'pageSize') {
      if (!/^[1-9][0-9]{0,2}$/.test(value) || Number(value) > 100) throw new Error('Tamaño inválido');
    } else if (!workspace && key === 'pageToken') {
      if (value.length > 2048 || !/^[A-Za-z0-9_-]*$/.test(value)) throw new Error('Cursor inválido');
    } else if (!workspace && key === 'query') {
      if ([...value].length > 160 || /\p{Cc}/u.test(value)) throw new Error('Búsqueda inválida');
    } else if (key === 'status' && statuses.includes(value)) {
      // Filtro de estado de miembros y sedes; solo valores del contrato.
    } else throw new Error('Parámetro no permitido');
    result.set(key, value);
  }
  return result.toString() ? '?' + result.toString() : '';
}
export function publicPagination(value) {
  if (value === undefined) return undefined; // Compatibilidad con el backend anterior durante la publicación.
  const token = value?.nextPageToken;
  if (typeof token !== 'string' || token.length > 2048 || !/^[A-Za-z0-9_-]*$/.test(token)) throw new Error('Cursor inválido');
  return { nextPageToken: token };
}
