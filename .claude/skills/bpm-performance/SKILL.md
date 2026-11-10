---
name: bpm-performance
description: Optimización de rendimiento de la app BPM (Compose Multiplatform - web wasm, Android, iOS) y de su capa de API/red (Ktor, proxy Node). Úsala cuando la app se sienta lenta, pese mucho, tarde en cargar, recomponga de más, las listas se atasquen, haya muchas solicitudes o antes de publicar - y para medir antes y después de cada mejora. Orquesta las referencias de compose-performance-skills (skydoves) adaptadas a este proyecto.
---

# Rendimiento BPM (multiplataforma)

Regla única: **medir → diagnosticar → corregir una cosa → volver a medir**. Sin número
«antes», no hay mejora demostrable. Optimizar lo que no se midió es adivinar.
Se complementa con [bpm-spacing](../bpm-spacing/SKILL.md) (layout) y
[bpm-motion](../bpm-motion/SKILL.md) (animación): la velocidad también es diseño.

## Perfil del proyecto (verificar si cambia)

| Dato | Valor | Consecuencia |
|---|---|---|
| Kotlin / Compose | 2.3.21 / 1.11.1 | *Strong skipping* activo por defecto: los parámetros «inestables» no impiden omitir. No perseguir el 100 % de estabilidad |
| Red | Ktor 3.4.3 (OkHttp, Darwin, JS) con `HttpTimeout` 12 s | Un solo `HttpClient` compartido; ver §3 |
| Web | Distribución wasm: `skiko.wasm` 7,2 MB + app 8,7 MB sin comprimir | El peso y la caché dominan el arranque web; ver §2 |
| Listas | `Column` + `forEach` + `BpmCardGrid` con paginación del backend | Aceptable por página; ver §1 |

Estado medido (informes del compilador, 2026-11): **103 de 106 composables son
reiniciables y omitibles**; las clases «inestables» son sobre todo estados, DTO y
repositorios. No hay trabajo urgente de estabilidad.

## 0. Cómo medir

```bash
# Informes del compilador de Compose (estabilidad y omisión) → composeApp/build/compose_compiler
./gradlew :composeApp:compileKotlinJvm -PcomposeReports=true --rerun-tasks
# Peso real entregado por el servidor web (brotli/gzip) y caché
node web/server.mjs   # y en otra terminal:
curl -s -H "Accept-Encoding: br" -o /dev/null -w "%{size_download}\n" http://localhost:3000/<archivo>.wasm
```

- **Web:** DevTools → Network (con «Disable cache» apagado en la segunda carga), Lighthouse y
  Performance. Medir en modo *release* (`wasmJsBrowserDistribution`), nunca en desarrollo.
- **Android:** build *release* con R8 en dispositivo real; Macrobenchmark
  (`StartupTimingMetric`, `FrameTimingMetric`). El emulador no es representativo.
- **iOS:** build *release* en dispositivo; Instruments (Time Profiler y Animation Hitches).
- Registrar cada número en `docs/perf-<fecha>.md` antes de cambiar nada.

## 1. Rendering (Compose)

Orden de revisión según el síntoma → referencia (en `references/`):

| Síntoma | Causa probable | Referencia |
|---|---|---|
| Recompone toda la pantalla al desplazar o animar | Estado leído en composición | [deferring-state-reads](references/deferring-state-reads.md) |
| Muchas recomposiciones con parámetros iguales | Tipo inestable / sin omisión | [diagnosing-compose-stability](references/diagnosing-compose-stability.md), [stabilizing-compose-types](references/stabilizing-compose-types.md), [using-strong-skipping-correctly](references/using-strong-skipping-correctly.md) |
| Saber por qué una clase es «unstable» | Inferencia del compilador | [understanding-stability-inference](references/understanding-stability-inference.md) |
| Lista larga que se atasca | Falta de `key`/`contentType`, sin carga perezosa | [optimizing-lazy-layouts](references/optimizing-lazy-layouts.md), [configuring-lazy-prefetch](references/configuring-lazy-prefetch.md) |
| `BoxWithConstraints` o `Scaffold` lentos en listas | Subcomposición en la medición | [avoiding-subcomposition-pitfalls](references/avoiding-subcomposition-pitfalls.md) |
| `LaunchedEffect` se reinicia solo | Llave equivocada | [using-efficient-effects](references/using-efficient-effects.md) |
| Flujos consumen en segundo plano | `collectAsState` sin ciclo de vida | [collecting-flows-safely](references/collecting-flows-safely.md) |
| Modificador propio recompone | `composed {}` | [migrating-to-modifier-node](references/migrating-to-modifier-node.md), [ordering-modifier-chains](references/ordering-modifier-chains.md) |
| `derivedStateOf` no actúa | Uso incorrecto | [choosing-derivedstateof](references/choosing-derivedstateof.md) |
| Comprobar un composable concreto | Sin instrumentación | [debugging-recompositions](references/debugging-recompositions.md), [tracing-recompositions-at-runtime](references/tracing-recompositions-at-runtime.md) |
| Auditoría completa con informe | Sin punto de partida | [auditing-compose-performance](references/auditing-compose-performance.md) |

**Adaptación a este proyecto** (las referencias están escritas para Jetpack Compose en Android):
- `collectAsStateWithLifecycle` es de Android; en `commonMain` usar `collectAsState` y detener
  el trabajo desde el `ViewModel` (`stateIn(WhileSubscribed)`).
- `LazyColumn`/`LazyVerticalGrid`: usar solo cuando la lista pueda superar ~50 elementos
  visibles a la vez. Las páginas del backend son cortas; `BpmCardGrid` con `Column` es suficiente.
  Si una lista crece, pasar a `LazyVerticalGrid(GridCells.Adaptive(minSize))` con `key` e ID estable.
- `BpmCardGrid` usa `BoxWithConstraints` (subcomposición): correcto **fuera** de un ítem
  perezoso; no anidarlo dentro de `LazyColumn`.
- `bpmAppear` es `Modifier.Node` (no `composed`) y lee el progreso en la capa: patrón a copiar.
- Perfiles base, R8 y Macrobenchmark: solo **Android**. Web e iOS se miden con las
  herramientas de §0.
- No perseguir 100 % de omisión: es un diagnóstico, no el objetivo. El objetivo son cuadros
  estables y arranque rápido en dispositivo real.

## 2. Web (wasm): peso, caché y arranque

Aplicado en [web/server.mjs](../../../web/server.mjs): compresión brotli/gzip con caché en
memoria, `Cache-Control: public, max-age=31536000, immutable` para archivos con hash en el
nombre, `no-cache` + `ETag`/304 para el resto y `Vary: Accept-Encoding`. Resultado medido:
**15,8 MB → 4,7 MB** por primera visita (−70 %) y **0 bytes** de wasm en visitas siguientes.
Mantener y vigilar:

1. Todo recurso nuevo con hash en el nombre sale `immutable`; `index.html`, `composeApp.js` y
   `config.json` nunca se cachean sin revalidar (cambiarían sin avisar).
2. `skiko.wasm` (≈ 7 MB) carga completo antes de que arranque la app: es el costo fijo de Compose
   en web. Reducir lo propio: no añadir librerías pesadas a `wasmJsMain`, revisar `composeResources`
   (fuentes, imágenes) y servir imágenes en WebP/SVG.
3. Probar la compresión detrás del proxy de producción (Railway): si ya comprime, evitar doble
   compresión; si no, la del servidor Node aplica.
4. Mostrar un indicador de carga ligero en `index.html` mientras descarga el wasm.
5. Evaluar optimizaciones del compilador wasm (Binaryen más agresivo) midiendo tamaño y tiempo de
   compilación; la mejora reportada por la comunidad ronda el 20 % del wasm propio.

## 3. API y red (Ktor + proxy)

Estado actual: un `HttpClient` por fábrica con `HttpTimeout` (12 s), JSON con
`ignoreUnknownKeys`, sin registro de cuerpos ni URL firmadas; el proxy Node valida listas
cerradas y limita respuestas.

Lista de verificación al añadir o revisar una llamada:
1. **Un cliente compartido** (`createHttpClient`); nunca crear uno por pantalla.
2. **Paginar y filtrar en el backend**; no traer listas completas para filtrarlas en el cliente.
3. **Búsqueda con espera** (*debounce* 300–400 ms) y cancelar la solicitud anterior al escribir o
   salir de la pantalla (cancelar la corrutina del `ViewModel`).
4. **Paralelizar lo independiente** (`async`/`awaitAll`) y no encadenar solicitudes que no dependen
   entre sí; evitar el patrón N+1 (una solicitud por fila).
5. **Reintentos solo en lecturas idempotentes (GET)**, con espera exponencial y variación aleatoria,
   máximo 2–3 intentos; **nunca reintentar** POST/PUT sin clave de idempotencia (duplicaría datos).
6. **Respetar 429/503**: mostrar el mensaje sanitizado y no insistir en bucle.
7. **Caché de lectura:** reutilizar el último dato mientras se refresca (mostrar → revalidar) y
   usar `ETag`/`If-None-Match` donde el backend lo ofrezca; datos sensibles de otra
   organización/usuario nunca se reutilizan (validar tenant, según AGENTS.md).
8. **Tamaño de carga:** pedir solo campos necesarios; comprimir (gzip) las respuestas JSON grandes;
   fotos: reducir y comprimir en el cliente antes de subir (ya limitadas por política de media).
9. **Temporizadores adecuados:** conexión 8 s y solicitud 12 s; subir archivos grandes con su
   propio tiempo, no ampliar el global.
10. **Sin trabajo en el hilo de UI:** serialización y E/S fuera de `Dispatchers.Main`.
11. Seguridad intacta: ninguna clave o credencial al cliente; el proxy conserva su lista cerrada.

## 4. Verificación y cierre

```bash
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
node --test web/*.test.mjs
node scripts/check-spacing.mjs --strict
```

Una optimización se da por buena solo si: (a) hay número «antes» y «después» del mismo escenario,
(b) las pruebas pasan, (c) no cambió el comportamiento visible ni se omitió validación de tenant o
permisos, y (d) se registró en `docs/perf-<fecha>.md`. Una mejora por cambio (commit), para poder
revertir.

## Fuentes

- skydoves, [compose-performance-skills](https://github.com/skydoves/compose-performance-skills)
  (Apache-2.0, commit 1b32f81, copiadas en `references/`; licencia en
  `references/LICENSE-skydoves-compose-performance-skills.txt`). Escritas para Jetpack Compose en
  Android; ver las adaptaciones de §1. Sus enlaces relativos internos (`../../...`) no resuelven aquí.
- Android Developers, [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/window-size-classes) y guías de rendimiento de Compose.
- Comunidad Kotlin (Slack), [tamaño y arranque de Kotlin/Wasm](https://slack-chats.kotlinlang.org/t/16406831/question-wasmjs-compose-what-is-the-typical-size-of-wasm-fil) (anecdótico).
