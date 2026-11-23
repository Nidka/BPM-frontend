# Contrato de pantallas: listados, formularios y detalles

Una sola medida para Android, iOS y web. Este documento es la fuente de verdad de **cómo se arma** cada
pantalla; los valores viven en código (`BpmPatternRules`, `BpmSpacing`) y el verificador
`scripts/check-spacing.mjs --strict` hace cumplir lo medible. Complementa la skill `bpm-spacing`
(espaciado fino, accesibilidad) y `BpmScreenPatterns.kt` (los seis patrones).

## 1. Principios

1. **Se decide por el ancho útil, no por la ventana.** El ancho útil es lo que queda para el contenido:
   ventana − navegación (menú de 230 dp desde 840 dp; antes, barra inferior) − márgenes. La clase de ventana solo decide la
   navegación y el margen; columnas, detalle al lado y campos en pares los decide el componente midiendo
   su propio contenedor. Así una ventana de 900 dp con menú lateral (600 dp útiles) se comporta como una
   pantalla de 600 dp, no como una de escritorio.
2. **Las pantallas no miden ni fijan anchos.** Una pantalla de `features/` no usa `BoxWithConstraints`
   ni `widthIn(max = N.dp)`: elige un patrón y los componentes ponen la medida (R12, R15). Excepción
   marcada con `// medida: motivo` (ventana modal, ilustración).
3. **Un componente por necesidad, compartido.** Los componentes viven en `core/designsystem`, no
   conocen el dominio (reciben textos, estados y lambdas) y se usan igual en todas las plataformas. Un
   módulo no crea su propia versión de un componente que ya existe (lista paginada, confirmación,
   acciones de formulario).
4. **Arquitectura intacta.** La UI observa `StateFlow` y emite acciones; ningún componente del sistema
   recibe un ViewModel, un DTO ni un repositorio.

## 2. Medidas compartidas (dp)

| Medida | Valor | Dónde vive |
|---|---|---|
| Cortes de ventana (navegación y margen) | 600 · 840 · 1200 | `BpmWindowClass` |
| Margen lateral por clase | 16 · 24 · 32 · 32 | `BpmWindowClass.margin` |
| Navegación (igual en todos los roles) | barra inferior < 840 (o alto < 480) · menú lateral 230 | `BpmNavigationMode` |
| Ancho máximo de contenido | 1200 | `BpmSpacing.contentMax` |
| Columna de lectura (formulario, pasos, detalle solo) | 720 | `BpmPatternRules.readingWidth` |
| Tarjeta de acceso | 440 | `BpmPatternRules.accessWidth` |
| Lista junto al detalle | 360 | `BpmPatternRules.listWidth` |
| Detalle mínimo junto a la lista | 480 | `BpmPatternRules.detailMinWidth` |
| Tarjeta mínima de rejilla | 300 (separación 16) | `BpmPatternRules.gridMinCard` |
| Dos campos lado a lado desde | 420 de ancho del formulario | `BpmPatternRules.pairMinWidth` |
| Datos del detalle en dos columnas desde | 520 de ancho del panel | `BpmPatternRules.detailPairMinWidth` |
| Búsqueda | máximo 560 | `BpmSpacing.searchMax` |
| Objetivo táctil | 48 mínimo; botón 52 (compacto 44) | `BpmSpacing.touch/button` |
| Separaciones | 4 · 8 · 12 · 16 · 24 · 32 | `BpmSpacing.xs…xxl` |
| Texto mínimo | 14 sp (RNF de usabilidad); chips incluidos | `BpmTheme` |

Columnas de rejilla según el ancho útil: **< 616 → 1**, **616–931 → 2**, **≥ 932 → 3** (nunca más de 3).
Detalle junto a la lista desde **864 de ancho útil** (360 + 24 + 480); antes, el detalle ocupa la pantalla
y muestra «Volver».

## 3. Listado

Patrón `ListDetail` (registros que se abren) o `Grid` (catálogos). Anatomía, de arriba abajo:

```
┌ Título de la pantalla ───────────────── [+ Nueva sede] ┐  BpmPageHeader (acción a la derecha ≥ 600)
│ Activas · Inactivas            [ Buscar sedes…       ] │  BpmListToolbar (filtros + búsqueda)
│ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐  │  BpmGridLayout de BpmRecordCard
│ │Título  [Estado]│ │               │ │               │  │
│ │Subtítulo       │ │               │ │               │  │
│ │dato · dato     │ │               │ │               │  │
│ │[ Abrir      ]  │ │               │ │               │  │
│ └───────────────┘ └───────────────┘ └───────────────┘  │
│ [Anterior]   Página 2                      [Siguiente] │  BpmPagedList (paginación)
└────────────────────────────────────────────────────────┘
```

1. **Cabecera:** un solo título; la acción de crear va en la cabecera («Nueva sede», «Invitar persona»),
   nunca al pie de la lista.
2. **Barra (`BpmListToolbar`):** filtros (chips o pestañas) a la izquierda y búsqueda a la derecha cuando
   caben (≥ 600 de ancho útil); en compacta, filtros arriba y búsqueda debajo a todo el ancho.
3. **Resultados (`BpmPagedList`):** un único bloque para cargando (barra de progreso), error saneado con
   «Reintentar», vacío (`EmptyState` que dice el siguiente paso) y contenido; la paginación «Anterior ·
   Página N · Siguiente» va al pie y no inventa totales.
4. **Ítem (`BpmRecordCard`):** título (`titleMedium`, 2 líneas máx.), estado (`BpmStatusChip`) a su
   derecha, subtítulo (`bodyMedium`), hasta 3 datos de apoyo (`bodySmall`, gris) y, al pie, **una**
   acción con botón a todo el ancho de la tarjeta + hasta dos acciones de texto. Las acciones destructivas
   (desactivar, revocar) son de texto y piden confirmación. Si el registro tiene detalle, toda la tarjeta
   lo abre y el botón dice «Ver detalle».
5. **Personas** usan `BpmListRow` (fila densa ≥ 840 dentro de su contenedor, tarjeta en compacta).
6. Listas secuenciales (cola de envíos, preguntas) siguen apiladas: `// lista-apilada: motivo`.

## 4. Formulario de creación y edición

Patrón `Form` (`BpmFormLayout`). Anatomía:

```
┌ Nueva sede ─────────────────────────────┐  Título: «Nueva …» / «Editar …»
│ Borrador guardado en este dispositivo    │  DraftSaveNotice (si aplica)
│ ┌ Datos de la sede ───────────────────┐ │  BpmSection
│ │ Nombre *                             │ │
│ │ Dirección *          Distrito        │ │  BpmShortFields (pares ≥ 420)
│ └──────────────────────────────────────┘ │
│ ┌ Contacto ───────────────────────────┐ │
│ │ Teléfono                             │ │
│ └──────────────────────────────────────┘ │
│                  [Cancelar] [Crear sede] │  BpmFormActions
└──────────────────────────────────────────┘
```

1. **Ancho:** todo el ancho en compacta; **máximo 720** en el resto, alineado a la izquierda bajo el título
   (no centrado, para que coincida con la cabecera). En la clase grande, una vista previa opcional va a la
   derecha.
2. **Secciones (`BpmSection`):** agrupan campos relacionados con un título corto; 24 entre secciones, 12
   entre campos. Un formulario de 1–3 campos puede ir en una sola sección.
3. **Campos:** `BpmTextField` con etiqueta visible siempre, error debajo del campo, obligatorios con `*`.
   Dos campos cortos relacionados (desde/hasta, dirección/distrito) van con `BpmShortFields`.
4. **Acciones (`BpmFormActions`):** al final del formulario. Compacta: «Cancelar» y la acción principal se
   reparten la fila (principal a la derecha). Desde 600: a la derecha, a contenido (mínimo 160). Un solo
   botón principal y dice la acción («Crear sede», «Guardar cambios»); en curso dice «Guardando…» y se
   deshabilita junto a «Cancelar».
5. **Confirmaciones en línea (`BpmConfirmPanel`):** mismas acciones que un formulario; nunca dos botones
   apilados a todo el ancho.
6. **Edición dentro de un listado:** el editor se muestra arriba de la lista con `BpmSection` +
   `BpmFormActions` y la misma columna de 720; no es una tarjeta a todo el ancho de la pantalla.

7. **Formulario por pasos (`BpmStepForm`)** cuando la creación pide **tres o más decisiones que dependen
   entre sí** (invitar persona: correo → rol → sedes; programar control: sede → frecuencia y turno → hora;
   asignar curso: curso → a quién → fecha). Cada paso es **una pregunta** («¿A quién invitas?»), con
   «Paso N de M» y barra de avance arriba (anunciados al lector de pantalla), «Cancelar» en el primer paso y
   «Atrás» en los demás, «Siguiente» deshabilitado con una línea que dice qué falta, y la acción final
   («Enviar invitación») en el último paso, que muestra un resumen. Si el servidor devuelve un error, el
   formulario vuelve al primer paso con problema. Un paso que no aplica (sedes de un administrador) no se
   muestra. Los formularios cortos (una sección) siguen en una sola página.

## 5. Detalle

Patrón `ListDetail` (junto a la lista) o a pantalla completa. Anatomía (`BpmDetailPane`):

```
┌ ← Volver a la lista ──────────────────────┐  solo si el detalle ocupa la pantalla
│ Cámara de frío 2            [Pendiente]    │  título titleLarge + estado
│ Cocina principal · 21/11/2026 09:30        │  subtítulo
│ [Editar] [Desactivar]                      │  acciones del registro (BpmInlineActions)
│ ┌ Datos ─────────────────────────────────┐ │  BpmSection
│ │ Responsable        Fecha límite         │ │  BpmDetailFields (2 columnas ≥ 520)
│ │ Ana Pérez          22/11/2026           │ │
│ └─────────────────────────────────────────┘ │
│ ┌ Historial ─────────────────────────────┐ │
└────────────────────────────────────────────┘
```

1. **Junto a la lista** ocupa el resto del ancho; **solo**, máximo 720 y con «Volver a la lista».
   `BpmDetailPane` sabe cuál de los dos casos aplica (lo publica `BpmListDetail`).
2. **Datos (`BpmDetailFields`):** etiqueta (`bodySmall`, gris) encima del valor (`bodyLarge`); una columna
   si el panel mide < 520, dos desde 520. Un dato vacío se muestra como «—», nunca se omite en silencio.
3. **Acciones del registro** debajo del encabezado, con `BpmInlineActions`; las destructivas piden
   confirmación con `BpmConfirmPanel`.

## 6. Estados de cualquier pantalla con datos

Cargando, vacío (con siguiente paso), error saneado con «Reintentar», sin permiso y sin conexión
(`ServerOnly` en acciones administrativas). Nunca éxito con datos simulados.

## 7. Verificación

```bash
node scripts/check-spacing.mjs --strict
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
```

Reglas nuevas del verificador: **R15** ancho fijo (`widthIn(max = N.dp)`) en una pantalla de
`features/` o `app/`; **R16** helper de listado o confirmación propio de un módulo (usar
`BpmPagedList`/`BpmConfirmPanel`). Revisión visual a 360, 600, 840, 1000, 1200 y 1600 dp de ventana, en
especial 840–1199 con el menú lateral abierto.
