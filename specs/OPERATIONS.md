# Operación diaria

El módulo `features/operations` implementa proveedores, productos y su criterio térmico,
recepción con varios productos, consulta PEPS, planes y ejecución de limpieza.
Los responsables se derivan de la sesión en el servidor; ningún cuerpo envía roles,
usuario ni empresa. Los catálogos se consultan mediante búsqueda y páginas, sin
cargar todas las opciones en un menú desplegable.

`OperationsViewModel.open(userId, organizationId, role, siteId)` recibe contexto público
adaptado por app. La sede se selecciona en la raíz con el selector del workspace;
operations no importa otras funcionalidades. `reset()` cancela trabajo y limpia estado
visible. `refreshIfStale()` actualiza consultas al regresar sin sustituir un formulario.
La raíz puede observar `state.form`, `dirty`, `pending`, `busy` y `saving` para coordinar
navegación. `OperationsScreen(viewModel, modifier)` necesita altura acotada: tiene su
propio desplazamiento vertical.

ADMIN/SUPER_ADMIN crean proveedores, productos y planes; actualizan el criterio térmico
con versión optimista. CHEF registra recepciones y consulta PEPS. CHEF/WORKER ejecutan
planes activos; WORKER consulta únicamente sus propias ejecuciones. Los supervisores
consultan el historial. Estas comprobaciones de presentación complementan la autoridad
del backend, que reautoriza cada consulta y escritura.

## Formularios y recuperación

`LocalOperationsDraftRepository` usa `JsonDraftDocument` sobre el `DraftStorage` inyectado.
Su clave incluye origen, cuenta, empresa, recurso y sede. Antes de revelar datos se
confirma el workspace, rol y acceso actual a la sede; las ejecuciones también vuelven
a consultar el plan. Los borradores de recepción vuelven a consultar sus productos.
Los campos incompletos se conservan, pero un documento pendiente debe corresponder
exactamente al comando reconstruido de su formulario.

`DraftAutosave` serializa escrituras. Antes de HTTP se confirma el guardado del UUID,
clave idempotente y cuerpo JSON completo. Tras fallo de conexión, servicio, sesión,
respuesta no interpretable o límite de solicitudes, ese cuerpo queda inmutable para
comprobar el mismo registro. Un rechazo definitivo permite corregir el formulario.
El descarte es explícito y requiere confirmación en pantalla; no se permite descartar
un envío cuyo resultado todavía es incierto. «Guardar y salir» espera el guardado local
y conserva la pantalla si falla. El cierre de un registro confirmado no recrea su borrador.
No hay confirmación offline ficticia ni generación automática de otra operación pendiente.

Las respuestas de escritura se comparan con el contenido enviado antes de anunciar
éxito, incluyendo cantidades canónicas, instantes equivalentes y versión del criterio.
La lectura admite envase nulo en legado; una recepción nueva confirmada debe informar
el envase y los detalles devueltos por el servidor. Los errores remotos se sanitizan.

## Entradas y límites

La cantidad permanece como cadena decimal exacta, positiva, hasta tres decimales y
999999999,999; nunca pasa por Double. La temperatura usa `DecimalInput`, dos decimales,
magnitud máxima 9999,99. Se reutilizan `BpmTextField`, navegación de formularios,
teclados decimales, botones y `PageNavigation`. La fecha/hora del registro se captura
automáticamente y se muestra con `displayDateTime`; el vencimiento admite fecha ISO
claramente etiquetada. El servidor compara el vencimiento con el día de recepción en
la zona de la sede, sin asumir la zona del dispositivo.

PEPS conserva el orden del servidor por recepción, item y lote; nunca se reordena por
vencimiento. Los lotes solo nacen de productos aceptados. Esta entrega no consume ni
descarta stock, no admite turnos PER_SHIFT, no modifica hechos confirmados y no carga
fotos de limpieza. Los formularios recuperables no son una cola de nuevas operaciones
o una autorización para trabajar sin conexión.

## Verificación

Las pruebas compartidas cubren precisión, criterios térmicos y permisos negativos;
consistencia e aislamiento del documento; errores públicos; respuesta incierta,
recuperación del mismo cuerpo y cierre sin recrear registros ya confirmados.
La integración completa se valida con `jvmTest`, compilación Wasm, Android e iOS y
pruebas del proxy explícito.

Las respuestas de fecha se comparan a precisión de microsegundos, equivalente al
almacenamiento PostgreSQL, conservando intacto el JSON pendiente original. La
lectura PEPS admite UUID históricos del lote independientes del ítem y códigos
de lote anteriores vacíos. El límite de cuerpos pendientes se calcula en bytes
UTF-8, no en unidades UTF-16; el conteo de longitudes de textos de negocio respeta
puntos de código Unicode. Estos casos tienen regresiones compartidas y del proxy.

Verificación local del 3 de noviembre de 2026: 198 pruebas JVM sin fallos, 52
pruebas Node sin fallos y compilaciones Wasm, APK Android y framework de simulador
iOS aprobadas. La prueba de interfaz contra la API Go y PostgreSQL aislados cubrió
alta ADMIN, recepción CHEF, lote PEPS con cantidad exacta y limpieza WORKER, además
de permisos negativos y anchos 320/390/768/1440. Volver desde selección y revisión
conservó el formulario. La sesión Firebase fue una identidad ficticia solo en el
arnés de prueba; no se registraron cuentas reales. La compilación iOS no sustituye
una prueba en dispositivo físico.
