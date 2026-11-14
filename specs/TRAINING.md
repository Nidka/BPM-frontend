# Academia BPM: capacitación, video y modo estudio

`features/training` implementa el recorrido completo de capacitación (RF-11) contra el
contrato `Software/Backend`: aprender con lecciones y video, practicar sin calificación,
evaluar con intentos calificados por el servidor y preparar cursos desde administración.
Las rutas de la API están bajo `/api/v1/organizations/{org}/training`.

## Estructura

```text
features/training/
  domain/        modelos, repositorios, casos de uso, reglas de borrador y de video
  data/          DTO, RemoteTrainingRepository (API autorizada + PUT firmado a R2)
  presentation/  TrainingViewModel, TrainingAuthoringViewModel, pantallas, reproductor y selector
core/crypto/     Sha256 en Kotlin puro (digest del video en cualquier plataforma)
core/network/    createObjectStorageClient (cliente sin sesión BPM para R2)
web/training-proxy.mjs (+ test)  validación estricta de rutas, cuerpos y respuestas
```

`app/TrainingContent.kt` conecta la raíz con las pantallas. La lista de cursos del workspace
(sección Aprender) sigue siendo el catálogo `courses` existente: cada entrada tiene ahora
«Abrir curso», que navega a `aprender/{courseId}`. No se duplicó el catálogo. ADMIN y
SUPER_ADMIN ven la tarjeta «Academia BPM» en Empresa (`empresa/academia`).

## Aprender

- **Detalle**: título, descripción, duración, versión, avance (`NOT_STARTED/IN_PROGRESS/COMPLETED`),
  mejor puntaje, XP, constancia con código de verificación y lista de lecciones.
- **Visor de lección**: texto y, si existe, video. Navegación anterior/siguiente. Cada lección que se
  abre guarda `lastLessonId` con `PUT progress`; el botón «Continuar donde quedaste» retoma desde
  esa lección (o la primera). Si el avance no se guarda, la lectura continúa con un aviso.
- **Transcripción accesible**: el texto de la lección se muestra bajo el video como «Transcripción y
  texto». El contrato no entrega pistas de subtítulos, por eso no hay `<track>`.
- Las preguntas de la vista de aprendizaje **nunca** incluyen respuesta correcta ni explicación; el
  DTO de datos ni siquiera declara esos campos y el proxy Node los descarta.

## Modo estudio · sin calificación

Rotulado en pantalla como «Modo estudio · sin calificación». Repasa las lecciones y practica las
preguntas una por una (todas las del curso o solo las de la lección actual). Cada respuesta llama a
`POST study/answers` y muestra correcta/incorrecta, la respuesta correcta y la explicación. Permite
reintentar la pregunta. **No** muestra puntaje, no crea intentos, no otorga XP ni constancia, y no
cuenta para la aprobación. Sí actualiza «última lección vista» al abrir lecciones.

## Modo evaluación

`DIAGNOSTIC` y `FINAL`. Hay que responder todas las preguntas antes de enviar. Al construir el
intento se fijan una vez `id`, `clientOperationId`, `startedAt` y `clientCreatedAt`:

- Fallo incierto (red, 5xx, 429, respuesta ilegible): el intento queda **pendiente**, las respuestas
  se bloquean y «Reintentar envío» reenvía exactamente el mismo cuerpo; el servidor responde con el
  mismo resultado (idempotencia por `clientOperationId`).
- Rechazo definitivo (p. ej. `TRAINING_ANSWERS_INVALID`): se descarta el pendiente; al corregir se
  crea un intento nuevo con identificadores nuevos.
- `409 TRAINING_VERSION_OUTDATED`: se recarga el curso y se reinicia la evaluación sobre las
  preguntas vigentes, con aviso.
- Resultado: por pregunta (correcta, respuesta correcta, explicación), puntaje, aprobado/no
  aprobado, XP y constancia con código. El cliente nunca envía ni calcula puntaje; la respuesta se
  valida contra lo enviado (misma opción, `correct == (selected == correctOption)`, puntaje =
  `round(correctas*100/total)`).

## Video

La app pide `POST …/lessons/{id}/video` cuando la persona pulsa «Ver video» y reproduce la URL
firmada (GET R2, 15 min). La URL vive solo en memoria (`VideoState.Ready`), se descarta al cambiar
de lección o salir, nunca se registra ni se persiste. Si el reproductor falla y el ticket venció
(`expiresAt`), se pide otro automáticamente; si no, se ofrece «Reintentar video».

`LessonVideoPlayer` es `expect/actual`:

| Plataforma | Implementación |
|---|---|
| Android | AndroidX Media3 ExoPlayer + `PlayerView` (`media3-exoplayer` y `media3-ui` 1.8.0 en `libs.versions.toml`); se pausa en `ON_PAUSE` y se libera al salir |
| iOS | `AVPlayerViewController` (AVKit) con `UIKitViewController`; el estado del `AVPlayerItem` se consulta para detectar una URL vencida |
| Web (wasmJs) | elemento HTML `<video controls>` superpuesto y sincronizado con el área de Compose (`onGloballyPositioned`); un `clip-path` recorta el video cuando el desplazamiento lo corta. La CSP añade `media-src 'self' https://*.r2.cloudflarestorage.com` |
| JVM | solo host de pruebas: botón que abre el video en el navegador del sistema |

El video nunca pasa por el proxy Node: el navegador lo pide directo a R2.

## Autoría (ADMIN y SUPER_ADMIN)

Lista de cursos (filtro por estado), alta (código, título, descripción, duración), editor de
borrador y publicación. El editor admite lecciones (título, texto, orden, video), preguntas (enunciado,
2–6 opciones, correcta, explicación, lección opcional) y puntaje de aprobación. `TrainingDraftRules`
valida en el cliente los límites del contrato (ver `specs/INPUT.md`):

| Dato | Límite |
|---|---|
| Código de curso | 1–32, letras/números/`.`/`_`/`-` |
| Título de curso | 2–200; descripción ≤ 2000; duración 1–10080 min |
| Lección | título 1–200, contenido ≤ 20000, hasta 50 |
| Pregunta | enunciado 1–300, 2–6 opciones de 1–300, correcta válida, explicación ≤ 1000, hasta 100 |
| Puntaje de aprobación | 0–100 |

Los límites de código, descripción, enunciado y explicación son decisiones del cliente (el contrato
solo fija título/contenido/cantidades/opciones); el backend es quien decide en última instancia.

- **Guardar** envía `version.rowVersion`; `409 TRAINING_DRAFT_CONFLICT` conserva la copia local y ofrece
  «Recargar borrador». Tras guardar se adopta el `rowVersion` nuevo.
- **Publicar** guarda primero si hay cambios; `422 TRAINING_DRAFT_INCOMPLETE` se muestra sin perder el borrador.
- **Subir video**: selector de archivo (MP4/WebM; Android SAF, iOS documentos, web `<input type=file>`),
  firma de formato (`ftyp` / EBML), SHA-256 en Kotlin puro, `POST videos/uploads`, PUT firmado
  directo a R2 (sin token BPM, sin cookies, cabeceras filtradas, URL validada como HTTPS
  `*.r2.cloudflarestorage.com`), y `POST videos/{id}/confirm`. Un PUT incierto o un 412 se resuelven
  confirmando la **misma reserva** (mismo `mediaId`); «Reintentar subida» reutiliza el archivo en
  memoria. Subir un video solo asocia el `mediaId` al borrador local: hay que guardar.
- **Límite en el cliente**: el servidor admite hasta 1 GiB (`TRAINING_VIDEO_MAX_BYTES`, 300 MiB por
  defecto), pero el cliente mantiene el archivo en memoria y limita a **100 MiB**
  (`TrainingLimits.CLIENT_VIDEO_MAX_BYTES`). Subir sin cargar el archivo completo (lectura por
  bloques y SHA incremental) queda pendiente.

## Web

`web/training-proxy.mjs` solo expone las rutas del contrato bajo
`/backend/api/v1/organizations/{org}/training/…`, valida IDs, cuerpos (campos exactos y límites),
origen y tipo de contenido, y proyecta respuestas públicas (verifica coherencia del intento con lo
enviado, descarta `correctOption` en la vista de aprendizaje, limita las firmas a HTTPS R2 con
cabeceras permitidas). Sanea errores y solo deja pasar los códigos de dominio que la app distingue.
Los bytes de video nunca pasan por el proxy.

## Supuestos frente al contrato

El contrato no fija el cuerpo de algunas respuestas; se asumió lo siguiente (ajustar si el backend difiere):

- `POST courses` → `{data:{course:{…, status: DRAFT}}}` (el cliente acepta también el curso directo).
- `PUT draft` → `data` con la misma forma que `GET draft` (sin `course` es válido); el cliente adopta el `rowVersion` devuelto.
- `POST publish` → cualquier 2xx; solo se confirma el estado `PUBLISHED`.
- `POST videos/uploads` → `upload.method` es `PUT`; `POST confirm` → `data.file` (o el archivo directamente) con `status` `AVAILABLE` o `FAILED`.
- `PUT draft` envía `version` completo (`id`, `versionNumber`, `passingScore`, `rowVersion`) además de `lessons` y `questions`.

## Pruebas

`commonTest/.../training`: casos de uso con repositorio falso, traducción de DTO (sin respuesta correcta
en aprendizaje), repositorio con `MockEngine` (rutas, errores, subida a R2, reintento tras PUT perdido),
ViewModel de aprendizaje/estudio/evaluación (reintento idempotente, versión desactualizada, resultados
tardíos) y reglas del borrador. `Sha256Test` verifica vectores publicados. `web/training-proxy.test.mjs`
cubre el proxy. No hay pruebas de los reproductores nativos ni de R2 real: requieren dispositivo y bucket.

## Ajustes del backend

- SUPER_ADMIN sin membresía puede leer, ver video y practicar, pero `PUT progress` y `POST attempts` dan 403: el cliente no guarda avance ni ofrece la evaluación para ese rol (`TrainingState.canPersist`).
- El cuerpo del borrador se limita a 1 MiB (el cliente valida ~900 KB) y las opciones repetidas se rechazan (también en el proxy).
- `POST videos/{id}/confirm` se repite (idempotente) con espera creciente (2, 4, 8, 16, 32 s) ante timeout o 5xx hasta ver `AVAILABLE` o `FAILED`.
- Códigos de dominio nuevos (`TRAINING_*_NOT_FOUND`, `TRAINING_VIDEO_CONFLICT/NOT_READY/INVALID`, `STORAGE_UNAVAILABLE`) se traducen a mensajes en español; el replay de un intento devuelve 200 con el resultado original.

## Ampliación: asignaciones, logros, rutas, módulos, YouTube y verificación pública

Contrato: sección «Asignaciones, rutas, insignias y evaluación por módulo» de `Backend/specs/TRAINING.md` (requiere
`V11__training_assignments_paths.sql`; no implica despliegue). El catálogo offline (`me/offline-catalog`) y las descargas
de video **no** se implementan aquí: son de otra entrega.

### Pantallas y flujos

| Quién | Dónde | Qué |
|---|---|---|
| CHEF/WORKER | Aprender (cabecera) | «Mis capacitaciones»: asignaciones con estado (Pendiente, En curso, Completada, Vencida), fecha límite y botón Empezar/Continuar; paginación «Ver más» |
| CHEF/WORKER | `aprender-logros` | «Mis logros»: XP total (cursos, lecciones, insignias) e insignias obtenidas/por lograr |
| Todos los roles de aprendizaje | `aprender-rutas` | Rutas publicadas con avance (%) y detalle con curso siguiente; «Volver» cierra el detalle |
| ADMIN | Empresa › Academia (pestañas) | Cursos · Asignaciones (alta a persona, rol con sede opcional o sede; curso publicado; fecha límite `AAAA-MM-DD` opcional; lista con filtros de estado y curso, paginada, cancelar) · Rutas (crear/editar con `rowVersion`, subir/bajar cursos, publicar, archivar) · Logros (editar nombre, descripción, puntos, umbral y vigencia) |
| Público | `/verificar/{codigo}` | Consulta una constancia sin sesión; muestra válida/revocada, curso, versión, fecha, organización y titular abreviado |

- **Evaluación por módulo** (`attemptType: MODULE` + `lessonId`): botón «Evaluar esta lección» en cada lección con
  preguntas (solo miembros). Solo se responden las preguntas de la lección; mismas garantías de idempotencia y reintento que el
  intento normal; **no** completa el curso ni emite constancia. El XP de módulo lo calcula el servidor y se ve en «Mis logros».
- **Insignias nuevas**: el intento devuelve `achievements`; el resultado muestra una celebración breve (aparece sin animación con
  «reducir movimiento») y se anuncia a lectores de pantalla.
- **Asignaciones**: el `id` lo genera el cliente al primer envío y se conserva en reintentos idénticos; cualquier edición lo renueva
  (evita `IDEMPOTENCY_CONFLICT`). La fecha límite se envía como `AAAA-MM-DDT23:59:00Z` (fin de día en hora universal; el contrato
  exige futura y hasta 5 años). El aviso al destinatario lo genera el worker de alertas (no es instantáneo).
- **Rutas**: 409 `TRAINING_PATH_CONFLICT` conserva la copia local y ofrece «Recargar ruta»; publicar exige guardar antes.
- **Selectores de persona y sede**: los aporta la raíz de la app (`app/TrainingContent.kt`) con las páginas de miembros y sedes del
  workspace; el `id` de miembro se usa como `userId` (supuesto: coincide con el UID de la cuenta).

### Video de YouTube

`lesson.externalVideo = {provider: YOUTUBE, videoId}`; la app arma `https://www.youtube-nocookie.com/embed/{videoId}` y muestra
«Requiere conexión» más «Abrir video en el navegador» (`LocalUriHandler`). `ExternalLessonVideo` es `expect/actual`:

| Plataforma | Implementación |
|---|---|
| Web | `<iframe>` superpuesto (mismo mecanismo que el `<video>`), `sandbox`, `referrerpolicy=strict-origin-when-cross-origin`; la CSP de `web/server.mjs` añade solo `frame-src https://www.youtube-nocookie.com` |
| Android | `WebView` con JavaScript, sin acceso a archivos ni contenido; `shouldOverrideUrlLoading` bloquea todo lo que no sea `https://www.youtube-nocookie.com` |
| iOS | `WKWebView` con `WKNavigationDelegate` que cancela cualquier otro destino |
| JVM | abre el navegador del sistema |

En el editor ADMIN cada lección acepta pegar un enlace de YouTube (`YouTubeLinks.idFromUrl`: watch, youtu.be, shorts y embed) **o**
subir un video; son excluyentes (elegir uno retira el otro). Solo viaja el `videoId`.

### Proxy web

`web/training-proxy.mjs` valida además las rutas `me/assignments`, `me/achievements`, `assignments[/{id}/cancel]`, `achievements[/{id}]`,
`paths[/{id}[/publish|archive]]` (cuerpos exactos, filtros `status/courseId/pageSize/pageToken` sin repetidos, proyección de
respuestas y `pagination`), `attemptType MODULE` con `lessonId`, `achievements` del intento y `externalVideo`. La verificación
`/backend/api/v1/public/training-certificates/{código de 24 caracteres base32}` es solo GET, sin consulta, **sin** `Authorization` ni
cookies, devuelve únicamente los campos públicos y reduce 404/429/5xx a errores sanitizados.

### Pruebas y pendientes

`TrainingPlusDomainTest`, `TrainingPlusViewModelTest` (asignaciones, MODULE, rutas con 409, logros, verificación),
`RemoteTrainingPlusRepositoryTest` (DTO, `externalVideo`, `achievements`) y `web/training-proxy.test.mjs`. Sin verificar en dispositivo:
`ExternalLessonVideo` de Android (WebView) e iOS (WKWebView) y el resto del código nativo (sin Android SDK ni macOS); el embed de
YouTube puede rechazar la reproducción si el origen no envía referrer (por eso el botón de respaldo).
