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
