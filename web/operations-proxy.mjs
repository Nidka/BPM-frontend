import { readPayload } from './checklists-proxy.mjs';
import { pageQuery, publicPagination } from './pagination.mjs';
import { forwardSessionHeaders } from './session-headers.mjs';

const uuidPattern = '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}';
const organizationPattern = '[A-Za-z0-9_-]{1,64}';
const organization = value => typeof value === 'string' && new RegExp(`^${organizationPattern}$`).test(value);
const route = new RegExp(`^/backend/api/v1/organizations/(${organizationPattern})/(suppliers|products|supply-receipts|storage-lots|cleaning-plans|cleaning-executions)(?:/(${uuidPattern})(/temperature-policy)?)?$`);
const scoped = ['supply-receipts', 'storage-lots', 'cleaning-plans', 'cleaning-executions'];
const own = (value, key) => Object.hasOwn(value, key);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const uuid = value => typeof value === 'string' && new RegExp(`^${uuidPattern}$`).test(value) && value !== '00000000-0000-0000-0000-000000000000';
const sameId = (left, right) => uuid(left) && uuid(right) && left.toLowerCase() === right.toLowerCase();
const fail = () => { throw new Error('Datos de operación no válidos'); };
const require = condition => { if (!condition) fail(); };
const error = (code, message, fields = {}) => JSON.stringify({ errors: [{ code, message, fields }] });
const unavailable = send => send(503, error('BACKEND_UNAVAILABLE', 'No pudimos confirmar la operación.'));
const string = (value, maximum, minimum = 0) => typeof value === 'string' && [...value].length <= maximum && [...value.trim()].length >= minimum;
const text = (value, key, max, min = 0, optional = false) => {
  const field = optional && value[key] === undefined ? '' : value[key];
  require(string(field, max, min)); return field;
};
const identifier = (value, key) => { require(uuid(value[key])); return value[key]; };
const exact = (value, fields) => require(object(value) && Object.keys(value).every(key => fields.includes(key)));
const canonicalQuantity = value => value.replace(/(\.[0-9]*?)0+$/, '$1').replace(/\.$/, '');
const quantity = (value, allowZero = false) => typeof value === 'string' && /^(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,3})?$/.test(value) && (allowZero || canonicalQuantity(value) !== '0');
const temperature = value => typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 9999.99 && Math.abs(value * 100 - Math.round(value * 100)) < 0.0000001;
function calendar(value) {
  if (typeof value !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) || value.startsWith('0000')) return false;
  const parsed = new Date(value + 'T00:00:00Z');
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}
function instant(value, input = false) {
  return typeof value === 'string' && /^[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,9})?Z$/.test(value) && calendar(value.slice(0, 10)) && Number.isFinite(Date.parse(value)) && (!input || Date.parse(value) <= Date.now() + 300000);
}
function stamp(value, key, input = false) { require(instant(value[key], input)); return value[key]; }
function expiry(value, input = false) {
  if (input && value === '') return null;
  require(value == null || calendar(value)); return value ?? null;
}
const dueTime = value => typeof value === 'string' && /^(?:|(?:[01][0-9]|2[0-3]):[0-5][0-9])$/.test(value);
function policy(value, input) {
  require(typeof value.requiresTemperature === 'boolean');
  const minimumTemperature = value.minimumTemperature ?? null, maximumTemperature = value.maximumTemperature ?? null;
  if (!value.requiresTemperature) require(minimumTemperature === null && maximumTemperature === null);
  else if (!input && minimumTemperature === null && maximumTemperature === null) { /* Legado pendiente de configurar. */ }
  else require(temperature(minimumTemperature) && temperature(maximumTemperature) && minimumTemperature <= maximumTemperature);
  return { requiresTemperature: value.requiresTemperature, minimumTemperature, maximumTemperature };
}
const fields = {
  suppliers: ['id', 'name', 'taxId', 'contactName', 'contactPhone', 'contactEmail', 'address'],
  products: ['id', 'name', 'category', 'unit', 'requiresTemperature', 'minimumTemperature', 'maximumTemperature'],
  'supply-receipts': ['id', 'locationId', 'supplierId', 'receivedAt', 'documentReference', 'notes', 'clientOperationId', 'clientCreatedAt', 'items'],
  'cleaning-plans': ['id', 'locationId', 'area', 'task', 'instructions', 'productName', 'frequency', 'dueTime'],
  'cleaning-executions': ['id', 'locationId', 'cleaningPlanId', 'executedAt', 'result', 'observation', 'clientOperationId', 'clientCreatedAt'],
};
const itemFields = ['id', 'productId', 'batchCode', 'expirationDate', 'quantity', 'temperatureCelsius', 'decision', 'rejectionReason', 'packagingCondition', 'storageLocation', 'label'];
const policyFields = ['rowVersion', 'requiresTemperature', 'minimumTemperature', 'maximumTemperature'];
function receiptItem(value, input) {
  require(object(value)); if (input) exact(value, itemFields);
  require(quantity(value.quantity));
  require(value.temperatureCelsius == null || temperature(value.temperatureCelsius));
  require(['ACCEPTED', 'REJECTED', 'OBSERVED'].includes(value.decision));
  require(['INTACT', 'DAMAGED'].includes(value.packagingCondition) || !input && value.packagingCondition === null);
  const result = { id: identifier(value, 'id'), productId: identifier(value, 'productId'), batchCode: text(value, 'batchCode', 100, input ? 1 : 0), expirationDate: expiry(value.expirationDate, input), quantity: value.quantity,
    temperatureCelsius: value.temperatureCelsius ?? null, decision: value.decision, rejectionReason: text(value, 'rejectionReason', 2000, 0, input), packagingCondition: value.packagingCondition,
    storageLocation: text(value, 'storageLocation', 120, 0, input), label: text(value, 'label', 160, 0, input) };
  if (!input) {
    result.productName = text(value, 'productName', 200, 2); result.unit = text(value, 'unit', 24, 1);
    result.minimumTemperature = value.minimumTemperature ?? null; result.maximumTemperature = value.maximumTemperature ?? null;
    require(result.minimumTemperature === null && result.maximumTemperature === null || temperature(result.minimumTemperature) && temperature(result.maximumTemperature) && result.minimumTemperature <= result.maximumTemperature);
  }
  return result;
}
function entity(resource, value, input = false) {
  require(object(value)); if (input) exact(value, fields[resource]);
  const result = { id: identifier(value, 'id') };
  if (resource === 'suppliers') {
    Object.assign(result, { name: text(value, 'name', 200, 2), taxId: text(value, 'taxId', 20, 0, input), contactName: text(value, 'contactName', 160, 0, input), contactPhone: text(value, 'contactPhone', 30, 0, input), contactEmail: text(value, 'contactEmail', 320, 0, input), address: text(value, 'address', 300, 0, input) });
    require(!result.contactPhone || (result.contactPhone.replace(/\D/g, '').length >= 7 && result.contactPhone.replace(/\D/g, '').length <= 15 && /^[+0-9() .-]+$/.test(result.contactPhone)));
    require(!result.contactEmail || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(result.contactEmail));
  } else if (resource === 'products') {
    Object.assign(result, { name: text(value, 'name', 200, 2), category: text(value, 'category', 100, 0, input), unit: text(value, 'unit', 24, 1), ...policy(value, input) });
    if (!input) { require(Number.isSafeInteger(value.rowVersion) && value.rowVersion > 0); result.rowVersion = value.rowVersion; }
  } else {
    result.locationId = identifier(value, 'locationId');
    if (resource === 'supply-receipts') {
      require(Array.isArray(value.items) && value.items.length >= 1 && value.items.length <= 50);
      Object.assign(result, { supplierId: identifier(value, 'supplierId'), receivedAt: stamp(value, 'receivedAt', input), documentReference: text(value, 'documentReference', 100, 0, input), notes: text(value, 'notes', 2000, 0, input), clientOperationId: identifier(value, 'clientOperationId'), clientCreatedAt: stamp(value, 'clientCreatedAt', input), items: value.items.map(item => receiptItem(item, input)) });
      require(new Set(result.items.map(item => item.id.toLowerCase())).size === result.items.length);
      if (!input) {
        Object.assign(result, { supplierName: text(value, 'supplierName', 200, 2), receivedBy: text(value, 'receivedBy', 128, 1), createdAt: stamp(value, 'createdAt') });
        if (own(value, 'receivedByName')) result.receivedByName = text(value, 'receivedByName', 200);
      }
    } else if (resource === 'storage-lots') {
      require(quantity(value.quantityAvailable, true) && ['AVAILABLE', 'CONSUMED', 'DISCARDED'].includes(value.status) && typeof value.expired === 'boolean');
      Object.assign(result, { receiptItemId: identifier(value, 'receiptItemId'), productId: identifier(value, 'productId'), productName: text(value, 'productName', 200, 2), unit: text(value, 'unit', 24, 1), batchCode: text(value, 'batchCode', 100), expirationDate: expiry(value.expirationDate), receivedAt: stamp(value, 'receivedAt'), storageLocation: text(value, 'storageLocation', 120, 1), label: text(value, 'label', 160), quantityAvailable: value.quantityAvailable, status: value.status, expired: value.expired });
    } else if (resource === 'cleaning-plans') {
      require(['DAILY', 'WEEKLY', 'MONTHLY'].includes(value.frequency) && dueTime(input && value.dueTime === undefined ? '' : value.dueTime));
      Object.assign(result, { area: text(value, 'area', 100, 2), task: text(value, 'task', 240, 2), instructions: text(value, 'instructions', 2000, 0, input), productName: text(value, 'productName', 160, 0, input), frequency: value.frequency, dueTime: value.dueTime ?? '' });
      if (!input) { require(typeof value.active === 'boolean'); result.active = value.active; }
    } else if (resource === 'cleaning-executions') {
      require(['COMPLETED', 'NOT_COMPLETED', 'OBSERVED'].includes(value.result));
      Object.assign(result, { cleaningPlanId: identifier(value, 'cleaningPlanId'), executedAt: stamp(value, 'executedAt', input), result: value.result, observation: text(value, 'observation', 2000, 0, input), clientOperationId: identifier(value, 'clientOperationId'), clientCreatedAt: stamp(value, 'clientCreatedAt', input) });
      if (!input) Object.assign(result, { area: text(value, 'area', 100, 2), task: text(value, 'task', 240, 2), responsibleUserId: text(value, 'responsibleUserId', 128, 1), responsibleName: text(value, 'responsibleName', 200), createdAt: stamp(value, 'createdAt') });
    } else fail();
  }
  return result;
}
function context(value, { organizationId, locationId, id }) {
  require(object(value));
  if (own(value, 'organizationId')) require(organization(value.organizationId) && value.organizationId === organizationId);
  if (locationId) require(sameId(value.locationId, locationId));
  if (id) require(sameId(value.id, id));
}
function confirmation(input, output) {
  for (const [key, value] of Object.entries(input)) {
    if (Array.isArray(value)) {
      require(Array.isArray(output[key]) && value.length === output[key].length);
      value.forEach((item, index) => confirmation(item, output[key][index]));
    } else if (key === 'quantity') require(canonicalQuantity(value) === canonicalQuantity(output[key]));
    else if (uuid(value)) require(sameId(value, output[key]));
    else if (typeof value === 'string' && instant(value)) require(Date.parse(value) === Date.parse(output[key]));
    else require(value === output[key]);
  }
}
export function operationsRoute(path) {
  const match = path.match(route);
  return Boolean(match && organization(match[1]) && (!match[3] || uuid(match[3]) && (match[2] === 'products' ? Boolean(match[4]) : !match[4] && ['supply-receipts', 'cleaning-executions'].includes(match[2]))));
}

/** Operaciones públicas cerradas; la API vuelve a autorizar empresa, sede, rol y reglas de negocio. */
export async function proxyOperations(request, url, upstream, send, response) {
  if (!operationsRoute(url.pathname)) return send(404, error('NOT_FOUND', 'Recurso no disponible.'));
  const [, organizationId, resource, id, action] = url.pathname.match(route);
  const method = request.method, writing = method === 'POST' || method === 'PUT';
  const permitted = action ? method === 'PUT' : id ? method === 'GET' : method === 'GET' || method === 'POST' && resource !== 'storage-lots';
  if (!permitted) return send(405, error('METHOD_NOT_ALLOWED', 'Método no permitido.'));
  const collection = !id && method === 'GET';
  let query, locationId;
  try {
    if (collection && scoped.includes(resource)) {
      const locations = url.searchParams.getAll('locationId'); require(locations.length === 1 && uuid(locations[0])); locationId = locations[0];
      const filters = new URL(url); filters.searchParams.delete('locationId');
      query = pageQuery(filters); const params = new URLSearchParams(query); params.set('locationId', locationId); query = '?' + params.toString();
    } else query = pageQuery(url, { detail: !collection });
    if (!writing) require(!request.headers['transfer-encoding'] && (!request.headers['content-length'] || request.headers['content-length'] === '0'));
  } catch { return send(400, error(collection ? 'INVALID_PAGINATION' : 'INVALID_REQUEST', 'Revisa los filtros y los datos de la solicitud.')); }
  const authorization = request.headers.authorization;
  if (typeof authorization !== 'string' || authorization.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(authorization)) return send(401, error('UNAUTHENTICATED', 'Inicia sesión para continuar.'));
  const headers = { Accept: 'application/json', Authorization: authorization };
  let input, body;
  if (writing) {
    if (request.headers.origin) {
      let origin; try { origin = new URL(request.headers.origin); } catch {}
      if (!origin || !['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.host !== request.headers.host || origin.origin !== request.headers.origin) return send(403, error('ORIGIN_NOT_ALLOWED', 'Origen no permitido.'));
    }
    if (!/^application\/json(?:\s*;[^\r\n]*)?$/i.test(request.headers['content-type'] || '')) return send(415, error('UNSUPPORTED_MEDIA_TYPE', 'Envía una solicitud JSON.'));
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string' || !/^[\x21-\x7e]{8,100}$/.test(key)) return send(400, error('INVALID_REQUEST', 'La clave de la operación no es válida.'));
    try {
      const raw = await readPayload(request, 512 * 1024);
      if (action) {
        exact(raw, policyFields); require(Number.isSafeInteger(raw.rowVersion) && raw.rowVersion > 0);
        input = { rowVersion: raw.rowVersion, ...policy(raw, true) };
      } else input = entity(resource, raw, true);
      // Se preserva el JSON público enviado para que el replay mantenga la misma operación.
      body = JSON.stringify(raw); headers['Content-Type'] = 'application/json'; headers['Idempotency-Key'] = key;
    } catch { return send(400, error('INVALID_REQUEST', 'Revisa los datos de la operación.')); }
  }
  try {
    const result = await fetch(new URL(url.pathname.slice('/backend'.length) + query, upstream), { method, headers, body, redirect: 'error', signal: AbortSignal.timeout(10000) });
    forwardSessionHeaders(result, response);
    if (!result.ok) {
      const invalidFields = {};
      const recognized = [400, 401, 403, 404, 409, 422, 429].includes(result.status);
      if (recognized) {
        const invalid = await readPayload(result.body, 65536);
        require(object(invalid) && Array.isArray(invalid.errors) && invalid.errors.length > 0 && invalid.errors.length <= 100);
        for (const item of invalid.errors) {
          require(object(item) && typeof item.code === 'string' && typeof item.message === 'string' && (item.fields === undefined || object(item.fields)));
          if (result.status !== 422 || !writing) continue;
          for (const key of Object.keys(item.fields || {})) if ((action ? policyFields : fields[resource]).includes(key) || resource === 'supply-receipts' && /^items\.(?:[0-9]|[1-4][0-9])\.[A-Za-z]+$/.test(key) && itemFields.includes(key.split('.')[2])) invalidFields[key] = 'Revisa este campo.';
        }
      } else await result.body?.cancel();
      const failures = {
        400: [writing ? 'INVALID_REQUEST' : 'INVALID_PAGINATION', 'Revisa los datos de la solicitud.'],
        401: ['UNAUTHENTICATED', 'Inicia sesión nuevamente.'], 403: ['ACCESS_DENIED', 'No tienes permiso para esta operación.'],
        404: ['NOT_FOUND', 'El recurso no está disponible.'], 409: ['RESOURCE_CONFLICT', 'La operación cambió o ya existe. Actualiza los datos.'],
        422: ['VALIDATION_ERROR', 'Revisa los campos indicados.'], 429: ['RATE_LIMITED', 'Espera un momento antes de reintentar.'],
      };
      return failures[result.status] ? send(result.status, error(...failures[result.status], invalidFields)) : unavailable(send);
    }
    const payload = await readPayload(result.body);
    require(object(payload));
    if (collection) {
      require(Array.isArray(payload.data) && payload.data.length <= Number(url.searchParams.get('pageSize') || 25) && object(payload.pagination));
      const data = payload.data.map(value => { context(value, { organizationId, locationId }); return entity(resource, value); });
      require(new Set(data.map(value => value.id.toLowerCase())).size === data.length);
      return send(200, JSON.stringify({ data, pagination: publicPagination(payload.pagination) }));
    }
    context(payload.data, { organizationId, locationId: input?.locationId, id: id || input?.id });
    const data = entity(resource, payload.data);
    if (writing) {
      if (action) { const { rowVersion, ...expected } = input; confirmation(expected, data); require(data.rowVersion > rowVersion); }
      else { confirmation(input, data); if (resource === 'cleaning-plans') require(data.active); }
    }
    return send(method === 'POST' ? 201 : 200, JSON.stringify({ data }));
  } catch { return unavailable(send); }
}
