# Plan: Personal con estilo de app de aprendizaje con juego (propuesta 2)

Fuente visual obligatoria: `/Users/helcorp/Downloads/Proyectos/BPM/Documentacion/Propuestas/personal-v2/index.html`
(maquetas 390 × 800 y 1280 × 780, sección 5 «Sistema visual»). Ilustraciones del tomate en
`/Users/helcorp/Downloads/Proyectos/BPM/Requerimientos/bpm-design-system/assets/*.png` (ya hay `mascot_*` en drawable).
Rama `feat/personal-juego`. Sin commit ni push.

## Reglas para todos los paquetes

- Solo el rol **WORKER** cambia de aspecto y de flujo. ADMIN/CHEF/SUPER_ADMIN siguen igual (salvo componentes nuevos que no usen).
- Leer `AGENTS.md`, `specs/LAYOUT.md` y las skills del repo en `.claude/skills/bpm-spacing` y `bpm-motion`. `node scripts/check-spacing.mjs --strict` debe quedar en 0 errores y 0 avisos
  (si una regla choca con el estilo de juego, anotar `// patrón: motivo` como permite el script; nunca `--update-baseline`).
- No inventar datos: usar lo que ya existe (racha, XP, insignias, avance por lección, keyPoints, tareas `view=today`, modo estudio, catálogo de incidencias).
  - **Meta diaria** (sin backend): 1 lección del día (`streak.activeToday`) + tareas del turno hechas/total (`view=today`). Función pura `dailyGoal(...)` con pruebas.
  - **Avance del turno**: hechas/total de `view=today` de la sede.
  - **Icono de categoría al reportar** (sin backend): función pura que asigna icono por palabras clave del nombre de la categoría
    (temperatura, plaga, equipo/malogr, insumo/producto, agua/desag, limpieza; resto «Otro») con pruebas.
- Responsivo con `BpmWindowClass`: Compact/Medium = diseño de teléfono centrado (máx. 480 dp de ancho en Medium);
  Expanded/Large = menú lateral grande + columna central de 600 dp + panel derecho de 330 dp (racha, meta, turno, próxima insignia).
- Modo enfoque (ya existe) para lección, práctica, evaluación, revisión, resultado, registro de tarea y reporte: sin navegación.
- Cada paquete corre `./gradlew :composeApp:jvmTest --tests '*WorkerScreenshots*'`, **mira** (Read) los `sheet-*.png` que tocó y corrige hasta 3 vueltas.
  Añadir al generador las pantallas nuevas (camino, idea, pregunta-correcta, pregunta-incorrecta, fin-leccion, tarea-paso1, tarea-foto, tarea-lista, reportar, perfil).
- Verificación final de cada paquete: check-spacing, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`. Añadir «## Estado Jn» al final de este archivo.

## J1 · Base visual y navegación

- Fuentes **Baloo 2** (títulos, 700/800) y **Nunito** (texto, 600/700/800/900) empaquetadas en `composeResources/font` (OFL, descargar los TTF estáticos de
  `https://github.com/google/fonts` raw `ofl/baloo2` y `ofl/nunito`; si la descarga no está disponible, usar las variables y documentarlo). Solo para pantallas de Personal:
  exponer `BpmPlayType` (h1 26/28 sp Baloo 800, h2 17 sp Nunito 900, body 15 sp Nunito 700, label mayúsculas 13 sp Nunito 900 con tracking).
- Colores de juego en `BpmTokens`/objeto nuevo `BpmPlayColors`: acción `#005B59`/sombra `#003D3C`/suave `#E3F1EE`; correcto `#2E9E5B`/`#1E7442`/`#E2F5E8`;
  error `#E04B3F`/`#AD3027`/`#FDE7E4`; logro `#F5B700`/`#C48F00`/`#FFF4CC`; racha/reportar `#ED5A3B`/`#B83E24`/`#FFE6DF`; bloqueado `#E5EAEA`/`#C7CFCF`.
- Componentes (core/designsystem, genéricos, con KDoc en español):
  - `BpmChunkyButton(text, onClick, tone = Action|Correct|Error|Report|White|Disabled, icon?, modifier)`: 52 dp, mayúsculas, relieve inferior de 4 dp que se hunde al presionar.
  - `BpmOptionTile(text, key: String?, state = Idle|Selected|Correct|Wrong, onClick)` ≥ 62 dp; `BpmChoiceTile(text, icon, tone, selected, onClick)` (tiles con icono grande para tareas/reportes).
  - `BpmMissionRow(title, detail, icon, tone, done, late, onCheck, onOpen)` con círculo de 44 dp a la derecha.
  - `BpmStatPill(icon, value, tone)` (racha, XP, meta), `BpmChunkyProgress(progress, tone)` 16 dp con brillo, `BpmStepDots(total, current)`.
  - `BpmFeedbackSheet(correct: Boolean, title, explanation, actionLabel, onAction)` que sube desde abajo (animación corta, respeta reducción de movimiento).
  - `BpmCelebration(image, title, subtitle, stats: List<BpmStatTileSpec>)` con fichas de color (XP dorado, racha coral, aciertos verde).
  - `BpmSpeechBubble(text)` + `BpmMascotRow(image, text)`.
  - `BpmPathNode(state = Done|Current|Locked|Trophy, onClick)` y `BpmLearningPath(nodes, modifier)` en zigzag (offsets −60/0/+60 dp), con burbuja «Empezar» sobre el actual sin taparlo.
  - `BpmUnitBanner(kicker, title, color, onGuide)`.
- Navegación WORKER: 4 destinos **Hoy · Aprender · Tareas · Perfil** (Perfil = Account con contenido nuevo en J4). Barra inferior con iconos de 26 dp, etiqueta en mayúsculas,
  seleccionado con fondo suave y borde. Expanded+: menú lateral grande (230 dp) con los mismos 4. Actualizar `WorkspaceNavigationTest`.
- Arreglar las tarjetas gigantes/vacías del teléfono (Asignadas/catálogo) — se reemplazan en J2, pero ningún componente debe estirarse en alto.

## J2 · Aprender

- **Camino** (pantalla Aprender de WORKER): fila de `BpmStatPill` (racha, XP, meta). Por cada curso (asignados primero, luego rutas/catálogo):
  `BpmUnitBanner` («Módulo n · {código}», título; color rotando verde azulado/morado/azul) + `BpmLearningPath` con las lecciones
  (Done si `completedLessonIds`, Current la siguiente, Locked el resto) y nodo trofeo = evaluación final (desbloqueado cuando todas las lecciones están hechas).
  Tocar el actual abre la lección; el trofeo abre la evaluación final; el botón libro del banner abre «Lo esencial» del curso. Mascota al costado del camino (desde 390 si cabe).
  Catálogo de cursos no asignados: al final, «Más cursos» con filas compactas.
- **Lección en pasos** (modo enfoque): secuencia = [video si existe] + un paso por cada keyPoint (icono grande y frase) + [texto completo si no hay keyPoints] + práctica de las preguntas de la lección.
  Arriba: X + `BpmChunkyProgress`. Abajo: un `BpmChunkyButton` «Continuar». Al terminar marca la lección completada (lógica existente).
- **Práctica**: pregunta + `BpmOptionTile` numerados + «Comprobar» (deshabilitado hasta elegir) → `BpmFeedbackSheet` verde «¡Muy bien!» o rojo «Casi» con explicación y la correcta marcada;
  las falladas se repiten al final. Usa el modo estudio existente (respuesta del servidor).
- **Fin de lección**: `BpmCelebration` con XP (si hay), racha y % de aciertos de la práctica; «Continuar» vuelve al camino.
- **Evaluación final**: mismo formato sin respuesta inmediata; único indicador = barra dorada + «3/8»; revisión antes de enviar; **Resultado**: celebración con puntaje grande,
  chip aprobado, fichas (+XP, módulo n/total, insignia), constancia con código en grupos de 4; «Siguiente módulo» (nextCourse) y «Ver mis respuestas».
- Teclado en web: 1–9 eligen opción, Enter comprueba/continúa.

## J3 · Operativa

- **Hoy** (WORKER): pills arriba; `BpmMascotRow` con frase calculada («Te faltan 2 tareas y 1 lección para cerrar el día» / «¡Día completo!»);
  tarjeta «Meta de hoy» (barra dorada); «Tu turno» con `BpmMissionRow` (vencidas primero, luego por hora, hechas al final en verde); tarjeta «Sigue aprendiendo» → camino.
- **Registro rápido de tarea** (modo enfoque, 3 pasos con `BpmStepDots`): 1) «¿Cómo quedó?» `BpmChoiceTile` Bien / Con observación / No se pudo (Bien preseleccionado);
  2) nota corta solo si no es Bien; 3) foto («Tomar foto» / «Ahora no») si el plan lo permite; Guardar usa el registro de limpieza existente (offline/QUEUED igual).
  Confirmación `BpmCelebration` «¡Tarea hecha!» con avance del turno y botones «Siguiente tarea» / «Volver a Hoy».
- **Tareas**: título + chip de sede; tarjeta «Turno …» con barra; grupos «Ahora» / «Más tarde» / «Hechas»; botón flotante coral «Reportar».
- **Reportar** (3 pasos): `BpmMascotRow` «¿Qué problema encontraste?» + tiles por categoría del catálogo (icono por palabra clave) → foto → dónde/nota → enviar con el flujo de incidencias existente.

## J4 · Perfil y pulido

- **Perfil** (WORKER): avatar con iniciales, nombre, rol; tarjeta racha con la semana (L–D, días activos según racha); insignias grandes (bloqueadas en gris con criterio);
  constancias; ajustes «Sonido» y «Vibración» (preferencia local), «Mis dispositivos», «Cerrar sesión».
- Sonido corto y vibración al acertar/fallar/terminar (expect/actual; web con WebAudio; respeta los interruptores).
- Animaciones: hundir botón, subir franja de respuesta, rebote del nodo actual, confeti/estrellas suaves en celebración; respetar reducción de movimiento (skill `bpm-motion`).
- Estados vacíos y sin conexión con el tomate. Pruebas de accesibilidad básicas (descripciones de iconos, foco, contraste).

## Estado J1

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`.

**Archivos nuevos** (`composeApp/src/commonMain/kotlin/pe/bpm/core/designsystem/`): `BpmPlayColors.kt`, `BpmPlayType.kt`, `BpmPlayFrame.kt` (interno: borde 2 dp con inferior 4/5 dp), `BpmChunkyButton.kt`, `BpmPlayOptions.kt`, `BpmMissionRow.kt`, `BpmPlayStatus.kt`, `BpmFeedbackSheet.kt`, `BpmCelebration.kt`, `BpmMascot.kt`, `BpmLearningPath.kt`, `BpmPlayNavigation.kt`.
Recursos: fuentes `font/baloo2_{bold,extrabold}.ttf`, `font/nunito_{semibold,bold,extrabold,black}.ttf`; licencias `files/baloo2_OFL.txt`, `files/nunito_OFL.txt`; iconos Lucide `ic_{star,trophy,bolt,target,thermo,spray,box,hand,bug,wrench,drop,eye}.xml` (añadidos a `BpmSymbol`: Star, Trophy, Bolt, Target, Thermometer, Spray, Box, Hand, Bug, Wrench, Drop, Eye).

**Componentes y firmas** (todas con `modifier` y KDoc en español):
- `BpmPlayColors` (`action/correct/error/gold/streak: BpmPlayShade(face, shadow, soft, ink)`, `lock`, `line`, `unitColors`, `shade(tone)`); `enum BpmPlayTone { Action, Correct, Error, Gold, Streak }`.
- `BpmPlayType` (`h1, h2, text, sub, label, button, rowTitle, rowDetail, option, key, pill, tile, bubble, statLabel, statValue, navLabel, sideLabel, kicker, unitTitle, feedbackTitle, celebration`, `display`, `body`; `@Composable`).
- `BpmChunkyButton(text, onClick, modifier, tone: BpmChunkyTone = Action, icon: BpmSymbol? = null, enabled = true)`; `BpmChunkyTone { Action, Correct, Error, Report, White, Disabled }`.
- `BpmOptionTile(text, key: String?, state: BpmOptionState, onClick, modifier, enabled = true)`; `BpmOptionState { Idle, Selected, Correct, Wrong }`.
- `BpmChoiceTile(text, icon, tone: BpmPlayTone, selected, onClick, modifier, horizontal = false)`.
- `BpmMissionRow(title, detail, icon, tone, done, late, onCheck, onOpen, modifier)`.
- `BpmStatPill(icon, value: String, tone, modifier, contentDescription?)`, `BpmChunkyProgress(progress, modifier, tone = Correct, contentDescription?)`, `BpmStepDots(total, current /*1..total*/, modifier)`.
- `BpmFeedbackSheet(correct, title, explanation, actionLabel, onAction, modifier)`.
- `BpmCelebration(image: DrawableResource, title, subtitle: String?, stats: List<BpmStatTileSpec>, modifier)`; `BpmStatTileSpec(label, value, tone = Gold, icon: BpmSymbol? = null)`.
- `BpmSpeechBubble(text: String | AnnotatedString, modifier)`, `BpmMascotRow(image, text: String | AnnotatedString, modifier, imageWidth = 92.dp)`.
- `BpmPathNode(state: BpmPathNodeState, onClick, modifier, enabled, contentDescription?)`; `BpmPathNodeState { Done, Current, Locked, Trophy }`; `BpmPathNodeSpec(state, onClick, contentDescription?, enabled)`; `BpmLearningPath(nodes, modifier, startLabel = "Empezar")`; `BpmUnitBanner(kicker, title, color, onGuide, modifier, guideDescription)`.
- `BpmPlayNavItem(label, icon, selected, onClick)`, `BpmPlayBottomBar(items, modifier)`, `BpmPlaySideMenu(brand, items, modifier)` (230 dp).

**Navegación WORKER**: `workspaceDestinations` = Hoy · Aprender · Tareas · Perfil (`destinationLabel` da «Perfil» a Account); `workspacePrimaryDestinations(available, role = null)` conserva Account solo para WORKER con Tareas; `WorkspaceScreen` usa `BpmPlayBottomBar` (< 840 dp, sin riel en Medium) y `BpmPlaySideMenu` (>= 840 dp); el avatar de la cabecera se oculta para WORKER (Perfil ya está en la navegación). Otros roles sin cambios. `WorkspaceNavigationTest` actualizada (+ prueba `workerNavigationIsTodayLearnTasksProfile`).

**Tarjetas estiradas**: causa = `BpmRowGrid`/`BpmCardGrid` con medidas intrínsecas (`IntrinsicSize.Min/Max`) sobre filas con `Layout` propio; con una columna ahora se renderiza una lista simple sin igualar alturas (arregla también el teléfono de otros roles).

**Capturas**: specs `j1-componentes` (botones, opciones, fichas, misiones, contadores, avance) y `j1-componentes-b` (respuesta, celebración, tomate, camino, banners, barra); en 1280 muestran el menú lateral. `ScreenshotSize.sideNavDp` ahora 230 desde 840 y 0 antes. 1 vuelta de revisión visual; coincide con la maqueta.

**Desviaciones**:
- Solo hay Baloo 2 / Nunito variables en google/fonts; se generaron estáticos 700/800 y 600–900 con fontTools (`varLib.instancer`) y se subconjuntaron a Latin (sin nombre reservado en la OFL).
- Trazo de iconos 2 dp (la maqueta usa 2,4 y 3 en nodos).
- Textos de 11,5–13 sp de la maqueta (por debajo del mínimo de 14 sp del sistema) solo en componentes de juego.
- Espaciados ajustados a la escala del verificador: gaps 14 y 10 → 12/8, 6 → 4 (puntos de paso, menú), padding vertical del menú 22 → 24; 5 dp (pastilla) y 2 dp (barra) con `Spacer`.
- `scripts/check-spacing.mjs`: `BpmPlayType` y `BpmPlayColors` añadidos a los archivos de tema exentos de R8 (`fontWeight`).
- La burbuja «Empezar» reserva su propio espacio sobre el nodo actual en vez de posicionarse absoluta.
- Pendiente a propósito: rebote/animación del nodo actual, confeti (J4); `Medium` aún no limita a 480 dp (J2/J3).

## Estado J2

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`. 2 vueltas de revisión visual de las capturas.

**Diseño (core/designsystem)**: `BpmPlayStage` (barra superior X + `BpmChunkyProgress` + contador, contenido centrado de 640 dp con desplazamiento y pie fijo; con `centered`, `footerColor`), `BpmPlayCard` (borde grueso o discontinuo), `BpmPlayColumns` (columna de 480/600 dp + panel derecho de 330 dp desde 954 dp útiles), `bpmPlayKey`/`Modifier.bpmPlayKeys` (web: 1–9 eligen, Enter comprueba/continúa). `BpmCelebration` suma `titleColor`, `imageWidth` y un slot `middle` (por defecto igual que antes). Recursos: `mascot_achievement.png` (logro-bpm) y `mascot_class.png` (clase-bpm).

**Features (features/training/presentation)**, solo WORKER:
- `WorkerLearn.kt`: camino (`BpmUnitBanner` + `BpmLearningPath` por curso asignado, en el orden en curso → vencidas → pendientes → completadas; libro = guía del curso; trofeo = evaluación final; tomate junto al primer camino; «Más cursos» con el catálogo; panel derecho con contadores, meta y próxima insignia).
- `PlayLesson.kt`: lección en pasos (video + una idea por keyPoint + texto si no hay), práctica con «Comprobar», `BpmFeedbackSheet` (respuesta del servidor), repaso de falladas al final y celebración de fin de lección (lecciones, racha, aciertos; no hay XP por lección en el modelo, no se inventa).
- `PlayEvaluation.kt`: pregunta con barra dorada + «3/8» como único indicador, revisión previa al envío, cola sin conexión y resultado (puntaje grande, fichas XP/módulo/insignia, constancia en grupos de 4, «Siguiente módulo», «Ver mis respuestas»).
- `PlayCourse.kt`: `WorkerCourseContext`, `CourseIntent` y la guía del curso (vista general del WORKER: Lo esencial, practicar, diagnóstica, descargas, constancia, historial). `PlayRules.kt`: funciones puras (pasos, icono por palabras clave, nodos del camino, módulo, meta).
- `TrainingScreen(worker=…)`, `TrainingCourseContent(worker=…)`; `WorkspaceScreen` recibe `workerLearn` y `playFocus` (sin cabecera del shell en el modo enfoque de juego). `App.kt` lleva la intención camino → lección/evaluación y la salida directa al camino. ADMIN/CHEF no cambian.

**VM (campos nuevos al final, con valores por defecto)**: `StudyState.repeatMissed/originalCount/firstTryCorrect/repeated` + `accuracyPercent`; `startStudy(lessonId, repeatMissed = false)`; `TrainingViewModel.completeLesson(id)`; `LearnerState.pathCourses/pathCoursesFailed/pathCoursesLoading` + `TrainingLearnerViewModel.loadPathCourses()` (nuevo parámetro opcional `loadCourse`, cableado en `AppGraph`). Pruebas en `PlayRulesTest`.

**Capturas**: `camino`, `guia-curso`, `idea`, `pregunta`, `pregunta-correcta`, `pregunta-incorrecta`, `fin-leccion`, `evaluacion`, `revision`, `resultado` (sustituyen a aprender/curso/leccion/estudio/evaluacion-hoja; las de juego omiten la captura «completa» con `ScreenSpec.full = false`).

**Desviaciones**: la meta del día del camino es solo «1 lección» (`activeToday`); la combinación con tareas del turno es de J3. El panel derecho no incluye «Tu turno» (J3). La evaluación solo avanza con la pregunta respondida (el selector de preguntas se eliminó). El tomate usa las ilustraciones de la maqueta.

## Estado J3

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`. 1 vuelta de revisión visual de las capturas (coinciden con la maqueta).

**Diseño**: `BpmPlayChip(text, tone)` (ficha «2 de 3»); `BpmPlayStage` suma `steps/step` (puntos de paso en la barra superior); `BpmPlayType.strong` (resalte en la frase del tomate); iconos `Dots` y `WifiOff`; tomates `mascot_ready` (Hoy), `mascot_done` (tarea hecha / reporte enviado) y `mascot_fix` (reportar).

**Reglas puras** (con pruebas en `WorkerPlayRulesTest` y `WorkerReportRulesTest`):
- `features/operations/presentation/WorkerPlayRules.kt`: `dailyGoal(lessonToday, tasksDone, tasksTotal)` (1 lección + tareas del turno), `goalPhrase`, `missionOrder` (vencidas → por hora → hechas), `missionIcon/missionDetail`, `taskGroups` (Ahora / Más tarde / Hechas; la hora local sale de `periodStartsAt` del servidor, sin zonas), `shiftProgress/shiftTitle`, `quickSteps` (con «Bien» 2 pasos; con observación o «No se pudo», 3) y `clockOf`.
- `features/incidents/presentation/WorkerReportRules.kt`: `reportIcon(nombre)` por palabras clave (Otro por defecto) y `reportDescription(lugar, nota)`.

**Hoy** (`app/WorkerTodayContent.kt`, nuevo `displayName`): contadores, tomate con la frase, «Meta de hoy» (barra dorada), «Tu turno» como `BpmMissionRow` (hasta 6; «Ver todas las tareas»), «Sigue aprendiendo» → camino y «Reportar un problema». Desde 1280: panel de 330 dp con contadores, meta, turno y próxima insignia. El shell omite el título «Hola…» para WORKER en Hoy.

**Registro rápido** (`WorkerQuickTask.kt`, modo enfoque): ¿Cómo quedó? (Bien preseleccionado) → nota solo si no es Bien → foto («Tomar foto» guarda y abre las fotos de la limpieza; «Ahora no» guarda) → celebración con avance del turno, «Siguiente tarea» y «Volver a Hoy/Tareas». Usa `OperationsViewModel.newCleaningFor` y `save()` (validación, borrador y cola QUEUED intactos). VM nuevo: `OperationsState.taskDone`, `finishTask(next)` y `cancelTask()` (la X descarta el borrador, salvo con envío sin confirmar). Un plan «por turno» preselecciona el turno que marca el servidor. La raíz muestra el registro en modo enfoque desde Hoy (`secondaryContent`) y desde Tareas (en sitio; `HostedContent(bare)` conserva el estado). Pruebas: `WorkerQuickTaskFlowTest`.

**Tareas**: `WorkerTasksHeader` (título + chip de sede), `WorkerTasksBody` (tarjeta del turno con barra, grupos Ahora / Más tarde / Hechas, «Ver historial» con la lista y las fotos de siempre) y botón flotante coral «Reportar» (`OperationsScreen(onReport)`).

**Reportar** (`WorkerReport.kt`, `IncidentsContent(onClose)`): 3 pasos (tomate + fichas por categoría del catálogo → foto → lugar/nota) y envío por `IncidentEditor` (borrador, cola, clave estable). Una sola sede se usa sin preguntar; «Ver mis reportes» abre la lista de siempre.

**Capturas**: `hoy`, `tareas`, `tarea-paso1`, `tarea-nota`, `tarea-foto`, `tarea-lista`, `reportar` (sustituyen a `tareas-por-hacer` y `tareas-hechas`). Fixtures: `NoopTransport` ahora confirma limpiezas (usa `encodeOperation`) y `IncidentsFixture` con 6 categorías.

**Desviaciones**:
- La foto se adjunta a la limpieza/incidencia ya guardada (el servidor la exige existente): «Tomar foto» guarda y abre el flujo de fotos; sin conexión la foto se agrega después.
- «Atrás» en los pasos 2 y 3 y la X que sale; la maqueta no los trae.
- Se quitó «Registrar limpieza» (sin plan) del WORKER: registrar es el círculo de cada tarea. Hoy conserva «Reportar un problema» (la maqueta no lo trae).
- «Ahora» incluye tareas con hora dentro de 2 h; el dato depende del reloj del dispositivo y del inicio de periodo del servidor.
- La meta cuenta la lección del día solo con `streak.activeToday`; sin logros cargados cuenta como pendiente.
- Chips de 13 sp y textos de 13–14,5 sp solo en componentes de juego (como J1).

## Estado J4

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`, `assembleDebug` y `compileKotlinIosSimulatorArm64`. 1 vuelta de revisión visual (`perfil`, `hoy-vacio`).

**Perfil** (`app/WorkerProfileContent.kt`, slot `workerProfile` de `WorkspaceScreen`; solo WORKER): avatar con iniciales, nombre y «Personal · {org}»; tarjeta de racha con la semana L–D; insignias de 68 dp (ganadas en color según criterio, pendientes en gris con candado; tocar una muestra descripción o «Cómo ganarla»); constancias (cursos de asignaciones completadas → abren el curso, donde está la constancia con código; sin ninguna, enlace a Aprender); ajustes Sonido y Vibración; «Mis dispositivos», «Aceptar una invitación» y «Cerrar sesión» (acciones existentes). Desde Expanded, ajustes y cuenta van en el panel derecho. Reglas puras en `features/training/presentation/ProfileRules.kt` (`streakWeek`, `streakTitle`, `badgeCriterion`, `badgeOrder`, `deviceToday`) con `ProfileRulesTest`; la fecha civil se calcula sin biblioteca de fechas.

**Efectos**: `core/designsystem/BpmFeedbackEffects.kt` (`BpmFeedbackEffects.success/error/celebrate`, `rememberBpmFeedbackEffects()`, `LocalBpmFeedbackSettings`) con `actual` por plataforma: Android `performHapticFeedback` (sin permisos) + `ToneGenerator`; iOS `UIImpact/UINotificationFeedbackGenerator` + `AudioServicesPlaySystemSound`; web WebAudio (tonos generados, sin archivos) + `navigator.vibrate`; JVM no-op. Preferencia: `core/presentation/FeedbackPreferences` (en memoria + `LocalStore` de la cuenta, colección `preferences`; expuesta en `AppGraph.feedbackPreferences`, cargada al iniciar sesión) con `FeedbackPreferencesTest`. `BpmFeedbackSheet` suena/vibra al aparecer (acierto/error) y `BpmCelebration` al mostrarse (nuevo `celebrate`, falso en «Casi lo logras»); cubre práctica, tarea hecha, fin de lección, resultado y reporte enviado.

**Movimiento**: el hundimiento del botón y la franja de respuesta ya existían (J1). Nuevo: rebote suave del nodo actual (3 veces, finito) y `BpmConfettiBurst` (Canvas, 30 piezas, 1,6 s, una vez) dentro de `BpmCelebration`; ambos desactivados con `rememberReduceMotion`.

**Vacíos y sin conexión**: `BpmPlayEmpty` (tomate + título + siguiente paso) y `BpmPlayNotice` (aviso dorado sin conexión, antes privado del registro rápido). «¡Todo al día!» en Hoy y Tareas, «Sin cursos asignados» en Aprender, aviso sin conexión en la práctica y desde el primer paso del registro rápido. Hoy omite la frase del tomate cuando no hay tareas (lo hace el estado vacío).

**Accesibilidad**: nodos con descripción por defecto del estado («Lección hecha», «Bloqueada»; la lección siguiente ya traía «Lección N: …, la siguiente»); círculo de la misión como `toggleable(Role.Checkbox)` con estado; interruptores `Role.Switch` en filas de 56 dp; insignias y días de la racha con descripción. Contraste (`PlayContrastTest`, ≥ 4,5:1): el dorado de las fichas era 2,6:1 → `BpmPlayColors.goldText`; valores de contadores y fichas de resultado usan `textOn(tone)`; franjas de las fichas de resultado ahora usan el tono oscuro (texto oscuro sobre el dorado).

**Desviaciones / pendientes**: `BpmFeedbackEffects` es interfaz común + `expect fun rememberPlatformFeedback()` (patrón del repo, como `TextSharer`), no una `expect class`. Texto blanco sobre el botón rojo (4,0:1) y etiquetas inactivas de la navegación (3,0:1) siguen bajo 4,5:1 (fuera de las fichas; son de J1). El sonido/vibración de Android e iOS y WebAudio no se probaron en dispositivo (solo compilan). La semana de la racha usa la fecha local del dispositivo; el servidor calcula la racha en la zona de la persona.
