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
