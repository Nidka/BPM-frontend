---
name: bpm-spacing
description: Diseño de UI BPM para móvil y web (Compose Multiplatform) - espaciado, alineación, rejillas adaptativas, jerarquía, objetivos táctiles, estados y accesibilidad, basado en bpm-design-system, Material 3 adaptive y WCAG 2.2. Úsala al crear o cambiar cualquier pantalla, tarjeta, lista, cabecera o formulario, y para verificar que todo cumpla antes de cerrar un cambio de UI.
---

# Diseño de UI BPM (móvil y web)

Una sola base de código para Android, iOS y web: la pantalla se adapta al **ancho disponible**,
nunca al tipo de dispositivo (no existe `isTablet`). La fuente de verdad visual es
`bpm-design-system` (`prototype.css`, `playful.css`, mockups `rol-*.png`, `operacion-*.png`);
los tokens viven en `BpmSpacing` y `BpmTheme`
([BpmTheme.kt](../../../composeApp/src/commonMain/kotlin/pe/bpm/core/designsystem/BpmTheme.kt)).
Antes de inventar un valor o un componente, buscar el token o componente existente.

## 1. Espaciado y alineación

1. **Un solo margen lateral.** Cabecera y contenido comparten `BpmSpacing.gutter(wide)`
   (32 dp amplia, 20 dp compacta). Un componente con relleno propio (`TopAppBar` reserva
   16 dp) se compensa para que su primer borde visible coincida con el del contenido.
2. **Una pantalla alojada no repite el margen.** Las pantallas que muestra `WorkspaceScreen`
   (avisos, incidencias, operación, plantillas) no ponen `.padding(16.dp)` en su raíz: el
   contenedor ya aporta el margen. Repetirlo desalinea 16 dp respecto a la cabecera.
3. **Ritmo vertical.** Entre secciones `BpmSpacing.section` (20 dp); entre tarjetas
   `BpmSpacing.grid` (16 dp); relleno de tarjeta `BpmSpacing.card` (24 dp); entre bloques
   dentro de una tarjeta 14 dp; entre líneas de un mismo dato 4 dp (se agrupan, no se separan).
4. **Escala permitida** (dp): 0, 1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40,
   48, 56, 64, 96 (96 solo para dejar libre un botón flotante).
5. **Proximidad.** Lo que va junto se acerca, lo que es distinto se separa más. Si todo
   está a 14 dp, nada parece agrupado (error del historial de revisiones antes de
   agrupar responsable y fechas).

## 2. Distribución adaptativa

1. **Decide por ancho disponible, no por ventana.** Dentro de una pantalla usar
   `BoxWithConstraints` / `BpmCardGrid`; el ancho útil es menor que el de la ventana por
   la barra lateral. Referencia Material/Android (clases de ancho de ventana): compacta
   < 600 dp, media 600–839, expandida 840–1199, grande 1200–1599, extragrande ≥ 1600.
   Breakpoints propios ya en uso: menú lateral desde 950 dp (`WorkspaceScreen`), login
   dividido desde 900 dp, intro en dos columnas desde 600 dp.
2. **Listas de registros → `BpmCardGrid`.** Tantas columnas como quepan con
   `minCardWidth` (320–400 dp típico) y `maxColumns` 2–3; altura igual por fila. No
   apilar una tarjeta por fila a ancho completo: deja el lado derecho vacío y obliga a
   desplazarse de más. Excepción: listas **secuenciales** (cola de envíos, preguntas en
   orden) se quedan apiladas y se marcan con `// lista-apilada: motivo`.
3. **Móvil primero, luego ampliar.** Diseñar la columna única y después decidir qué gana
   con más espacio (columnas, panel de detalle). Navegación: barra inferior < 950 dp,
   lateral desde 950 dp. Con ventana baja (móvil horizontal) evitar paneles dobles.
4. **Patrones canónicos** (Material adaptive): lista–detalle (en compacta se muestra uno
   y «Volver» regresa a la lista), panel de apoyo, feed en rejilla. Reutilizar antes de
   inventar otro.
5. **Ancho de lectura.** Formularios y texto largo no superan ~900 dp (`widthIn(max=…)`);
   las rejillas sí pueden crecer. Si se centra contenido con tope, centrar también la cabecera.
6. **Tarjeta de acceso:** título, una descripción corta y botón a todo el ancho de la tarjeta
   (`ActionCard`). Un solo botón primario por tarjeta.

## 3. Objetivos táctiles y entrada

1. **48 dp mínimo** (`BpmSpacing.touch`) en todo control interactivo; separación ≥ 8 dp entre
   controles contiguos. Referencias: Material 48 dp, Apple 44 pt, WCAG 2.2 AA (2.5.8) 24 px y
   AAA (2.5.5) 44 px. Un icono de 24 dp igualmente lleva un área de 48 dp.
2. **Web:** todo es operable con teclado (Tab/Enter/Espacio), foco visible, y nada depende
   solo de *hover*. **Móvil:** gestos con alternativa en pantalla; el teclado no tapa el
   campo activo (`imePadding`) y se respetan las zonas seguras (`safeDrawingPadding`).
3. Campos con etiqueta visible y error junto al campo; no usar solo el placeholder.

## 4. Jerarquía y contenido

1. **Un título por pantalla** (`headlineMedium`); secciones `titleLarge`/`titleMedium` en
   negrita; datos secundarios `bodySmall` en `onSurfaceVariant` (gris). El dato clave de una
   tarjeta (cumplimiento, estado, total) va en negrita o como etiqueta (`Eyebrow`).
2. **Contraste:** texto normal ≥ 4,5:1 y componentes de interfaz ≥ 3:1 (WCAG 1.4.11). El
   naranja de acento es de detalle, no texto pequeño.
3. **Estados completos** en toda pantalla con datos: cargando (`LinearProgressIndicator`),
   vacío (`EmptyState` con siguiente paso), error (mensaje saneado + «Reintentar»), sin
   permiso, sin conexión. Nunca mostrar éxito con datos simulados.
4. **Textos en español**, claros y breves; el botón dice la acción («Gestionar plantillas»).
5. Bordes 2 dp, radios 12/16/24 dp y relieve 3 dp vienen del tema; no redefinirlos. Nada de
   `Modifier.shadow` suelto.
6. Movimiento: ver la skill [bpm-motion](../bpm-motion/SKILL.md); respetar «reducir movimiento».

## 5. Verificación (obligatoria al cerrar un cambio de UI)

```bash
node scripts/check-spacing.mjs --strict
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution
```

El verificador falla con código 1 por: R1 márgenes con literales, R4 relleno duplicado en
pantalla alojada; y en `--strict` también por R2 valores fuera de escala, R3 controles
< 48 dp y R5 listas de tarjetas apiladas sin marcar. Después, revisar en el navegador
(`node web/server.mjs`, http://localhost:3000) y en un móvil o emulador, a **360, 390, 600,
840, 1200 y 1600 dp**, con especial atención en los umbrales (599/600, 949/950):

- el borde izquierdo de cabecera y contenido coincide;
- las listas forman rejilla y el número de columnas cambia sin saltos raros;
- sin desplazamiento horizontal, texto cortado ni controles < 48 dp;
- el foco con Tab es visible y recorre en orden lógico;
- cada estado (cargando, vacío, error) se ve bien;
- se contrasta con el mockup equivalente de `bpm-design-system`.

Si una regla nueva sale de un mockup o de una guía, añadirla aquí y, si es medible, a
`scripts/check-spacing.mjs`.

## Fuentes

- Android Developers, [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/window-size-classes) y [Canonical layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts).
- Kotlin Multiplatform, [Adaptive layouts en Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html).
- W3C, WCAG 2.2: 2.5.8 Target Size (Minimum), 2.5.5 Target Size, 1.4.11 Non-text Contrast.
- Material Design 48 dp y Apple HIG 44 pt (valores contrastados en fuentes secundarias; verificar en las guías oficiales ante una auditoría).
