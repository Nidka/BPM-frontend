# Fotos privadas de controles

## Arquitectura

`features/media` contiene dominio, casos de uso, transporte y presentación MVVM.
El selector y SHA-256 tienen adaptadores Android, iOS, Wasm y JVM. AppGraph inyecta
HTTP autorizado y un cliente de objetos separado: sin tokens Firebase, cookies,
redirecciones o caché persistente hacia R2. No se añadieron SDKs Firebase/S3.

La raíz App conecta media y checklists mediante slots de contenido por pregunta;
ninguna feature importa a la otra. El formulario conectado y el preparado sin
conexión reutilizan ChecklistAnswerEditor y los botones/campos del diseño común.
El panel usa hoja inferior en móvil y diálogo limitado en escritorio.

## Flujo

El chef abre las fotos de una pregunta de su control en curso, elige JPEG/PNG y
el cliente comprueba tamaño y firma de archivo. UploadEvidence normaliza el
nombre y calcula SHA-256; se reserva un UUID estable, se realiza PUT y se confirma
en el backend. Una respuesta incierta se comprueba antes de repetir el PUT.
Solo AVAILABLE cuenta para finalizar. Los fallos no se presentan como guardados.

El límite y los formatos proceden del API: hasta tres fotos por pregunta y el
máximo configurado (10 MiB por defecto, hasta 25 MiB). No se inventan rangos
sanitarios ni se incorporan sedes/usuarios de prueba a la app.

Cerrar con una foto pendiente exige confirmar el descarte de esa selección. Al
cerrar, cambiar de empresa/cuenta/ruta o perder acceso se cancelan operaciones,
se limpian arrays y se ignoran resultados tardíos. Las respuestas del formulario
permanecen. Las URLs temporales no llegan al estado de presentación ni al
almacenamiento local. La previsualización se descarga bajo demanda y se borra al
cerrarla.

ADMIN/SUPER_ADMIN consultan fotos desde el detalle de seguimiento; no pueden
adjuntar ni retirar fotos del chef. El backend vuelve a autorizar todo; los
controles de UI solo orientan, no conceden permisos.

## Respuestas offline y fotos

Las respuestas siguen protegidas por la cola local documentada en OFFLINE.md.
Los recuentos confirmados se conservan como proyección por pregunta, sin
sobrescribir textos editados ni la intención de una operación ya en cola.
Cargar fotos requiere conexión: no hay cola binaria ni promesa de conservar una
selección al cerrar la app. El servidor vuelve a comprobar evidencias al finalizar.

## Transporte web

`web/media-proxy.mjs` solo expone las cinco rutas de evidencia definidas en el
OpenAPI backend. Valida IDs, nombres, política, contexto, MIME, estados y tamaño;
proyecta solo los campos públicos. Las firmas solo pueden apuntar a HTTPS R2,
con headers permitidos y sin credenciales de usuario, fragmentos ni redirección.
CSP amplía únicamente connect-src a R2: las imágenes no se incluyen por URL en HTML.
El Dockerfile incluye el proxy explícitamente.

## Configuración externa y pruebas

Aplicar la migración backend V4 antes de publicar el nuevo backend y frontend.
El bucket R2 debe ser privado y tener CORS para los orígenes propios con GET,
PUT, HEAD y los headers firmados. Credenciales permanecen en el backend.

Pruebas automatizadas cubren selección, SHA, contexto, tickets, reintentos,
resultado perdido, limpieza de bytes, retirada y validación de respuestas con
foto obligatoria. La prueba de aceptación del bucket R2 y los selectores en
dispositivos reales se realiza aparte; compilación o dobles HTTP no la sustituyen.

La limpieza física programada de huérfanos y la cola binaria offline no forman
parte de esta entrega.

## Resultado local del 2 de noviembre de 2026

182 pruebas JVM y 36 pruebas Node aprobadas. Web, APK Android e iOS Simulator
ARM64 construidos. Chrome aislado comprobó selección de archivo, lectura privada,
ancho de 320/390/768/1440 px, conservación de respuestas y selección pendiente,
cancelación de cierre y confirmación recuperada sin repetir PUT. Se corrigió la
pérdida del contexto al alternar hoja y diálogo y se evitó una confirmación
anidada que ocultaba los controles al árbol accesible. Estas pruebas usan datos
y servicios ficticios; R2 y dispositivos reales requieren aceptación separada.
