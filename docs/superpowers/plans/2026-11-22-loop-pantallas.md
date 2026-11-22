# Loop de revisión de pantallas según las skills de diseño

> Una iteración = un grupo de pantallas. Cada una se dibuja, se compara con las skills, se corrige, se verifica y se registra antes de pasar a la siguiente.

**Rama:** `diseno/loop-pantallas` (desde `main` del 22/11/2026). Un commit por iteración, sin `push`.
**Skills que mandan:** `bpm-spacing` (espaciado, distribución, accesibilidad), `bpm-motion` (movimiento) y `bpm-performance` (solo al cerrar). Contrato de pantallas: `specs/LAYOUT.md`. Inventario de rutas y patrones: `docs/superpowers/plans/2026-11-19-inventario-patrones.md`.
**Reemplaza** al plan `2026-11-18-loop-revision-diseno.md` (que vive en la rama atrasada `diseno/loop-mejoras`): `main` ya migró todas las rutas a un patrón de `BpmScreenPatterns.kt` y el verificador está en 0 avisos.

## Qué NO cambia (identidad)

Teal `#005B59`, acento `#ED5700` solo en detalles, fondo `#F5F6F6`, superficies blancas, borde de 2 dp con relieve inferior, radios 12/16/24, mascota tomate, logo gorro con visto, textos en español breves. No se inventan tokens ni componentes si ya existe uno en `core/designsystem`.

## Cómo se ve cada pantalla (sin tocar las ventanas del usuario)

No se envían teclas ni clics a ventanas del usuario, y no hace falta iniciar sesión: se usa el arnés de capturas fuera de pantalla de `composeApp/src/jvmTest/kotlin/pe/bpm/screenshots/` (`ScreenSpec` + `WorkerFixtures`).

```bash
./gradlew :composeApp:jvmTest --tests '*Screenshots*' --console=plain   # PNG en composeApp/build/screenshots/
```

- Salida: `<pantalla>-<ancho>.png` (390, 768, 1280), `-full.png` y `sheet-<pantalla>.png` (los tres anchos juntos). Se miran con la herramienta de lectura de imágenes.
- Si una pantalla del grupo no tiene `ScreenSpec`, **se añade** (con repositorios falsos en `WorkerFixtures` o un fixture nuevo por rol) antes de revisarla. Sin captura no hay revisión visual: se anota «solo código» y se explica por qué.
- El emulador `BPM_API35` (`adb exec-out screencap`, desde Bash, nunca con redirección de PowerShell) sirve para confirmar en el móvil real lo que el arnés muestre dudoso; solo la pantalla de ingreso se ve sin cuenta de prueba.

## Lista de revisión por pantalla (criterio de salida)

1. Un solo título de página y va primero; avisos y accesos después.
2. Un solo margen lateral compartido por cabecera y contenido (`BpmSpacing.gutter`); la pantalla alojada no repite relleno.
3. Separaciones solo de la escala de `bpm-spacing §1`; lo cercano se agrupa, lo distinto se separa.
4. Columnas, detalle al lado y campos en pares por **ancho útil**, no por ventana; la pantalla no usa `BoxWithConstraints` ni `widthIn(max=)` sueltos (R12/R15 de `LAYOUT.md`).
5. Botones con las tallas del sistema (`BpmButtonSize`), un solo principal por pantalla o tarjeta; chips con `BpmFilterChip`.
6. Ninguna tarjeta más ancha que su contenido; las filas de una rejilla miden lo mismo; una tarjeta con una línea es una franja.
7. Texto de apoyo ≥ 14 sp, contraste ≥ 4,5:1 (3:1 en componentes), objetivos táctiles ≥ 48 dp.
8. Estados presentes: cargando, vacío, error, sin permiso, sin conexión.
9. 390 / 768 / 1280 sin desborde horizontal ni texto cortado; teclado y foco en orden lógico.
10. Movimiento solo con las API y duraciones de `bpm-motion`; sin recomposiciones evitables (`bpm-performance`) en lo que se toque.

## Verificación de cada iteración (todo debe pasar)

```bash
node scripts/check-spacing.mjs --strict
./gradlew :composeApp:jvmTest --console=plain
./gradlew :composeApp:assembleDebug --console=plain     # y wasmJsBrowserDistribution cada 3 iteraciones
```

Gradle se lanza con `./gradlew` desde **Git Bash** (no hay `gradlew.bat`). iOS no se puede verificar sin macOS.

## Iteraciones (grupos, en orden)

| # | Pantallas (rutas) | Estado |
|---|---|---|
| 1 | Acceso: Ingreso `acceso`, Conexión `conexion`, Verificar constancia `verificar/{codigo}`, Elegir organización `organizaciones`, Aceptar invitación `cuenta/invitacion` | pendiente |
| 2 | Inicio `inicio` (panel e indicadores) | pendiente |
| 3 | Mi cuenta `cuenta`, Mis dispositivos `cuenta/dispositivos`, Cambios pendientes `cambios-pendientes`, Mis avisos `avisos` | pendiente |
| 4 | Empresa `empresa`, Nueva organización / sede, Equipo `equipo`, Agregar persona, Cuentas y equipo `empresa/cuentas` | pendiente |
| 5 | Aprender `aprender`, Curso / lección `aprender/{courseId}`, Logros, Rutas | pendiente |
| 6 | Academia (gestión) `empresa/academia`, Encuesta del estudio `estudio`, Investigación `investigacion` | pendiente |
| 7 | Control `control`, Catálogo de plantillas, Detalle de plantilla | pendiente |
| 8 | Revisión de tu sede (ejecución por pasos), Mis revisiones, Controles en este dispositivo, Control programado | pendiente |
| 9 | Seguimiento `seguimiento`, Detalle de revisión | pendiente |
| 10 | Plantillas de control (gestión), Nueva plantilla, Versiones, Programar control, Controles programados | pendiente |
| 11 | Operación diaria (turnos, movimientos de insumos, limpieza) | pendiente |
| 12 | Incidencias (`control/`, `empresa/`, `tareas/`) y Mis tareas `tareas` | pendiente |
| 13 | Reportes `reportes`, Reglas de avisos `empresa/avisos`, Normativa, Webhooks | pendiente |
| 14 | Estados vacío / error / sin conexión y modo oscuro de todo lo anterior (pasada transversal) | pendiente |
| 15 | Cierre: `bpm-motion` y `bpm-performance` sobre lo tocado; accesibilidad; comparación antes/después | pendiente |

## Cómo se ejecuta cada iteración

1. Leer este plan y el registro; tomar la **primera** fila `pendiente`. Si no hay, terminar el loop.
2. Verificar que el árbol de trabajo está limpio (`git status`); si no, parar y avisar.
3. Capturar «antes» de cada pantalla del grupo (añadiendo `ScreenSpec` si falta) y guardarlas en `docs/diseno-loop/<iteración>/antes/`.
4. Revisar contra la lista de arriba y contra `bpm-spacing`; escribir los hallazgos en el registro con `archivo:línea`.
5. Corregir **solo** ese grupo, reutilizando tokens y componentes existentes. Un cambio que afecte a todas las pantallas (tema, tokens, componente compartido) no se hace dentro de un grupo: se anota en «Para decidir» y se pregunta al usuario.
6. Correr la verificación; capturar «después» en `docs/diseno-loop/<iteración>/despues/` y comprobar cada ítem de la lista.
7. Anotar fecha, hallazgos, cambios, cifras del verificador, resultado de pruebas y commit en el registro; marcar la fila `hecha`; hacer un commit con el trabajo de la iteración (sin `push`). **Parar.**

**Paradas:** cualquier verificación en rojo que no se arregle en la misma iteración, cambios sin commitear ajenos, una decisión que afecte a todas las pantallas, o un tope de 20 iteraciones. Ante cualquiera: dejar el registro al día y avisar al usuario.

## Para decidir (el loop no lo decide solo)

_Vacío._

## Registro de ejecución

_Sin iteraciones todavía._
