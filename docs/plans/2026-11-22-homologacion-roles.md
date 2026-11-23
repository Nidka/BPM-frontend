# Plan: homologar el diseño de todos los roles

Fuente visual: `/Users/helcorp/Downloads/Proyectos/BPM/Documentacion/Propuestas/roles-v1/index.html` (base común, tabla de anchos,
navegación por rol, maquetas de Jefe, Administrador y Superadministrador, tabla de componentes).
Base ya hecha para Personal: `docs/plans/2026-11-22-personal-juego.md` (Estado J1–J4) y la rama `fix/personal-web`.

## Reglas

- Los componentes Play (BpmChunkyButton, BpmOptionTile, BpmChoiceTile, BpmMissionRow, BpmStatPill, BpmChunkyProgress, BpmFeedbackSheet,
  BpmCelebration, BpmMascotRow, BpmPlayCard, BpmPlayStage, BpmPlayColumns, BpmPlayType, BpmPlayColors…) pasan a ser la base de toda la app.
  Los componentes antiguos (RaisedButton, BpmSecondaryButton, BpmCard plano, BpmStatTile, BpmListRow, BpmPageHeader) se reimplementan
  por dentro con el estilo nuevo (misma firma, para no romper pantallas) y luego se migran las pantallas; nunca dos estilos visibles.
- Tipografía Baloo 2 / Nunito en toda la app (incluido el ingreso). Colores con significado fijo.
- Distribución por ancho: <600 una columna + barra inferior; 600–839 columna centrada 640 + barra inferior;
  840–1199 menú lateral 230 + columna 600–720 centrada + panel 300; ≥1200 grupo centrado máx. 1120 (contenido + panel 330 o lista–detalle).
- Cada paquete verifica en la app real (servidor web local apuntando al backend de producción + Playwright) con las cuentas de prueba
  de cada rol (credenciales fuera del repo), y mira las capturas a 390 y 1280. No crear ni borrar datos en producción salvo lo indispensable.
- check-spacing --strict 0/0, compileKotlinJvm, jvmTest, compileKotlinWasmJs; al final assembleDebug y compileKotlinIosSimulatorArm64.

## H1 · Base común
- Nuevos: `BpmAttentionBar`, `BpmKpiTile` (cifra + barra o tendencia + tono; «Sin datos» en gris), `BpmPersonRow`, `BpmDataRow` (se apila en teléfono);
  rediseño de `BpmListDetail` y `BpmStepForm` (barra gruesa arriba, botones gruesos abajo).
- Reestilizar por dentro los componentes antiguos. Cabecera unificada (organización en Nunito, sin la etiqueta «Organización»); menú lateral y barra inferior iguales para todos.
- Ingreso con la tipografía y botones nuevos.

## H2 · Jefe de cocina
- Navegación Turno · Controles · Equipo · Aprender · Perfil (Incidencias como sección). Actualizar pruebas de navegación.
- Turno: franja de atención, 3 indicadores, controles de hoy con «Empezar», equipo con avance (si no hay dato por persona, omitir), su aprendizaje en el panel.
- Ejecutar control por pasos (fichas Cumple / No cumple / No aplica, observación si no cumple) con la lógica de ejecución existente.
- Aprender: el camino de Personal.

## H3 · Administrador
- Navegación Inicio · Controles · Equipo · Academia · Empresa. Inicio con franja de atención, 4 indicadores (reportes existentes), sedes con barra, acciones rápidas.
- Equipo, Academia, Empresa y Controles en lista–detalle con el estilo nuevo; formularios por pasos.

## H4 · Superadministrador y cierre
- Organizaciones, Indicadores, Acceso, Perfil con el estilo nuevo.
- Revisión final con capturas reales de los 4 roles en 390 / 768 / 1280.

## Estado H1

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`. Revisión en la app real (servidor web local contra el backend de producción + Playwright, cuentas Administrador, Jefe y Personal) a 1280 y 390: ingreso, y las rutas de cada rol; sin errores de consola ni cierres de composición. 2 vueltas.

**Nuevos** (`core/designsystem`): `BpmAttentionBar(items: List<BpmAttentionItem>, title, calmTitle, calmText)` (rojo suave con motivos que abren pantalla; sin motivos pasa a «Todo al día» verde con el tomate); `BpmKpiTile(label, value: String?, tone: BpmPlayTone, progress, trend, trendGood, support, featured)` (`value` nulo = «Sin datos» gris con tamaño de texto); `BpmPersonRow(name, detail, status, progress /*anillo*/, selected, onClick, trailing, footer)`; `BpmDataRow(title, subtitle, progress, value, tone, status, selected, onClick, stacked)` (columnas de ancho fijo para alinear barras; se apila con la clase compacta, o con `stacked`); `BpmBackLink` (sustituye a los dos «Atrás» privados de Registro rápido y Reportar). Ninguno usa `BoxWithConstraints` ni medidas intrínsecas, así que caben en `BpmCardGrid`/`BpmRowGrid`; `BaseCommonComponentsTest` los dibuja dentro de rejillas a 390 y 1280 (PNG en `build/visual`).

**Rediseñados**: `BpmListDetail` (línea de 2 dp entre lista y detalle; selección resaltada con el `selected` de las filas/tarjetas) y `BpmStepForm` (barra gruesa `BpmChunkyProgress` + «PASO N DE M», la pregunta como título grande, campos en tarjeta, botones gruesos abajo).

**Reestilizados por dentro (misma firma)**: `RaisedButton` y `BpmSecondaryButton` = botón grueso (`BpmChunkyShell`, también base de `BpmChunkyButton`); `BpmTextButton` Nunito 900 primario; `BpmCard` borde 2 dp + 4 dp abajo, radio 18, seleccionada `#7FC3B6` sobre teal suave (y `BpmPlayCard` ahora es esa misma tarjeta); `BpmStatTile` = `BpmKpiTile`; `BpmListRow` = `BpmPersonRow`; `BpmPageHeader` Baloo 2 por el tema; `BpmFilterChip`, `BpmSegmentedTabs` (`.seg`), `BpmTextField`/`BpmSearchField` (ahora `BasicTextField` + decoración de Material, borde de 2 dp también en reposo, radio 14). Tema: `MaterialTheme.typography` mapeada a Baloo 2 (display/headline/titleLarge) y Nunito (resto); el ingreso usa `BpmCard` y `BpmSegmentedTabs`.

**Cabecera y navegación unificadas** (todos los roles): cabecera con el nombre de la organización en Nunito 900, sin «Organización» (se quitó el parámetro `play`); barra inferior (`BpmPlayBottomBar`) < 840 dp y menú lateral de 230 dp (`BpmPlaySideMenu`) desde 840 dp; se eliminaron el riel y `NavigationBar`/`NavigationDrawerItem`; `BpmNavigationMode` = BottomBar | SideMenu(230) y `LAYOUT.md` actualizado. Cada rol conserva sus destinos (H2/H3 los cambian); iconos propios por destino (Seguimiento = ojo). Con cinco destinos la etiqueta baja a 10 sp para que «Seguimiento» quepa a 390.

**Desviaciones**: los botones heredados (`RaisedButton`, `BpmSecondaryButton`, `BpmTextButton`) llevan el texto tal como lo escribe cada pantalla (Compose no tiene `text-transform`); solo `BpmChunkyButton` pone mayúsculas. Al migrar cada pantalla en H2–H4 conviene pasarla a `BpmChunkyButton`. `BpmStatTile` trata «Sin datos» y «—» como valor nulo. Etiquetas de la barra de 10 sp, de 11,5 sp en las maquetas y de 14 sp en el RNF (como en J1). Un 503 puntual del backend de producción apareció una vez al cargar el espacio de Jefe y no se repitió. El botón «Continuar con Google» conserva su estilo (guía de marca de Google).

## Estado H2

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`, prueba de arquitectura. Revisión en la app real (servidor local contra producción + Playwright, cuenta Jefe; Administrador y Personal solo Inicio/Hoy) a 1280 y 390; 2 vueltas (los indicadores partían la etiqueta a 390: ahora el primero va solo y los otros dos juntos).

**Navegación CHEF**: Turno · Controles · Equipo · Aprender · Perfil (`profileInNavigation`; Incidencias ya no es destino ni atajo: tarjeta en Turno y Controles). `WorkspaceNavigationTest` actualizada.

**Nuevos** (`app/`): `ChefTurnoContent` (franja `BpmAttentionBar` con controles vencidos e incidencias abiertas del panel de indicadores y cursos vencidos propios; 3 `BpmKpiTile`; controles de hoy con «Empezar/Continuar/Ver» (`ChefControlRow`); equipo con `BpmPersonRow`; panel con «Mi aprendizaje», Incidencias y encuesta), `ChefControlsContent` (controles + accesos), `ChefTeamContent` (lista–detalle de solo lectura), `ChefRules` (+ `ChefRulesTest`). Ejecución: `ChecklistStepFlow`/`ChecklistStepRules` (+ prueba): una pregunta por pantalla con fichas Cumple/No cumple/No aplica, número grande con criterio, observación y foto si no cumple, revisión y celebración; reutiliza `ChecklistRunViewModel` (borrador, cola, conflicto) tal cual. Aprender y Perfil = los del Personal (`WorkerLearnContent`, `WorkerProfileContent(roleLabel)`). Botones de pantallas del jefe en `BpmChunkyButton`.

**Desviaciones / no verificado**: la cuenta de prueba no tiene controles programados, tareas ni personas, y `/workspace` devuelve `members: []` para CHEF, así que «Equipo» y «Tu equipo» muestran el estado vacío y no hay avance por persona ni «cursos vencidos del equipo» (la API no los da; solo los propios). La ejecución por pasos y las filas con «Empezar» no se vieron en la app real (exigiría crear una revisión en producción); solo están cubiertas por pruebas de reglas. «Empezar» abre la ficha del control y la revisión se inicia sola. Las pantallas de gestión del ADMIN no se migraron (H3).

## Estado H3

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `compileKotlinWasmJs`. Revisión en la app real (servidor local contra producción + Playwright, cuenta Administrador; Jefe y Personal solo su inicio) a 1280 y 390: Inicio, Controles, Equipo (con persona abierta), Academia (con curso abierto), Empresa, Revisiones del equipo y las pantallas de gestión (cuentas, cursos, plantillas, reglas de avisos, incidencias, controles programados, reportes); sin errores de consola. 3 vueltas (columna alineada con el título, filtros de Equipo en una fila desplazable, pestañas de Academia más cortas).

**Navegación ADMIN**: Inicio · Controles · Equipo · Academia · Empresa (`workspacePrimaryDestinations` quita Seguimiento solo para el administrador con Controles; la super administración no cambia). «Seguimiento» sigue como ruta, titulada «Controles», con Controles resaltado. Las rutas de gestión cambian de destino solo para el ADMIN: cursos (`empresa/academia`) en Academia, cuentas en Equipo, incidencias y controles programados en Controles; para los demás siguen en Empresa (`destination` se recalcula en `App.kt`). Pruebas de navegación actualizadas y `AdminRulesTest`.

**Pantallas**: `AdminInicioContent` (franja `BpmAttentionBar` con controles vencidos, incidencias, avisos y sedes bajo 70 %; 4 `BpmKpiTile` de `Kpis`, nulo = «Sin datos»; sedes como `BpmDataRow` con el cumplimiento a 30 días de `ReportsViewModel`; panel con Programar control / Asignar curso / Invitar persona y accesos a reportes, normativa y encuesta), `AdminControlsContent` (reutiliza `ChefControlsContent` con todas las sedes, botones de programación y accesos: revisiones del equipo, incidencias, insumos…), `AdminTeamContent`, `AdminAcademyContent` y `AdminCompanyContent` (lista–detalle con `BpmPersonRow`/`BpmRecordCard`/`BpmDetailPane`). `BpmPlayColumns(alignStart)` alinea columna y título a la izquierda. `BpmChunkyButton` ahora respeta `serverActionBlocked()` (como los botones heredados) y se migraron a él los botones `RaisedButton`/`BpmSecondaryButton` con texto simple de las pantallas de gestión (~220 usos).

**Desviaciones**: «Programar control» abre Plantillas (la programación empieza eligiendo plantilla y sede; no hay un formulario independiente). «Invitar persona», «Cambiar rol o sede» y «Editar sede/datos» abren «Cuentas y equipo» sin elegir pestaña (el estado de pestaña es privado de esa pantalla). La API no da avance de capacitación por persona ni cursos vencidos de la organización: el detalle de persona no los muestra. Cuentas, Cursos (gestión), Plantillas y Controles programados siguen en rejilla de tarjetas (su detalle ya es otra ruta o lista–detalle); no se creó, editó ni borró ningún dato en producción.

## Estado H4

Hecho (sin commit). Verificado: `check-spacing --strict` 0/0, `compileKotlinJvm`, `jvmTest`, `wasmJsBrowserDistribution`, `assembleDebug`, `compileKotlinIosSimulatorArm64`, `iosSimulatorArm64Test`, `node --test scripts/*.test.mjs web/*.test.mjs`. Revisión final en la app real (servidor local contra producción + Playwright, cuentas Administrador, Jefe y Personal; 390, 768 y 1280, con el 768 iniciando a 390 y cambiando el tamaño) en 2 vueltas; hojas por rol en `scratchpad/final-<rol>.png`. La super administración **no tiene cuenta de prueba**: se verificó con el arnés `WorkerScreenshots` (nuevas capturas `PlatformScreenshots.kt`, con repositorios falsos).

**Super administración**: navegación Organizaciones · Indicadores · Acceso · Perfil (`WorkspaceDestination.Indicators`; `destinationLabel(..., platformAdmin)`; «Acceso» es siempre un atajo de la super administración; el atajo «Empresas» desaparece porque Organizaciones es su inicio). Empresa, Equipo y Seguimiento siguen siendo rutas permitidas: son la organización abierta y marcan Organizaciones en la barra. Pruebas de navegación actualizadas (`WorkspaceNavigationTest`) y `PlatformRulesTest`.
- **Organizaciones** (`PlatformContent.kt`, `PlatformRules.kt`): 4 `BpmKpiTile` (cuántas organizaciones hay en la página cargada, con «+» si hay más; cumplimiento a 30 días, capacitación e incidencias abiertas de la organización abierta, rotuladas con su nombre; sin dato, «Sin datos»), todas las organizaciones como `BpmDataRow` con búsqueda y paginación (la abierta lleva su barra, cifra y estado «Abierta»), «Nueva organización» en la cabecera. Tocar una organización la abre en su espacio (Empresa, con «Todas las organizaciones» para volver y tarjetas a Equipo y Revisiones del equipo).
- **Indicadores**: la lectura del Inicio del administrador (4 indicadores y sedes con barra) para la organización abierta, más reportes y normativa; reemplaza al `DashboardPanel` antiguo (eliminado). Los reportes tienen pestañas segmentadas.
- **Acceso**: concesiones e investigación con botones gruesos, pestañas en una fila desplazable y sin flecha «atrás» (es un destino).
- **Perfil**: `ManagerProfileContent` (avatar, ajustes y cuenta, igual que Personal y Jefe sin racha) para la super administración y también para el Administrador, que antes veía la tarjeta antigua «Mi cuenta».

**Barrido**: ya no queda ningún `RaisedButton`/`BpmSecondaryButton` con texto simple fuera de `core/designsystem` (~110 usos pasaron a `BpmChunkyButton`; el botón «atrás» con icono de las evaluaciones y la envoltura del sistema se conservan); no hay `Button`/`OutlinedButton`/`Card`/`NavigationBar` de Material ni `FontFamily` fija, salvo «Continuar con Google» (guía de marca). Se quitó la etiqueta «ORGANIZACIÓN» y el código sin uso `WorkspaceIcon`. Etiquetas largas de la barra («Organizaciones») se compactan para no cortarse (menú y barra inferior).

**Hallazgos de la revisión real**: el Perfil del Administrador era la pantalla antigua (ahora `ManagerProfileContent`); «Controles» del Jefe quedaba centrada bajo un título a la izquierda (ahora alineada como la del Administrador); las cuatro pestañas de Acceso se partían en tres líneas.

**Desviaciones / no verificado**: no existe modo de solo lectura de una organización, así que no se muestra la cinta «Viendo como superadministrador» (se conserva el comportamiento actual: la super administración gestiona la organización abierta). La API no da cifras por organización: solo la abierta muestra cumplimiento, y el estado de cada organización se limita a «Abierta». Las pantallas de la super administración no se vieron en la app real (sin cuenta); solo en el arnés. En el escritorio de producción el inicio de sesión con teclado (Enter) no envía el formulario; se usó el botón.
