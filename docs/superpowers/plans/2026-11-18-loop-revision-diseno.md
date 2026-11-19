# Plan de revisión de diseño en loop

> Ejecución por iteraciones cortas con `/loop` (ver «Cómo se ejecuta»). Cada iteración toca una pantalla o un componente, se verifica y se registra antes de pasar a la siguiente.

**Objetivo:** que todos los componentes sigan un orden de tallas, jerarquía y distribución, en 1920×1080 y en móvil, sin perder la identidad actual de BPM.
**Skills del repo:** `bpm-spacing` (espaciado, tallas, distribución), `bpm-motion` (movimiento) y `bpm-performance` (al cerrar).
**Rama:** `diseno/loop-mejoras`. Un commit pequeño por iteración.

## Lo que NO cambia (identidad base)

- Color: teal `#005B59`, naranja de acento `#ED5700` solo en detalles, fondo `#F5F6F6`, superficies blancas.
- Lenguaje «táctil»: borde de 2 dp y relieve inferior de 3 dp en tarjetas y botones; radios 12/16/24 dp.
- Mascota tomate, logo gorro con visto, textos en español claros y breves.
- Navegación: barra inferior bajo 950 dp y lateral desde 950 dp. Ancho de contenido 1200 dp alineado a la izquierda.

## Hallazgos medidos en el código (18/11/2026)

| Tema | Dato | Problema |
|---|---|---|
| Botón principal | 95 usos con 7 anchos distintos: 26 a ancho completo, 17 `max 360`, 3 `min 220`, 1 `min 240`, 1 `min 160`, 1 `max 320`, 1 `max 300` | El mismo botón mide distinto según la pantalla |
| Botón secundario | 216 usos, solo 44 a ancho completo | Mezcla de anchos dentro de una misma tarjeta |
| Peso de letra | 207 `fontWeight` escritos a mano (186 Bold, 9 ExtraBold, 7 SemiBold, 5 Medium) | El peso debería venir del tema, no de cada pantalla |
| Escala de texto | `bodySmall` 131 veces contra `bodyMedium` 24 | Mucho texto pequeño; sin regla de cuándo usar cada estilo |
| Títulos | 54 usos de `headline*`, 68 de `titleLarge`, 101 de `titleMedium` | Los «títulos» dentro de tarjetas compiten con el de la página |
| Tarjetas | 119 tarjetas a ancho completo, 42 `EmptyState` | Una tarjeta enorme para una sola línea (avisos, cuenta, Investigación) |
| Separaciones | `spacedBy`: 8 (82), 12 (35), 4 (26), 16 (13), 6 (10), 10 (7), 14, 20, 24, 2 | 10 valores donde bastan 5 |
| Ya corregido | chips de filtro (35 usos → `BpmFilterChip`), alto y relieve de botones, ancho único 1200 dp | Falta cerrar el resto con el mismo criterio |

Observados en pantalla a 1920: tarjeta «Investigación» de 1624 dp con una sola línea, KPIs en 3+3+1, Mi cuenta con tarjeta casi vacía, tarjetas de Empresa con botón de 750 dp, aviso de acceso de 170 dp de alto.

## Referencias de diseño y qué se toma de cada una

Son fuentes secundarias (blogs, sistemas de diseño públicos); no hay guía oficial del estilo «táctil». Se toman principios, no se copia ninguna interfaz.

| Referencia | Qué se toma |
|---|---|
| [Sistema Duolingo (comunidad)](https://open-design.ai/systems/duolingo/) y [shadcn.io](https://www.shadcn.io/design/duolingo/raw) | Botón con relieve inferior de 3-4 px, radio 16, texto en negrita de 16, hundimiento al pulsar: es el origen del estilo actual. Principal y secundario comparten medidas. |
| [Material 3 `ButtonDefaults`](https://developer.android.com/reference/kotlin/androidx/compose/material3/ButtonDefaults) | Cinco tallas de botón (XS a XL) con altura y relleno por talla: base para definir **solo dos** tallas en BPM. |
| [Material: diseño para pantallas grandes](https://developer.android.com/design/ui/large-screens) y [layouts canónicos](https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts) | Lista-detalle, panel de apoyo y feed; rail/menú lateral en pantallas expandidas. |
| [Wix: guía de dashboards](https://dev.wix.com/docs/build-apps/develop-your-app/frameworks/wix-blocks/dashboard-pages/ux-guidelines-for-dashboard-pages-in-blocks), [Carbon: rejilla](https://v9.carbondesignsystem.com/guidelines/grid), [Magento: rejilla de admin](https://r-martins.github.io/m1docs/guides/v2.4/design-styleguide/pagegrid/pagegrid.html) | Rejilla de 12 columnas, contenido máximo ~1248 dp junto a menú fijo. Carbon centra y Wix limita; BPM mantiene 1200 dp a la izquierda. |
| [Bento grids: guía práctica](https://www.saasframe.io/blog/designing-bento-grids-that-actually-work-a-2026-practical-guide) | El tamaño de la tarjeta expresa importancia, una función por tarjeta, mismo diseño interno y mismo hueco entre tarjetas. Solo si hay jerarquía real; si todo pesa igual, rejilla normal. |
| [FoodReady: rediseño HACCP](https://ankurbanerjee.notion.site/Redesigning-the-HACCP-Flow-UI-for-FoodReady-ac37f7ae792845e781294b9973dc20d9), [eEAT HACCP](https://apps.apple.com/app/id1667914032), [FoodDocs](https://www.fooddocs.com/restaurant-checklist-software), [formularios rápidos en cocina](https://kitchennmbrs.app/en/knowledge-base/food-safety-and-haccp/what-can-you-do-visually-with-forms-to-make-them-clear-and-quick-to-fill-out) | Del dominio: consistencia entre pantallas, menos entrada repetida, color por prioridad, checklists con una instrucción visual por paso. |
| [Chips: plano de diseño](https://designsystems.surf/blueprints/chip) y [SAP Fiori: chips](https://www.sap.com/design-system/fiori-design-android/v24-12/components/input-and-selection/chips/usage) | Marca de verificación al seleccionar, objetivo táctil mínimo, no mezclar selección única y múltiple en una misma pantalla. |
| [Lista de auditoría de tokens](https://figr.design/design-system-checklist/design-tokens-styles) y [lista de sistema de diseño](https://eleken.co/blog-posts/design-system-checklist) | Valores fuera de escala, valores escritos a mano en vez de tokens, revisión por alguien distinto del autor. |

## Sistema de tallas propuesto (se decide en la Iteración 0)

**Botones.** Dos tallas: *Normal* (cuerpo 52 dp, la actual) y *Compacta* (cuerpo 44 dp con área táctil de 48 dp, solo dentro de filas y tarjetas densas). Anchos: a contenido con mínimo 160 dp; a ancho completo solo dentro de tarjetas y en pantallas compactas; sin los 7 anchos sueltos. Un botón principal por pantalla o tarjeta. Peso Bold definido en el tema.

**Texto (rol → estilo).** Título de página `headlineMedium` 28 ExtraBold (uno por pantalla). Sección `titleLarge` 22. Título de tarjeta `titleMedium` 16 Bold. Cuerpo `bodyMedium` 16. Apoyo `bodySmall` 13 (nunca como único texto de una tarjeta). Etiqueta `labelMedium` 13. Se eliminan los `fontWeight` sueltos: el peso va en el estilo.

**Tarjetas.** Tres variantes: *Estándar* (relleno 24), *Compacta* (relleno 16, para listas y KPI) y *Destacada* (fondo teal suave, una por pantalla). Dentro de una rejilla todas miden lo mismo por fila. Regla: una tarjeta no es más ancha que su contenido útil; si lleva una sola línea, es una franja o una fila, no una tarjeta.

**Separaciones.** Escala 4 · 8 · 12 · 16 · 24 · 32 (se retiran 6, 10, 14, 20 y 2). Entre secciones 24, entre tarjetas 16, dentro de tarjeta 12, entre líneas de un dato 4.

**Componentes nuevos del sistema** (se reutilizan en todas las pantallas):
- `BpmPageHeader`: título + descripción corta + fila de acciones (principal a la izquierda o a la derecha, siempre en el mismo lugar).
- `BpmStatTile`: KPI con una cifra, etiqueta y apoyo; dos tamaños (normal y destacado de 2 columnas).
- `BpmActionTile`: acceso a un módulo con icono, título, una línea y flecha; toda la tarjeta es el botón, sin botón interior.
- `BpmListRow`: fila densa (iniciales, nombre, correo, chip de rol, acciones) para listas anchas; en móvil se muestra como tarjeta.
- `BpmInlineNotice`: aviso de una línea con icono y «Entendido» (reemplaza las tarjetas de aviso).

## Distribución propuesta por pantalla (a 1920 y a 390)

| Pantalla | Hoy | Propuesta |
|---|---|---|
| **Inicio** | Tarjeta de introducción, aviso, saludo, 7 KPI en 3+3+1 | Saludo y acciones rápidas arriba; KPI en bento: «Cumplimiento de hoy» destacado (2 columnas) y 5 pequeños; debajo «Pendientes» y «Estado de tu espacio» lado a lado |
| **Equipo** | Tarjetas de ~389 dp con 2 personas | `BpmListRow` desde 840 dp (más personas por pantalla); tarjetas en móvil; filtros Activas/Inactivas con `BpmFilterChip`; «Agregar persona» en el encabezado |
| **Empresa** | Tarjeta de organización a ancho completo y 6 tarjetas con botón de 750 dp | Datos de la organización en el encabezado de la página; 6 `BpmActionTile` en 3 columnas; sedes como lista/rejilla compacta |
| **Mi cuenta** | Tarjeta de 1624 dp con 4 botones | Dos columnas: perfil a la izquierda y lista de acciones (dispositivos, invitación, cerrar sesión) a la derecha; una columna en móvil |
| **Aprender** | Intro + tarjetas de curso | Curso destacado («continúa donde lo dejaste») + rejilla de cursos con progreso |
| **Seguimiento / Control** | Catálogo en tarjetas | Lista de plantillas con estado y sede; detalle en panel lateral desde 1200 dp (lista-detalle) |
| **Operación diaria / Incidencias** | Pestañas con chips y tarjetas | Chips unificados, tarjetas compactas con color por prioridad, acción principal en el encabezado |
| **Avisos / Reportes / Investigación** | Anchos propios | Mismo encabezado y contenedor; gráficos y KPI con los componentes comunes |
| **Formularios y login** | 640 dp, tarjeta | Se mantienen; revisar campos, errores y botones con las tallas nuevas |

## Cómo se captura y se compara (sin tocar tus ventanas)

Las capturas anteriores fallaron porque otras ventanas robaron el foco. En el loop **no se envían teclas ni clics a las ventanas del usuario**:

- **Móvil:** emulador `BPM_API35` con `adb exec-out screencap` (independiente de lo que hagas en el PC).
- **Web 1920×1080:** navegador sin ventana (headless) con perfil propio y sesión iniciada con una cuenta de prueba por rol (ADMIN, CHEF, WORKER). Las credenciales van en variables de entorno locales, nunca en el repositorio ni en el chat. **Necesito estas cuentas de ti para la Iteración 1.**
- Cada captura se guarda como «antes» y «después» en `docs/diseno-loop/<iteración>/` y se compara contra la lista de abajo.

## Lista de revisión por pantalla (criterio de salida)

1. Un solo título de página; el título aparece antes que avisos y accesos.
2. Un solo botón principal y todos del mismo alto; anchos solo de la escala permitida.
3. Ninguna tarjeta más ancha que su contenido útil; las filas de una rejilla miden lo mismo.
4. Mismo borde izquierdo en cabecera y contenido; ancho máximo 1200 dp.
5. Separaciones solo de la escala; sin `fontWeight` sueltos.
6. Texto de 13 sp solo como apoyo; contraste ≥ 4,5:1 (3:1 en componentes).
7. Cada estado presente: cargando, vacío, error, sin permiso, sin conexión.
8. Móvil a 360/390/600/840 dp sin desplazamiento horizontal ni texto cortado; objetivos de 48 dp.
9. Foco con teclado visible y en orden lógico; filtros con marca al seleccionar.
10. Se parece al mockup equivalente de `bpm-design-system` y respeta los invariantes.

## Verificación automática (se amplía `scripts/check-spacing.mjs`)

Reglas nuevas, en modo estricto: R6 botón principal con ancho fuera de la escala; R7 `fontWeight` literal fuera del tema; R8 uso directo de `FilterChip` o `Card` de Material; R9 `spacedBy` fuera de escala; R10 tarjeta `fillMaxWidth` con un solo `Text` hijo. Cada iteración corre además `node scripts/check-spacing.mjs --strict` y `./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug`.

## Iteraciones (orden de ejecución)

| # | Alcance | Resultado esperado |
|---|---|---|
| 0 | Base: tallas de botón, roles de texto en el tema, variantes de tarjeta, los 5 componentes nuevos, reglas R6–R10 | Sistema definido y comprobable; sin cambios de pantalla |
| 1 | Cabecera, menú lateral, contenedor y avisos (`BpmInlineNotice`) | Todas las pantallas comparten estructura |
| 2 | Equipo | Lista ancha, encabezado con acciones |
| 3 | Inicio y KPI | Bento con jerarquía |
| 4 | Empresa y Mi cuenta | Tiles y dos columnas |
| 5 | Aprender y Academia | Curso destacado y rejilla |
| 6 | Seguimiento, Control y Operación diaria | Listas y detalle |
| 7 | Incidencias, Avisos, Reportes, Investigación | Mismo encabezado y componentes |
| 8 | Formularios, login y estados vacíos | Tallas y errores unificados |
| 9 | Pasada móvil completa (360/390/600/840) | Sin desbordes ni objetivos pequeños |
| 10 | Accesibilidad, movimiento (`bpm-motion`) y rendimiento (`bpm-performance`) | Cierre y comparación antes/después |

**Fin del loop:** todas las pantallas cumplen la lista, 0 avisos del verificador, pruebas y builds en verde. Tope de seguridad: 25 iteraciones.

## Cómo se ejecuta

Cada iteración: leer este plan y el registro → tomar la siguiente fila pendiente → capturar «antes» → cambiar solo ese alcance → verificar → capturar «después» → anotar en el registro y hacer un commit → parar. Se lanza con `/loop` sin intervalo (ritmo automático) y el texto: «Ejecuta la siguiente iteración pendiente de docs/superpowers/plans/2026-11-18-loop-revision-diseno.md». Se detiene sola cuando no quedan filas pendientes o ante cualquier fallo de verificación.

## Riesgos

- Cambiar la talla de texto o de botones afecta a todas las pantallas a la vez: por eso la Iteración 0 va primero y se revisa antes de seguir.
- La lista de Equipo y el panel de Control cambian la estructura, no solo el estilo: se validan con el usuario antes de extenderlos a otras pantallas.
- Sin cuentas de prueba por rol no se puede capturar Control ni Aprender (una cuenta de administrador general no tiene acceso).
- iOS no se puede verificar sin macOS.

## Registro de ejecución

Cada iteración anota fecha, alcance, resultado de verificación y commits. Las reglas nuevas del verificador se numeran R7–R11 porque R6 ya existía (chips en `Row`).

### Iteración 0: base del sistema (19/11/2026)

- **Hecho:** `BpmButtonSize` (Normal 52 dp / Compacta 44 dp) en `RaisedButton` y `BpmSecondaryButton`; `BpmCardVariant` (Estándar, Compacta, Destacada) en `BpmCard`; escala `xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32` en `BpmSpacing`; peso Bold de botones tomado de `labelLarge`; componentes `BpmPageHeader`, `BpmStatTile`, `BpmActionTile`, `BpmListRow`, `BpmInlineNotice`; reglas R7 (ancho del botón principal), R8 (`fontWeight` suelto), R9 (`FilterChip`/`Card` de Material), R10 (`spacedBy` fuera de escala), R11 (tarjeta con un solo `Text`).
- **Sin cambios de pantalla:** los valores por defecto reproducen lo anterior; los componentes nuevos aún no se usan.
- **Línea base del verificador (avisos, modo normal):** R7 34 · R8 204 · R9 1 · R10 24 · R11 1 · R1–R6 0 → 264 avisos, 0 errores. Cada iteración debe bajar estas cifras; el modo `--strict` se exige al cerrar el loop.
- **Verificación:** `jvmTest` (con 5 pruebas nuevas de iniciales), `wasmJsBrowserDistribution` y `assembleDebug` en verde; `node --test web/server.test.mjs` 6/6.
- **Pendiente de esta iteración:** `bodyMedium` sigue en 14 sp. Subirlo a 16 sp cambia todas las pantallas a la vez, así que se hace junto con la Iteración 1 y se comprueba con capturas.
- **Commits:** `96394d7` (tallas, variantes, escala), `893aad5` (componentes nuevos) y el de reglas del verificador y pruebas.
