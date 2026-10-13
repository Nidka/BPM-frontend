# Contrato offline para implementar después

Estado: diseño definido; no hay base local, service worker, cola ni sincronizador
implementados en esta entrega. La pantalla de conexión requiere red y muestra el
fallo de forma explícita.

Para cada módulo con operaciones offline:

1. La UI observará datos locales mediante Flow; el repositorio guarda primero.
2. Cada operación tendrá UUID generado en cliente, organizationId, locationId
   cuando aplique, deviceId, clientOperationId, fecha UTC e Idempotency-Key.
3. Una cola persistente conservará operaciones tras reinicios. Estados:
   pendiente, sincronizando, sincronizado y requiere atención.
4. Se sincronizan datos estructurados antes de subir evidencias a R2.
5. Plantillas/catálogos: servidor gana. Borradores: versión optimista; el conflicto
   nunca se resuelve sobrescribiendo silenciosamente.
6. Archivos pendientes permanecen locales hasta confirmar carga privada; no se
   persisten URLs firmadas como referencia estable.
7. Cachés y colas se separan por usuario y organización. El cierre de sesión y
   cambio de tenant definen su limpieza sin perder operaciones inadvertidamente.
8. Conectividad es una señal para reintentar; solo la respuesta del servidor
   confirma sincronización.

Adaptadores previstos: SQLDelight para Android/iOS e IndexedDB para web, detrás
del puerto específico de cada feature. No introducir interfaces vacías ni fingir
almacenamiento común mientras no exista un recorrido que lo necesite.

La disponibilidad de la aplicación web sin internet requerirá cachear el bundle
y recursos mediante service worker. Ese paso y la persistencia web se deben
probar aparte; servir la app en Railway por sí solo no aporta soporte offline.
