# Ajuste de UX/UI y responsividad del rol Personal

Base: capturas de `./gradlew :composeApp:jvmTest --tests '*WorkerScreenshots*'` → `composeApp/build/screenshots/sheet-*.png`
(390 / 768 / 1280). Rama `feat/personal-ux`. Cada paquete vuelve a generar las capturas y **las mira** (Read del PNG) antes de terminar.

## Reglas globales (aplican a todas las pantallas de Personal)

- **G1 Un solo indicador de posición.** Nunca mostrar a la vez texto «X de N», barra segmentada y selector/temario para lo mismo.
- **G2 Panel de apoyo solo desde Expanded (≥ 840).** En Medium (600–839) una sola columna, ancho máx. 640 dp centrado; el contenido de apoyo va debajo.
  Cambiar `supportPaneWidth(Medium) = null` y ajustar sus pruebas.
- **G3 Anchos máximos.** Pantallas de lectura/flujo (lección, estudio, evaluación, revisión, resultado): columna de 720 dp centrada
  (en Expanded+ columna 720 + panel 300 centrados como grupo). Tableros (Hoy, Aprender, Tareas): máx. 1120 dp.
- **G4 Modo enfoque.** Lección, estudio, evaluación, revisión y resultado ocultan la navegación (barra inferior, riel y menú lateral) en todos los tamaños.
  La cabecera muestra cerrar (X) + nombre del curso en lugar de la organización. Salir de una evaluación sin enviar pide confirmación
  («¿Salir sin enviar? Perderás las respuestas de este intento.» · «Seguir respondiendo» / «Salir»), y se quita el enlace «Salir sin enviar» del cuerpo.
- **G5 Nada de tarjetas dentro de tarjetas.** Una sección es título + lista. Si la sección es una tarjeta, sus filas son planas con divisor
  (agregar a `BpmTaskRow` un parámetro `framed: Boolean = true`; dentro de tarjetas `framed = false`).
- **G6 Filas de tarea/curso legibles en teléfono.** Título = la tarea («Desinfectar superficies de preparación»), meta = «Cocina · Diaria»
  (área y frecuencia), título máx. 2 líneas. Cuando el ancho de la fila < 360 dp el chip de estado va **debajo** del título (en la línea de meta), no a la derecha.
  Toda la fila es tocable; nada de enlaces «Ver detalle» sueltos debajo.
- **G7 Una acción principal por pantalla.** Botones: en Compact acción fija abajo (`BpmActionBar`); en Medium+ los botones van en línea, con ancho
  automático (mín. 160 dp) alineados a la derecha del bloque, nunca de 1000 dp de ancho. Pares: secundario a la izquierda, principal a la derecha.
- **G8 Ritmo vertical** con la escala (4/8/12/16/24/32). Sin huecos grandes vacíos entre contenido y acciones (en Compact el contenido llena y la acción va fija).
- **G9 Texto informativo largo** (p. ej. «Ver sin conexión» en web) se resume en una línea con icono; el detalle solo si se expande.
- Respetar `check-spacing.mjs --strict` (0 errores, 0 avisos) y no tocar flujos de ADMIN/CHEF.

## Por pantalla

**Evaluación** (problema reportado por el usuario: selector abajo + «Pregunta 3 de 8» arriba)
- Quitar la barra segmentada y el selector en línea. Compact/Medium: cabecera de la pregunta con un único botón-chip «Pregunta 3 de 8 ▾»
  que abre una hoja inferior (`ModalBottomSheet`) con el `BpmQuestionNavigator` (resumen + chips + leyenda) y el botón «Revisar y enviar».
  Expanded+: el navegador vive solo en el panel lateral («Tus preguntas»), y la cabecera muestra solo el eyebrow «Evaluación final · aprueba con 80».
- Resumen del navegador: «5 por responder» / «Todas respondidas · lista para enviar» (sin listar números; los chips ya lo muestran).
- Acciones: Anterior (secundario, icono) + Siguiente / en la última «Revisar respuestas». Sin enlace «Salir sin enviar» (va a la X con confirmación).
**Revisión**: lista plana con divisores; preguntas sin responder primero y resaltadas; «Enviar evaluación» como acción principal.
**Lección**: Compact: «Lección 3 de 5» como subtítulo de la cabecera + barra segmentada fina; Expanded+: solo el temario lateral (sin barra ni eyebrow).
  Video: tarjeta 16:9 con icono de reproducir en lugar del botón «Ver video de la lección». «Comprueba lo aprendido» y «Evaluar esta lección (suma XP)» en una sola tarjeta
  (botón secundario «Practicar · no califica» + botón de texto «Evaluar lección · suma XP»). Acciones de navegación según G7.
**Estudio**: mismas reglas de enfoque y G1.
**Resultado**: modo enfoque. Hero: mascota + chip Aprobado + «88 / 100» + «Mínimo 80» + chip «+120 XP»; «Total del curso: 180 XP» en texto pequeño (sin repetir XP).
  Código de constancia en grupos de 4 («K7M2 QXD4 …») con fuente monoespaciada. Acción principal «Siguiente curso» con el título del curso y la ruta como texto de apoyo;
  «Revisar respuestas» (expandible) y «Volver al curso» como botones de texto. Botones según G7.
**Curso**: Compact: tarjeta de avance compacta (anillo + «2 de 5 lecciones» + chips «Mejor 72» «60 XP» «Aprueba con 80»), temario, una tarjeta «Más opciones»
  con filas planas: Practicar sin calificación, Ver sin conexión (una línea), Constancia (bloqueada hasta aprobar). Medium: una columna. Expanded+: como hoy pero con G9.
**Hoy**: Medium una columna. Tras «Tu siguiente paso», una franja de resumen (racha · XP · insignias) en una sola tarjeta compacta; el detalle de logros solo en el panel (Expanded+).
  «Tareas de hoy»: filas planas (G5, G6). Eliminar la sección «Tu turno» de un solo ítem: fusionar en una fila de acciones rápidas
  («Reportar un problema» primaria-secundaria e «Incidencias y correcciones»). Botón de la tarjeta Siguiente paso con ancho automático en Medium+; la mascota no se superpone al texto.
**Aprender**: no repetir en «Asignadas» el curso que ya está en «Continuar». Catálogo: filas compactas (icono + título + descripción 2 líneas + chip) en lugar de tarjetas con banda «CURSO»;
  en Expanded+ grilla de 2 columnas de esas filas. Filtros en una sola fila desplazable horizontalmente. Buscador a todo el ancho de la columna.
**Tareas**: «Por hacer» = solo no hechas, ordenadas Vencidas → por hora → sin hora; «Hechas» arriba muestra «Hoy» (hechas del periodo) y luego el historial.
  Lista de una columna (máx. 840 dp) con filas G6, sin «Ver detalle». Cabecera: «Mis tareas» + chip de sede «Sede Miraflores · Cambiar» en una fila; «Registrar limpieza» como
  acción principal (fija en Compact, botón de ancho automático a la derecha del título en Medium+). «Incidencias y correcciones» como fila plana bajo la cabecera.

## Paquetes

- **U1**: G2, G4 (shell/App: modo enfoque y cabecera), G5/G6 en `BpmTaskRow` (y `BpmCourseCard` → variante fila), G7 helpers, hoja inferior del navegador y nuevo resumen. Actualiza pruebas.
- **U2**: Evaluación, Revisión, Estudio, Lección, Resultado, Curso.
- **U3**: Hoy, Aprender, Tareas.

## Estado U1

Hecho (verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`; capturas `sheet-evaluacion.png` y `sheet-leccion.png` revisadas: sin barra inferior, riel ni menú lateral en 390/768/1280, cabecera con X + nombre del curso).
- **G2**: `supportPaneWidth(Medium) = null`; pruebas actualizadas en `BpmComponentsLogicTest`.
- **G4**: `WorkspaceScreen(focusMode, focusTitle)` oculta barra inferior, riel y menú lateral; `WorkspaceTopBar(focusTitle)` muestra X («Cerrar») + título y oculta avisos/cuenta. `App.kt` activa el modo con `isFocusMode(state)` (pantalla ≠ vista general del curso) y confirma la salida con `needsExitConfirmation` (AlertDialog «¿Salir sin enviar?» / «Perderás las respuestas de este intento.» · «Seguir respondiendo» / «Salir»). Se quitó el enlace «Salir sin enviar» del cuerpo. Pruebas: `FocusModeTest`.
- **G5/G6**: `BpmTaskRow(framed, detailMaxLines, below)`: título máx. 2 líneas; con fila < 360 dp el chip baja a la línea de meta (o bajo ella si no cabe). Se implementó con un `Layout` propio y no con `BoxWithConstraints`, porque `BpmCardGrid` pide medidas intrínsecas y `BoxWithConstraints` las rechaza (rompía Tareas). `BpmRowDivider()` para filas planas y `BpmCourseRow(title, description, symbol, onClick, progress, status, framed)`.
- **G7**: `BpmInlineActions(secondary, primary, compactSecondaryFixed)`: Compact reparte el ancho; Medium+ auto-ancho (≥ 160 dp) alineado a la derecha.
- **Navegador**: `BpmQuestionNavigatorSheet` (ModalBottomSheet + navegador + ranura `reviewAction`), `BpmQuestionPickerChip` («Pregunta 3 de 8 ▾»); resumen «N por responder» / «Todas respondidas · lista para enviar» con pruebas.
- Arnés: `Shell` pasa `focusMode`/`focusTitle`; nueva captura `u1-componentes`.
- Pendiente para U2/U3: usar las piezas nuevas en las pantallas (la evaluación aún muestra barra segmentada, «Pregunta X de N» y navegador en línea; los botones de acción siguen con el modifier anterior).

## Estado U2

Hecho (verificado: `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`; `check-spacing --strict` sin hallazgos en archivos de U2: el único error restante es `WorkerTasksHeader.kt:55`, de U3). Capturas revisadas: `sheet-evaluacion`, `sheet-evaluacion-hoja` (nueva, hoja abierta), `sheet-revision`, `sheet-estudio`, `sheet-leccion`, `sheet-resultado`, `sheet-curso`.
- **Evaluación (G1)**: un solo indicador por ancho. Compact/Medium: chip «Pregunta 3 de 8 ▾» → `BpmQuestionNavigatorSheet` (con «Revisar y enviar»); Expanded+: solo el panel «Tus preguntas» (con «Revisar y enviar» en texto) y la cabecera lleva únicamente «EVALUACIÓN FINAL · APRUEBA CON 80». Sin barra segmentada, sin «Pregunta X de N» y sin navegador en línea. Acciones: `BpmInlineActions` con `BackButton` (icono; con texto desde Medium) + «Siguiente pregunta» / «Revisar respuestas». `LocalInitialNavigatorOpen` solo sirve a la captura.
- **Revisión**: una tarjeta con filas planas y divisores; sin responder primero (`reviewOrder`), resaltadas en tono de advertencia con chip; sin barra ni «Respondidas: x de N» (el aviso ya cuenta las que faltan).
- **Lección**: `PositionBar` («Lección 3 de 5» + barra fina) solo sin panel lateral; con panel solo el temario. Video como tarjeta 16:9 oscura con icono de reproducir; «Comprueba lo aprendido» en una sola tarjeta («Practicar · no califica» + texto «Evaluar lección · suma XP»); navegación con G7.
- **Estudio**: eyebrow + `PositionBar` (un solo indicador; se quitó el «Pregunta X de N» duplicado y «Salir de la práctica», que hace la X). Acciones G7 (en compacta, principal y «Reintentar» apilados).
- **Resultado**: hero con «Mínimo 80», chip «+120 XP» y «Total del curso: 180 XP» en texto pequeño; constancia con código agrupado de 4 en monoespaciada (`groupVerificationCode`); título y ruta del siguiente curso como apoyo y «Siguiente curso» como acción principal (fija en compacta); «Revisar respuestas» en una tarjeta de filas planas; «Volver al curso»/«Repasar en modo estudio» como texto.
- **Curso**: Compact/Medium una columna: tarjeta de avance compacta (anillo, «2 de 5 lecciones», chips Mejor/XP/Aprueba con; en Medium con el botón en línea), temario y tarjeta «Más opciones» con filas planas (Practicar, «Ver sin conexión» en una línea que se expande con el detalle (`DownloadsRow`, G9), Constancia bloqueada). Expanded+: temario + panel con el mismo `DownloadsCard` resumido.
- **G3**: en `TrainingScreen` el orden `widthIn(max).fillMaxSize()` hace efectivo el ancho máximo; con panel, 720 + 16 + 300 centrados como grupo.
- Pruebas nuevas: `ReviewAndCodeTest`. Captura nueva: `evaluacion-hoja`. Nota: las capturas comparten `build/screenshots`; dos corridas simultáneas se pisan.

## Estado U3

Hecho (verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`; capturas `sheet-hoy`, `sheet-aprender`, `sheet-tareas-por-hacer` y `sheet-tareas-hechas` revisadas en 390/768/1280).
- **Hoy**: tras «Tu siguiente paso», franja `BpmSummaryStrip` (racha · XP · insignias) solo sin panel de apoyo; el panel (Expanded+) conserva racha y logros. «Tareas de hoy» y «Asignadas a ti» con filas planas + `BpmRowDivider`; la asignación de «Siguiente paso» no se repite en la lista. Se eliminó «Tu turno»: fila de acciones rápidas («Reportar un problema» + «Incidencias y correcciones»; apiladas en Compact, en línea a la derecha desde Medium). `BpmNextStepCard`: botón a ancho de contenido (≥ 160 dp) desde Medium.
- **Aprender**: «Asignadas a ti» sin el curso de «Continuar», en filas `BpmCourseRow`; `ContinueCard` con botón de ancho automático (`BpmInlineActions`); catálogo en filas (1 columna, 2 desde Expanded) con `BpmRowGrid`; filtros en una fila desplazable (`CatalogFilterRow`); buscador a todo el ancho (`BpmSearchField(fullWidth)`); resumen en franja bajo Medium.
- **Tareas**: cabecera `WorkerTasksHeader` dentro de `OperationsScreen` (se desplaza): «Mis tareas» + chip de sede («Sede · Cambiar»; en Compact solo la sede con flecha), «Registrar limpieza» fija abajo en Compact y botón auto-ancho a la derecha en Medium+, «Incidencias y correcciones» como fila. `DailyOperationsContent(onIncidents)` reemplaza al encabezado de `App.kt` para WORKER. Lista de una columna (máx. 840 dp), filas G6 (título = tarea, meta «Cocina · Diaria», sin «Ver detalle»; el chip de turno no se repite en la meta).
- **Por hacer / Hechas**: `pendingPlans` (solo no hechas, Vencidas → hora → sin hora). «Hechas»: sección «Hoy» con `donePlans` (`plan.today.done` de `view=today`) y «Historial» con `withoutDoneToday` (sin repetir la última ejecución ya mostrada). Se pide `view=today` también en «Hechas».
- Nuevo `BpmRowGrid`: `BpmCardGrid` iguala alturas con medidas intrínsecas y estira las filas de `BpmTaskRow` (hueco enorme en 390/1280); para filas usar `BpmRowGrid`.
- Pruebas: `WorkerPendingDoneTest`. Arnés: `Tasks` ya no compone `WorkerTasksHeader` (va dentro de `DailyOperationsContent`); la fixture enlaza `lastExecutionId` con las ejecuciones.
