# Auditoría de experiencia de uso (2026-11-23)

Recorrido de todos los flujos por rol (Personal, Jefe de cocina, Administrador y Superadministrador, este último con el
arnés de capturas) con tres preguntas: ¿lo entiende una persona de cocina sin formación técnica?, ¿la jerarquía y la
distribución de la información son correctas? y ¿el texto es demasiado plano (falta negrita, fichas de estado, conteos,
horas, sedes)? Cada hallazgo indica el problema, la pantalla, la corrección y su estado.

Estados: **Hecho** (en este cambio), **Parcial** (hecho en lo principal, queda un resto descrito) y **Pendiente** (no
se hizo; con motivo). Las capturas se generan con `./gradlew :composeApp:jvmTest --tests '*WorkerScreenshots*'` (390, 768,
1280 y 1920 dp) en `composeApp/build/screenshots/`.

## 1. Principio: nada bloquea por algo que la administración no configuró

Regla aplicada en todo el recorrido: si falta una configuración, la persona igual puede seguir (valores integrados, texto
libre, selección automática cuando hay una sola opción) y, si no puede, el vacío dice qué hacer y, para quien administra,
ofrece el botón que lo arregla.

| # | Problema | Pantalla | Corrección | Estado |
|---|---|---|---|---|
| B1 | «Tu organización todavía no configuró categorías» impedía reportar | Reportar (Personal) | Categorías integradas (Temperatura, Limpieza, Plagas, Equipo dañado, Producto en mal estado, Higiene del personal, Otro problema) siempre visibles, junto a las de la organización sin repetirse; «No sé qué tipo es» lleva a «Otro problema» | Hecho |
| B2 | El servidor exigía un `catalogId` existente | API de incidencias | `POST /incidents` acepta `catalogCode` (categoría integrada); el servidor crea la fila del catálogo de la organización la primera vez. Sin migración (ver sección 5) | Hecho (backend `c4a3e32`) |
| B3 | Reportar eran 3 pasos (tipo, foto, dónde y nota) con textos obligatorios | Reportar | 2 pasos: tipo → «Cuéntanos qué pasó» (texto libre, lugar opcional, casilla de foto). Con elegir el tipo ya se puede enviar; la descripción toma el nombre de la categoría | Hecho |
| B4 | Confirmación sin «qué pasa ahora» | Reporte enviado | Tarjeta «¿Qué pasa ahora?» (aviso al supervisor, asignación, revisión, dónde seguirlo) | Hecho |
| B5 | Jefe de cocina y Administrador no tenían entrada rápida para reportar | Turno (Jefe), Inicio (Admin) | Botón «Reportar un problema» en Turno y en «Acciones rápidas» del Inicio; ruta `reportar` en modo enfoque para cualquier rol que pueda reportar; sede automática si hay una sola | Hecho |
| B6 | Formulario clásico de incidencia pedía «Elegir criterio del catálogo» (vacío sin catálogo) | Incidencias (formulario) | Fichas con las mismas categorías integradas + las de la organización | Hecho |
| B7 | Inicio del administrador sin sedes: texto sin acción | Inicio (Admin) | Botón «Agregar sede» a un toque | Hecho |
| B8 | Personal sin sede asignada: solo texto («Pide a tu administrador…») | Hoy (Personal) | Estado vacío con el tomate y botón «Ir a Aprender» (las lecciones no dependen de la sede). «Reportar» sigue disponible y, sin sede, explica por qué hace falta una y qué hacer en lugar de una pantalla vacía (el servidor ubica el problema en una sede) | Hecho |
| B9 | Controles sin programar (Jefe) y plantillas vacías (Personal/Jefe) dicen «tu administrador…» sin acción | Controles | Para el administrador ya existe «Programar control» en su pantalla; el vacío de Personal/Jefe no puede resolverse por ellos | Parcial |
| B10 | Vacíos de administración (rutas, cursos, criterios) con texto pero sin botón en el propio vacío | Academia, Incidencias | `EmptyState` acepta una acción: «Crear curso», «Crear ruta» y «Crear criterio» dentro del propio vacío | Hecho |

## 2. Hallazgos de la pantalla Hoy (Personal, escritorio 1280–2000 dp)

| # | Problema | Corrección | Estado |
|---|---|---|---|
| H1 | La tarjeta «Buscar en la normativa» era más ancha que la rejilla de dos columnas y quedaba desalineada | Pasa a la columna principal de Hoy (Personal y Jefe) como `NormativeTile`, con el mismo ancho que las demás tarjetas; el acceso genérico de Inicio ya no se dibuja para quien tiene Hoy propio | Hecho |
| H2 | «Sigue aprendiendo» mostraba la barra llena con el módulo sin terminar | El servidor informa 100 % cuando están todas las lecciones vistas aunque falte la evaluación. `assignmentProgress` limita la barra a 95 % hasta que el curso está completado y la tarjeta dice «40 % del curso» o «Viste todas las lecciones. Falta la evaluación final.» | Hecho |
| H3 | «Meta de hoy» valía 0 de 2 en Hoy y 0/1 en Aprender | Una sola definición: Hoy = 1 lección + tareas del turno, con desglose en fichas («Lección hecha», «Tareas 2/7»); Aprender cuenta solo la lección y se llama «Lección de hoy» (sección 4) | Hecho |
| H4 | La píldora «0/2» arriba repetía la tarjeta Meta de hoy | Se quitó de Hoy; arriba quedan solo racha y XP | Hecho |
| H5 | Encabezado «Tu turno · 1 tarea» redundante | «Tu turno» + ficha del turno abierto («TURNO TARDE») y, sin panel lateral, ficha de avance («2 DE 7») | Hecho |
| H6 | La tarjeta derecha «Tu turno» repetía la lista de la izquierda | Ahora «Avance del turno»: barra, «5 tareas por hacer» y ficha roja «1 VENCIDA»; ya no repite la siguiente tarea | Hecho |
| H7 | Inicio de sesión: Enter debe enviar el formulario en web; el mensaje de error desplaza el botón en móvil | **Verificado en vivo que Enter NO enviaba** (solo cerraba el teclado; la primera versión de este informe lo daba por hecho sin probarlo). `BpmTextField` gana `onSubmit` y el último campo de cada modo (contraseña al entrar, confirmación al crear cuenta, correo al recuperar) envía el formulario; el desplazamiento del botón por el mensaje de error se acepta | Hecho (verificar en vivo tras el despliegue) |

## 3. Carga: esqueletos en lugar de barras y «Cargando…»

Componentes nuevos en `core/designsystem/BpmSkeleton.kt`: `BpmSkeleton` (bloque con destello, quieto con «reducir
movimiento»), `BpmSkeletonLine`, `BpmSkeletonCircle`, `BpmSkeletonRow`, `BpmSkeletonList`, `BpmSkeletonCard`,
`BpmSkeletonKpis`, `BpmSkeletonVideo`, `BpmSkeletonDetail` y `BpmSkeletonDashboard`. Cada plantilla anuncia «Cargando…» una
sola vez a los lectores de pantalla (región con `liveRegion`).

Aplicado en: Hoy (tareas), Aprender (camino y curso), Turno del jefe (indicadores y controles), Controles del jefe,
Inicio del administrador y Superadministrador (indicadores y sedes), listados paginados (`BpmPagedList`, selector de
opciones), plantillas y detalle de controles (catálogo, asignación, versiones, historial, ejecución), operación diaria,
incidencias (lista, historial y detalle), alertas y reglas, Academia (cursos, rutas, logros, asignaciones, administración),
reportes y reportes de administración, investigación y encuesta, normativa, constancias, organización, formularios
de empresa, selector de organización, reproductor de video de la lección (`BpmSkeletonVideo`), estado de conexión y
restauración de sesión.

Se conservaron las barras que representan avance real o una acción en curso: avance de curso y metas, subida y descarga de
archivos, envío de evaluación, comprobación de respuesta, guardado/publicación, sincronización y el botón de acceso.
Estado: **Hecho**. Resto: las barras de acción en curso podrían pasar a un indicador dentro del botón (**Pendiente**, no
es carga de contenido).

## 4. Aprender y evaluación (Personal)

| # | Problema | Corrección | Estado |
|---|---|---|---|
| A1 | «Meta de hoy 0/1» en Aprender contradecía Hoy | Se renombra «Lección de hoy» (píldora «0/1 lección» y tarjeta lateral); la meta de Hoy es lección + tareas | Hecho |
| A2 | Lecciones con video: la transcripción estaba oculta y sin lectura en voz alta | La transcripción (derivada de los subtítulos WebVTT que ya guarda el servidor, sin campo nuevo) se abre con un botón y tiene «Escuchar la transcripción» | Hecho |
| A3 | Subtítulos y velocidad | Subtítulos activos por defecto (pista nativa Android/web, superpuestos en iOS). Velocidad y botón de subtítulos: los del reproductor nativo (web `<video controls>`, ExoPlayer, AVPlayer) | Parcial: no verificado en dispositivo |

## 5. Backend: reportar sin catálogo

- `POST /api/v1/organizations/{id}/incidents` acepta `catalogId` **o** `catalogCode` (exactamente uno). Códigos:
  `TEMPERATURA`, `LIMPIEZA`, `PLAGAS`, `EQUIPO_DANADO`, `PRODUCTO_MAL_ESTADO`, `HIGIENE_PERSONAL`, `OTRO`.
- La primera vez que una organización usa un código, el servidor inserta su fila en `incident_catalog` (versión 1, sin
  fotos obligatorias) con `INSERT … ON CONFLICT DO NOTHING`; si ya tenía ese código usa el suyo. Idempotente y seguro con
  reportes simultáneos (probado contra PostgreSQL).
- **No hay migración nueva**: la tabla y sus restricciones no cambian, así que no hay nada que aplicar a mano en la base.
  Sí hay que **desplegar el backend** para que el cliente nuevo pueda enviar `catalogCode`.
- El proxy web (`web/incidents-proxy.mjs`) valida el cuerpo del reporte: ahora acepta `catalogCode` (solo los siete códigos integrados) o `catalogId`, exactamente uno, con prueba.
- Degradación del cliente hasta que el backend esté desplegado: un servidor anterior rechaza el campo desconocido con
  «solicitud inválida»; el cliente muestra el error saneado y, si hay categorías de la organización, sugiere elegir una de
  la lista. Las categorías de la organización (con `catalogId`) siguen funcionando igual.

## 6. Accesibilidad

Sección **Accesibilidad** en Perfil de todos los roles (Personal, Jefe, Administrador y Superadministrador), con lenguaje
simple y guardado en el dispositivo (`FeedbackPreferences`):

| # | Necesidad | Qué se hizo | Estado |
|---|---|---|---|
| X1 | Baja visión: texto más grande | «Tamaño del texto»: Normal / Grande (×1,15) / Muy grande (×1,3), se suma al tamaño del sistema y se aplica a toda la app al instante | Hecho |
| X2 | Baja visión: contraste | «Más contraste»: texto de apoyo, bordes y deshabilitados más oscuros (colores de juego y esquema Material) | Hecho (cubre texto de apoyo y bordes; los colores de estado conservan su tono) |
| X3 | Escuchar (voz en voz alta) | `BpmListenButton` + `TextSpeaker` por plataforma: Android `TextToSpeech`, iOS `AVSpeechSynthesizer`, web `speechSynthesis`; se oculta sin soporte o si se apaga «Escuchar». En ideas de «Lo esencial», lectura de la lección, preguntas (con sus opciones) y transcripción | Hecho (no probado en dispositivos físicos) |
| X4 | Sordera / poca audición | Todo sonido ya tenía equivalente visual (franja de respuesta con título y explicación, celebración con texto); el aviso de Sonido lo dice. Vibración opcional | Hecho |
| X5 | Movimiento reducido | Confeti, celebración, camino, franja de respuesta, aparición y ahora el destello de los esqueletos lo respetan (`rememberReduceMotion`) | Hecho |
| X6 | Lectores de pantalla y 48 dp | Regiones de carga con descripción; botón «Escuchar» ≥ 48 dp; interruptores de 56 dp con estado en palabras («Activado»); filas de datos (`BpmDataRow`) leídas como una frase (nombre, detalle, cifra, estado); filas de ajustes y enlaces de 48 dp | Parcial: no se revisó uno por uno cada icono de las pantallas heredadas con TalkBack/VoiceOver |
| X7 | No depender solo del color | Estados con ficha de texto («1 VENCIDA», «LECCIÓN HECHA», «TAREAS 2/7»); respuestas con rótulo; las filas de administración con tono de error y sin ficha ahora llevan icono y «REQUIERE ATENCIÓN» | Hecho |
| X8 | Texto «Muy grande» en cajas de alto fijo | Banda del curso y número de opción sin alto fijo; títulos de fila de tareas, controles y tarea rápida duplican sus líneas a ×1,3 (`bpmTitleLines`); verificado con la captura `hoy-texto-grande` | Hecho en Hoy, Reportar y Ajustes; el resto de pantallas heredadas sin captura |

## 7. Texto plano → énfasis y fichas

| # | Dónde | Cambio | Estado |
|---|---|---|---|
| T1 | Hoy: meta, turno, vencidas | Fichas con conteo y estado (3 DE 8, TURNO TARDE, 1 VENCIDA) | Hecho |
| T2 | Sigue aprendiendo | Porcentaje y mensaje de evaluación pendiente | Hecho |
| T3 | Reportar | Ficha con el tipo elegido en «Cuéntanos qué pasó» | Hecho |
| T4 | Pantallas de administración heredadas (Material) | Muchas listas siguen en texto plano con `BpmStatusChip` solo en algunas filas. Los detalles ya usan fichas y datos clave (sección 9) | Parcial |

## 8. Otros hallazgos sin resolver

- Verificar visualmente Turno del jefe y Controles con el escalado «Muy grande».
- `BpmSkeleton*` usan un destello continuo; en pantallas con muchas regiones simultáneas podría limitarse a una sola
  animación compartida (rendimiento en Android antiguos).
- Los avisos de «sin conexión» y «Requiere conexión» de video no tienen aún acción alternativa a un toque.

## 9. Fase 2: una anatomía, Ajustes, accesibilidad total y encuesta del estudio

### 9.1 Plantillas de detalle, ajustes y configuración (`core/designsystem/BpmScaffolds.kt`)

- **`BpmDetailScaffold`**: «Volver» · antetítulo · título · fichas de estado (`BpmDetailChip`, siempre texto) · datos clave
  (`BpmDetailFact`, leídos como «rótulo: valor») · acciones del registro (un botón principal y secundarios, pasan de línea
  sin cortarse) · secciones · acción principal fija al pie en compacta (`primaryAction`, vía `BpmActionBar`). Mide hasta
  720 dp y se alinea a la izquierda; dentro de lista–detalle se adapta al panel.
- **`BpmSettingsScaffold`** + `BpmSettingsSection` + filas `BpmSettingToggle` (SÍ/NO además del interruptor y
  «Activado/Desactivado» al lector), `BpmSettingChoice` y `BpmSettingLink`. **`BpmFormScaffold`** para formularios de
  configuración (título, descripción y acciones al pie).
- **Migración**: `BpmDetailPane` (ya usado por detalle de control, historial, asignación, equipo, incidencia, organización y
  operaciones) ahora es un envoltorio de `BpmDetailScaffold`, así que **todos esos detalles pasan a la misma anatomía** sin
  tocar cada pantalla. Detalle de incidencia: antetítulo con el área, fichas de estado y de riesgo, datos clave (quién
  reportó, fecha, fotos) y «Escuchar el reporte». Detalle de control: datos clave (preguntas, obligatorias, con evidencia)
  y «Escuchar las preguntas». **Ajustes** es la primera pantalla construida directamente sobre `BpmSettingsScaffold`.
- **Pendiente**: los formularios de alta/edición de empresa, sedes y equipo siguen en `BpmFormLayout` (que ya cumple el
  patrón de formulario de `specs/LAYOUT.md`); migrarlos a `BpmFormScaffold` queda para una pasada propia porque su
  validación y borradores están acoplados al layout. La guía del curso y el detalle de organización del superadministrador
  usan sus propios paneles de juego.

### 9.2 Perfil y Ajustes separados

- **Perfil** muestra solo quién eres: identidad, racha/insignias/constancias (Personal y Jefe) y rol. Una tarjeta lleva a
  «Ajustes» y a «Encuesta del estudio».
- **Ajustes** (ruta `ajustes`, engranaje en la cabecera y entrada desde Perfil, igual en Personal, Jefe, Administrador y
  Superadministrador): *Accesibilidad* (tamaño del texto, más contraste, Escuchar, subtítulos, transcripción siempre abierta,
  reducir movimiento), *Sonido y vibración*, *Avisos* (a «Mis avisos») y *Cuenta* (dispositivos, invitación, cerrar sesión).
  No hay selector de idioma: la app solo está en español. Todo se guarda en el dispositivo (`FeedbackPreferences`).

### 9.3 Accesibilidad visual y auditiva

- «Escuchar» ahora también está en: preguntas de práctica y de evaluación (con sus opciones), pasos de un control,
  guía del curso, tarea rápida, reporte (opciones y paso «Cuéntanos qué pasó»), consentimiento y preguntas de la encuesta,
  detalle de incidencia y de control, y la transcripción («Escuchar resumen»).
- Video: subtítulos encendidos según el ajuste, transcripción siempre abierta si se pide y modo **«Solo audio y texto»**
  (oculta el video, abre la transcripción —o el texto de la lección si no hay subtítulos— y la lee en voz alta).
- «Reducir movimiento» de Ajustes se suma al del sistema (`rememberReduceMotion`): esqueletos, confeti, celebraciones y
  camino lo respetan.
- **Por verificar en dispositivo**: velocidad de reproducción y botón de subtítulos dependen de los controles nativos de
  cada reproductor; la voz en español depende de las voces instaladas en el teléfono.
- **No se hizo**: botón flotante «Escuchar esta pantalla» que lea cualquier pantalla (Compose no expone de forma portable el
  árbol de semántica para extraer texto); se cubrieron las pantallas con texto significativo con botones propios.

### 9.4 Encuesta del estudio (cuestionario CAP)

Definición (Requerimientos/ResumenProyectoBPM.md §8.1 y el instrumento «Encuesta estructurada pre y post uso del
aplicativo»): conocimientos (8 de opción múltiple), actitudes (8 Likert) y prácticas (10 de frecuencia), para
manipuladores, cocineros, supervisores y responsables; **pretest antes de implementar el aplicativo y postest al
finalizar el pilotaje (3 meses)**; consentimiento informado voluntario y retirable; una sola entrega por momento.

Lo que ya existía (backend V9 y cliente): consentimiento, instrumento versionado, entrega idempotente, sin puntajes al
participante, exportación anonimizada. **No faltaba backend.** Lo que faltaba era la integración en el recorrido:

- **Invitación en el flujo** (`studyPrompt`, `StudyPromptCard`): «Antes de empezar» (consentimiento) → «Encuesta inicial»
  → «Encuesta final» cuando pasan 90 días desde la inicial (`PILOT_DAYS`, calculado con la fecha de entrega que ya guarda el
  servidor). Aparece arriba de Hoy (Personal), Turno (Jefe), Inicio (Administrador) y Aprender; «Ahora no» la deja para
  después en la sesión; nunca bloquea nada; no se muestra a quien retiró su consentimiento ni a la super administración.
  El enlace permanente «Encuesta del estudio» vive en Perfil.
- **Estilo de juego y modo enfoque** (`SurveyScreen` reescrita): resumen con el tomate y fichas «Al empezar / Al terminar» →
  consentimiento con «Acepto participar» / «Ahora no» → una pregunta por pantalla con progreso, opciones numeradas,
  atajos 1–9, «Escuchar la pregunta» → gracias. Sin puntajes ni respuestas correctas.
- **Retomable**: las respuestas a medias se guardan en el dispositivo (`LocalSurveyDraftStore`, solo ids de pregunta y valores)
  y se retoman en la primera sin responder; se borran al enviar, al retirar el consentimiento y con el cierre de sesión.
  Antes se perdían al salir de la pantalla.
- **Anónima y voluntaria**: código seudónimo, consentimiento solo con conexión (acto legal), respuestas sin conexión por la
  Outbox. Salir de la encuesta ya no reinicia la participación (`leave`), para que la invitación siga correcta.
- Pruebas: `StudyPromptTest` (cuándo toca cada paso, retiro respetado, versión distinta del instrumento) y borrador en
  `SurveyViewModelTest`.
- **Decisión pendiente de la investigadora**: la fecha de fin de piloto se infiere (90 días desde la inicial); si el estudio
  necesita una fecha fija por empresa, hay que agregar un campo en el backend (no existe hoy). También hay que publicar en
  producción el consentimiento y el cuestionario CAP: sin ellos la invitación no se muestra.

## 10. Navegación, caché y esqueletos por resolución (reporte urgente)

- **Causa raíz de «los datos no cargan al navegar»** (reproducida en vivo con Playwright: Turno del Jefe → «Insumos y limpieza» → Turno
  dejaba «Tareas del turno: Sin datos»): `DailyOperationsContent` e `IncidentsContent` reiniciaban en `onDispose` los ViewModels
  **compartidos** (`operations`, `shifts`, `stock`, `incidents`) que también usan Hoy, Tareas y Turno. La pantalla saliente se retira
  cuando termina la transición, o sea **después** de que la de destino ya pidió y recibió sus datos; el reinicio le vaciaba el estado y
  nada volvía a cargar. Corrección: esas pantallas ya no reinician al salir (lo hace el cierre de sesión); `open()` reorienta el
  ViewModel si cambian persona, empresa, rol o sede. Regresión: `architecture.test.mjs` falla si una pantalla reinicia un ViewModel
  compartido en `onDispose`.
- **Caché al volver** (stale-while-revalidate): al conservarse el estado de los ViewModels, volver a una pantalla muestra lo cargado
  al instante y se revalida en segundo plano (`refreshIfStale`, `refreshToday`…). El esqueleto queda solo para la **primera carga sin
  datos**: se cambiaron las condiciones `loading` a `loading && sin datos` (operación, turnos, stock, avisos, incidencias, reportes,
  Academia, historial) y el historial y los controles programados ya no vacían la lista mientras recargan.
  Límite: es memoria del proceso (se pierde al cerrar la app o la pestaña) y la invalidación tras una mutación sigue siendo la de
  cada ViewModel; no se agregó una caché genérica por ruta.
- **Esqueletos por resolución**: `BpmSkeletonScreen` usa el mismo `BpmPlayColumns` (columna de 640/600 + panel de 330, grupo centrado
  de 1120), `BpmSkeletonGrid` usa `BpmGridLayout` (1/2/3 columnas), `BpmSkeletonListDetail` usa `BpmListDetail` (detalle solo si
  cabe junto a la lista) y `BpmSkeletonKpis` reparte 2×2 en compacta y una fila en expandida. Verificado en las capturas a 390, 768,
  1280 y 1920 (`esqueleto-hoy`, `esqueleto-rejilla`, `esqueleto-lista-detalle`).
- **Hallazgo aparte (no es de navegación)**: el Jefe de cocina ve «Aún no hay personas» en Equipo y Turno porque el servidor solo envía
  la lista de miembros a quien puede administrar (`organizations/adapters/postgres/pages.go`, `CanManage`); hace falta un endpoint o
  filtro por sede para el Jefe. Pendiente de backend.

## 11. Primeros pasos y Ayuda (todos los roles)

- **Ayuda («?» en la cabecera de todas las pantallas con cabecera)**: abre `ayuda` con el tema de la pantalla de origen
  (`helpTopicForRoute`): para qué sirve, cómo usarla en 2–4 pasos, «Palabras que verás» (BPM, sede, control, turno, incidencia,
  evidencia, racha, constancia…, filtradas por rol), «Ver primeros pasos otra vez» y «¿Necesitas más ayuda?» (a tu jefe o
  administrador; no hay un correo o teléfono de soporte definido en los requerimientos, así que no se inventó uno). Mismo
  andamio de detalle, con «Escuchar esta ayuda». En modo enfoque (lección, reporte, encuesta, control paso a paso) la pantalla
  ya trae su propia X y no muestra «?»: la ayuda de esos flujos se abre desde la pantalla de la que se entró.
- **Introducción de la primera vez** (`primeros-pasos`, modo enfoque): 4–5 tarjetas con el tomate por rol (Personal, Jefe,
  Administrador y Superadministrador), español simple, «Saltar» o la X, puntos de avance, «Escuchar» en cada una y Enter/→
  para seguir. Sale una sola vez al llegar a Inicio; se repite desde Ayuda.
- **Lista «Primeros pasos»** (tarjeta arriba de Hoy, Turno, Inicio y Organizaciones): Personal (primera lección, primera tarea,
  cómo reportar), Jefe (revisar el turno, primer control, mirar al equipo, aprender), Administrador (crear sede, invitar al
  equipo, programar el primer control, asignar un curso) y Superadministrador (abrir organización, ver indicadores). Los pasos
  que se pueden saber con datos reales se marcan solos (sede creada, más de un miembro, controles programados, XP > 0, tarea
  hecha hoy); el resto se marca al abrirlos. Cada paso lleva a donde se hace; «Ocultar por ahora» la quita y Ayuda la vuelve a
  mostrar.
- **Persistencia**: por persona **en este dispositivo** (`OnboardingPreferences` sobre el `LocalStore` de la cuenta, como las
  preferencias de accesibilidad). No hay endpoint en el backend para guardarlo por usuario; una cuenta que entra en otro teléfono
  vuelve a ver la introducción (se salta en un toque). Si se necesita por usuario, hay que agregar un campo de preferencias en el
  backend.
- Pruebas: `OnboardingRulesTest` (tarjetas por rol, pasos automáticos, ayuda por ruta, persistencia).

## 12. Pasada de uso en vivo: Jefe de cocina y Administrador (appbpm.com, organización «Prueba BPM»)

Hecha con Playwright a 1280 y 390 dp y con el arnés de capturas. Solo se leyó y se navegó; no se enviaron formularios que creen datos
(la organización de prueba ya tenía una revisión «En curso» de Ana). Capturas en `scratchpad/pw/e2e-*.png` (no se versionan).

| # | Rol · pantalla | Qué se probó | Hallazgo | Corrección | Estado |
|---|---|---|---|---|---|
| E1 | Jefe · Turno → «VER» un control | Abrir el único control de hoy | Abre el «Detalle de revisión» **de otra persona** (Ana), de solo lectura, con «Mis revisiones» vacío al lado: parece un error y no hay cómo hacer uno propio | Se dejó el detalle (es lo correcto para un control ajeno) y se agregó el camino claro de E2 | Parcial |
| E2 | Jefe · Controles | Hacer un control propio | Eran **5 toques** y el acceso se llamaba «Otras plantillas» (Controles → Otras plantillas → Abrir plantilla → Preparar revisión → sede) | Botón principal «Hacer un control ahora» arriba y el acceso se renombra «Empezar otro control» (elige una plantilla y complétala en tu sede) | Hecho |
| E3 | Admin · Controles → «Programar control» | Programar un control | 4 toques (Programar control → Abrir plantilla → Programar en una sede → formulario) y la pantalla intermedia decía «Plantillas de control» con el texto fijo «Crea una primera versión…» aunque ya hubiera plantillas | Texto según el estado («Elige una plantilla para programarla en una sede, o crea una nueva» / «Aún no tienes plantillas…») y «Crear plantilla» pasa a secundario cuando ya hay plantillas | Hecho |
| E4 | Admin · Plantilla | Detalle de plantilla | Ya usa la anatomía única: antetítulo «CONTROL», ficha de versión, datos clave (preguntas, obligatorias, con evidencia), acción principal «Programar en una sede», «Escuchar las preguntas» | — | Hecho |
| E5 | Admin · Empresa | Datos y sedes | Las tarjetas de «Gestión» mezclan iconos presentes y espacios en blanco | Ver E7 | Pendiente |
| E6 | Jefe · Equipo / Turno | Ver al equipo | «Aún no hay personas para mostrar» aunque la sede tiene personal: el servidor solo entrega la lista de miembros a quien puede administrar | Requiere un endpoint o filtro por sede para el Jefe en el backend | Pendiente (backend) |
| E7 | Todos · iconos | Cambiar de pantalla varias veces | Algunas capturas muestran huecos donde van iconos (Equipo, Academia, campana, rueda, «Insumos y limpieza»…); no es reproducible a voluntad y los recursos no devuelven errores HTTP, así que parece una carga de recursos de Compose para web que no termina | Sin causa confirmada; conviene precargar los iconos del shell al arrancar | Pendiente |
| E8 | Todos · acceso | Iniciar sesión desde un headless | A veces la llamada de acceso se cancela y aparece «La conexión tardó demasiado»; reintentar funciona | No es de la interfaz; revisar el tiempo máximo de la llamada de acceso | Pendiente |
| E9 | Admin · Invitar persona, Academia → asignar curso, Seguimiento | — | **No se recorrieron de punta a punta** en vivo por el límite de tiempo (la tarea de invitar y asignar crea datos y requiere el correo real) | — | Pendiente |

### 11.1 Verificación en vivo (appbpm.com, organización «Prueba BPM»)

- **Enter envía el acceso** en la web (probado a 390 y 1280 dp con Personal, Jefe y Administrador).
- **Introducción** de la primera vez (5 tarjetas, «Escuchar», «Saltar»): aparece al entrar y lleva a Inicio al terminar.
- **Primeros pasos**: Personal «1 de 3» (la tarea de hoy se marcó sola), Administrador «3 de 4» (sede, equipo y control programado detectados
  con datos reales; falta «Asigna un curso»).
- **Ayuda («?»)**: abre «Ayuda: Inicio» con el rol, pasos, glosario y «Ver primeros pasos otra vez»; se confirmó en Administrador a 1280 dp.
  En teléfono el «?» está entre la campana y la rueda.
- **Navegación**: Turno del Jefe → Insumos y limpieza → Turno ya conserva «Tareas del turno 1/1» (antes «Sin datos»).
- Notas de la verificación: el acceso a veces tarda más de 10 s o se cancela (E8), y la lista «Primeros pasos» en teléfono pierde la ficha
  «Pendiente» (el icono de meta y el lector de pantalla la sustituyen).

## 13. Segunda pasada en vivo (Jefe y Administrador) y arreglos de E6–E8

Pasada con Playwright a 1280 dp (y 390 dp en Personal) en «Prueba BPM». Se completó **de punta a punta, como Jefe, un control**: Controles → Empezar otro
control → plantilla → Preparar revisión → sede → dos preguntas → Revisar → «Confirmar y finalizar» → «¡Control finalizado! 100 %» (queda un registro de
prueba en esa organización). Se abrieron también, como Administrador, Equipo, Invitaciones (asistente de 3 pasos), Academia, asignaciones y Sedes.

| # | Dónde | Hallazgo | Corrección | Estado |
|---|---|---|---|---|
| E6 | Backend · workspace del Jefe | El Jefe no recibía miembros: «Aún no hay personas» | `TeamPage`: personas activas (Personal y otros Jefes) que comparten SUS sedes, sin correo, solo lectura; prueba que otra sede y los administradores no aparecen. Verificado en vivo: el Jefe ve a Pedro | Hecho (backend d429811) |
| E7 | Todos · iconos en blanco | Intermitente; los 33 recursos devuelven 200 y no hay fallos de red, así que no es un error HTTP. Hipótesis: con `Cache-Control: no-cache` cada pantalla revalidaba decenas de iconos; una petición lenta deja el icono en blanco y la carga no se reintenta | `/composeResources/` con caché de 1 h y revalidación en segundo plano (prueba en `static-assets.test.mjs`). **Causa raíz no confirmada**; sigue visible a veces (ver Equipo del Jefe: faltaban Controles y Aprender) | Mitigado, no resuelto |
| E8 | Acceso | El tope general de 12 s cancelaba el acceso en una red o un servidor lentos («La conexión tardó demasiado»); el arranque en frío del servidor lo agrava | Acceso, renovación, configuración pública y lectura del perfil: 30 s por llamada y hasta 2 reintentos (600 ms y 1,5 s) solo en llamadas seguras de repetir (no al crear cuenta ni enviar correos); mensaje «La conexión está lenta y no pudimos terminar el acceso…». Prueba `IdentityNetworkTest` | Hecho |
| E1 | Jefe · revisión ajena en curso | Detalle de solo lectura que parecía un error | Ficha «En curso por <nombre>», explicación y botón «Hacer mi propio control» | Hecho |
| E9 | Jefe · «Revisión de tu sede» | Con una sola sede había que elegirla con un radio antes de poder pulsar «Comenzar revisión»; el texto decía «El chef registra aquí…» | La única sede queda elegida sola; texto en segunda persona | Hecho |
| E10 | Jefe · comenzar un control ya programado | El servidor rechaza un control libre si la plantilla está programada para la sede y el aviso (en rojo, tras pulsar) no decía qué hacer | Mensaje claro («Este control ya está programado para tu sede. Ábrelo desde «Controles de hoy»…»). Falta avisar **antes** de pulsar y llevar directo al control programado | Parcial |
| E11 | Admin · «Invitar persona» | Abría «Cuentas y equipo» en la pestaña Equipo: 2 toques más hasta el formulario | Abre la pestaña Invitaciones (botón «Invitar por correo» → asistente de 3 pasos) | Hecho |
| E12 | Admin · «Asignar curso» (Inicio) | Abría «Cursos de Academia» (edición de cursos) en lugar de las asignaciones | Abre la pestaña Asignaciones | Hecho |
| E13 | Admin · dos pantallas de equipo | «Equipo» (lista–detalle) y «Cuentas y equipo» (tarjetas) conviven con nombres parecidos | Sin cambio: unificarlas es un rediseño propio | Pendiente |
| E14 | Todos · carga inicial | El esqueleto aparecía sobre el título «Hola, …» | El título no se dibuja mientras el espacio carga | Hecho |
| F1 | Formularios | Migrar los formularios restantes a `BpmFormScaffold` | **No se migraron**: `BpmFormLayout` ya cumple la anatomía de formulario (columna de 720 dp, secciones, acciones al final) y sus títulos los pone el shell; `BpmFormScaffold` queda disponible para formularios nuevos | Pendiente |
| M1 | 390 dp Jefe y Administrador | No se recorrieron en vivo (solo Personal a 390) | — | Pendiente |

## 14. Pasada móvil (390 y 768 dp) Jefe y Administrador, acciones reales en «Prueba BPM»

Barrido con Playwright a 390 y 768 dp: Jefe (Turno, Controles, Equipo, Aprender, Perfil, Ajustes, Ayuda, Reportar) y Administrador (Inicio, Controles,
Equipo, Academia, Seguimiento, Incidencias, Empresa, Ajustes, Ayuda, Perfil). Acciones reales hechas en la organización de prueba: **se envió una
invitación** a `bpm-invitado-fd3760@yopmail.com` (asistente de 3 pasos → enlace listo para copiar/compartir), **se asignó el Módulo 1 a Pedro** (asistente de 3 pasos).
La edición y reversión de un campo de sede **no pudo hacerse porque «Editar sede» cerraba la app** (ver H3).

| # | Dónde | Hallazgo | Corrección | Estado |
|---|---|---|---|---|
| H1 | Control · «Comenzar revisión» | Se avisaba del control ya programado solo DESPUÉS de pulsar y con un error | Al elegir la sede, si la plantilla ya está programada y vigente allí, aparece el aviso y «Abrir el control programado» (y «Comenzar revisión» queda secundario y desactivado). `ScheduledControls`/`scheduledFor` | Hecho (sin probar en vivo: no hay un control de ese tipo sin hacer en la organización) |
| H2 | Admin · dos pantallas de equipo | «Equipo» y «Cuentas y equipo» duplicadas | Una sola: «Equipo» del administrador muestra pestañas Equipo · Invitaciones · Organización y sedes; «Invitar persona», «Gestionar» y «Editar datos/sede» abren la pestaña que toca | Hecho |
| H3 | Admin · «Editar sede/datos» | **Cerraba la app** (`Vertically scrollable component was measured with an infinity maximum height`): la unificación (H2, commit 3869722, ya desplegado) alojó la pantalla de cuentas, que desplaza en vertical, dentro de un espacio que ya desplaza | `scrollable = false` en esa ranura + prueba de arquitectura que lo exige. Hubo una ventana en producción con el fallo hasta el despliegue de 01ce36f | Hecho |
| H4 | Admin · Invitar | Debajo del asistente seguía la lista de invitaciones; con una sola sede había que marcarla a mano antes de «Enviar invitación» | La lista se oculta mientras hay formulario o enlace; la sede única queda marcada | Hecho |
| H5 | Admin · Incidencias y operación diaria | Con una sola sede preguntaban «¿En qué sede trabajarás?» (solo Personal la saltaba) | Cualquier rol con una sola sede la usa sin preguntar | Hecho |
| H6 | Inicio (Jefe/Admin) en teléfono | «Primeros pasos» ocupaba media pantalla | En teléfono solo se listan los pasos pendientes (los hechos cuentan en «3 de 4») | Hecho |
| H7 | Jefe · Primeros pasos | «Haz tu primer control» no se marcaba aunque ya había uno finalizado | Se cuenta con las revisiones finalizadas propias | Hecho |
| H8 | Admin · Asignar curso | Aviso «verán en «Mis capacitaciones»» (esa pantalla se llama «Aprender») | Texto corregido | Hecho |
| H9 | Admin · Asignar curso, paso 3 | La fecha límite se escribe a mano como AAAA-MM-DD | Sin selector de fecha; queda anotado | Pendiente |
| H10 | Admin · Equipo en teléfono | Las pestañas se reacomodan al cambiar la marcada (el ancho del «✓» cambia el salto de línea), así el segundo toque cae en otro lugar | Sin cambio | Pendiente |
| H11 | 768 dp (Jefe) | Turno, Controles y Reportar se ven bien: columna centrada de 640 dp y barra inferior | — | Verificado |

### 14.1 Verificación en vivo tras el despliegue de 7fbfa88 y acciones reales

- «Editar organización/sede» ya no cierra la app (se confirmó a 390 dp). **Se editó el teléfono de la «Sede principal» a 999000111, se guardó y se revirtió
  a vacío** (la ficha vuelve a mostrar solo dirección y zona horaria).
- Invitación real enviada a `bpm-invitado-fd3760@yopmail.com` (rol Colaborador, Sede principal) y asignación del Módulo 1 a Pedro: ambas aparecen en sus listas.
- **Incidencia de proceso**: el despliegue de 01ce36f quedó en «failure» porque el trabajo de iOS del CI se canceló solo; producción siguió con el
  fallo de H3 más de 1 h hasta que un commit nuevo (7fbfa88) relanzó CI y despliegue. Conviene que el despliegue no dependa de un trabajo cancelado por la infraestructura.
- H12 (nuevo): el filtro de estados de incidencias mostraba dos chips «Validada» (estados `CLOSED` y `VERIFIED`); el servidor cierra con `CLOSED`, se quitó `VERIFIED`.

## 15. Cierre de pendientes (selector de fecha, equipo, pestañas) y verificación

| # | Pendiente | Resultado | Estado |
|---|---|---|---|
| P1 | Barrido del Administrador a 768 dp y revisión a 1280 dp tras unificar el equipo | Inicio, Controles, Equipo (2 columnas de tarjetas), Empresa, Aprender, Seguimiento, Incidencias, Ajustes, Ayuda y Perfil se ven bien a 768; Equipo a 1280 con pestañas Equipo · Invitaciones · Organización y sedes | Hecho |
| P2 | «Asignar curso» desde la persona | Acción «Asignar curso» en la tarjeta de cada persona activa de Equipo: abre el asistente de Academia con esa persona ya elegida (`startCreateFor`) | Hecho |
| P3 | Selector de fecha | `BpmDatePicker` + `BpmCalendar` en el sistema de diseño: campo que muestra la fecha en palabras, calendario en línea (sin ventana emergente, igual en Android, iOS y web), atajos Hoy/Mañana/En 1 semana, mínimo, «Quitar fecha», días ≥ 44 dp con fecha completa y estado («Elegido», «No disponible», «hoy») para lector de pantalla, flechas de mes de 48 dp. Pruebas de calendario (`BpmCalendarTest`: validez, bisiestos, día de la semana, sumar días). Usado en: fecha límite de asignación de curso, vencimiento de lote de insumos y fecha límite de incidencia (la hora sigue siendo un campo HH:mm) | Hecho |
| P4 | Pestañas de Equipo estables | Equipo usa `BpmSegmentedTabs` (un tercio de ancho cada una); las pestañas de Academia reservan el sitio del ✓ (`reserveCheck`) | Hecho |
| P5a | Chip único «Validada» | Verificado en vivo a 768 dp tras el despliegue de 23f51c8 | Hecho |
| P5b | Aviso de control ya programado | Verificado en vivo (Jefe, plantilla ya programada en la sede): aparece «Este control ya está programado…» con «Abrir el control programado» y «Comenzar revisión» desactivado. Al probar el botón se vio que llevaba a la ejecución de una revisión ajena y la app volvía al acceso; ahora abre la revisión (solo lectura si es de otra persona, ejecución si es propia) | Hecho |
| P6 | Despliegue de 23f51c8 | El despliegue terminó en `success` (23:07 UTC) | Hecho |
| P7 | Selector de hora | La hora límite de una incidencia sigue escribiéndose HH:mm | Pendiente |
