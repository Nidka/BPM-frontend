---
name: bpm-spacing
description: Reglas de espaciado, alineación y distribución de la UI BPM (Compose Multiplatform), tomadas de bpm-design-system. Úsala al crear o cambiar cualquier pantalla, tarjeta, rejilla, cabecera o formulario, y para verificar que todo cumpla antes de cerrar un cambio de UI.
---

# Espaciado y distribución BPM

Fuente: biblioteca `bpm-design-system` (`prototype.css`, `playful.css`, `styles.css`,
mockups `rol-*.png`, `operacion-*.png`). Los tokens viven en `BpmSpacing`
([BpmTheme.kt](../../../composeApp/src/commonMain/kotlin/pe/bpm/core/designsystem/BpmTheme.kt)).
No inventar valores por pantalla: usar los tokens.

## Reglas

1. **Un solo margen lateral.** Cabecera y contenido comparten `BpmSpacing.gutter(wide)`:
   32 dp en vista amplia (≥ 950 dp) y 20 dp en compacta. Nunca `if (wide) 32.dp else 16.dp`
   suelto. Si un componente trae relleno propio (p. ej. `TopAppBar` reserva 16 dp), se
   compensa para que su primer borde visible coincida con el del contenido.
2. **Separación vertical entre secciones:** `BpmSpacing.section` (20 dp).
3. **Rejilla de tarjetas:** 2 columnas en vista amplia y 1 en compacta, separación
   `BpmSpacing.grid` (16 dp), tarjetas de una fila con la misma altura
   (`IntrinsicSize.Max` + `fillMaxHeight`). Es la distribución del mockup «Gestión
   principal»; no apilar tarjetas de acceso una por fila en pantallas amplias.
4. **Tarjeta:** relleno `BpmSpacing.card` (24 dp), contenido separado 14 dp, título,
   descripción y botón a todo el ancho de la tarjeta, alineado al fondo.
5. **Objetivo táctil mínimo 48 dp** (`BpmSpacing.touch`) en todo control interactivo.
6. **Escala permitida** para relleno y separación (dp): 0, 1, 2, 3, 4, 6, 8, 10, 12, 14,
   16, 18, 20, 24, 28, 32, 40, 48, 56, 64, 96 (96 solo para dejar libre un botón flotante).
7. **Excepción documentada:** el login usa composición dividida con márgenes propios
   (48/24 dp por panel) en `IdentityScreen`.
8. Bordes de 2 dp, radios 12/16/24 y relieve de 3 dp vienen del tema; no redefinirlos.
9. Los textos van en español y los comentarios nuevos también.

## Verificación (obligatoria al cerrar un cambio de UI)

```bash
node scripts/check-spacing.mjs --strict
```

Falla con código 1 si hay márgenes laterales con literales (R1) o, en `--strict`,
valores fuera de escala (R2) y controles menores de 48 dp (R3). Después:

1. `./gradlew :composeApp:wasmJsBrowserDistribution` y `node web/server.mjs`.
2. Abrir http://localhost:3000 y comprobar en ancho amplio (≥ 950 dp) y en 390 dp:
   - el borde izquierdo de la cabecera coincide con el del contenido;
   - las tarjetas de acceso forman rejilla 2×N (amplia) o 1×N (compacta);
   - no hay desplazamiento horizontal ni texto cortado;
   - los botones miden al menos 48 dp.
3. Contrastar contra el mockup equivalente de `bpm-design-system` si existe.

Si una regla nueva sale de un mockup, añadirla aquí y, si es medible, a
`scripts/check-spacing.mjs`.
