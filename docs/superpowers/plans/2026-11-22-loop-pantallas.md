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

1. Un solo título de página y va primero; avisos y accesos después. Las pantallas alojadas con `secondaryEditor` no reciben cabecera del shell: llevan su propio `BpmPageHeader`.
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
| 1 | Acceso: Ingreso `acceso`, Conexión `conexion`, Verificar constancia `verificar/{codigo}`, Elegir organización `organizaciones`, Aceptar invitación `cuenta/invitacion` | hecha |
| 2 | Inicio `inicio` (panel e indicadores) | hecha |
| 3 | Mi cuenta `cuenta`, Mis dispositivos `cuenta/dispositivos`, Cambios pendientes `cambios-pendientes`, Mis avisos `avisos` | hecha |
| 4 | Empresa `empresa`, Nueva organización / sede, Equipo `equipo`, Agregar persona, Cuentas y equipo `empresa/cuentas` | hecha |
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

**Paradas:** cualquier verificación en rojo que no se arregle en la misma iteración, cambios sin commitear ajenos, una decisión global de la que dependa la iteración en curso, o un tope de 20 iteraciones. Las decisiones globales que no bloquean (componentes compartidos, tema) se acumulan en «Para decidir» y se preguntan juntas al llegar a la iteración 14. Ante cualquiera: dejar el registro al día y avisar al usuario.

## Para decidir (el loop no lo decide solo)

- Inicio de ADMIN y CHEF (iteración 2): el panel suma dos rejillas de tarjetas con columnas distintas (indicadores de cumplimiento en 4 columnas y «Sedes / Cursos / Controles» en 3), más la tarjeta de bienvenida «Capacitación y buenas prácticas» y «Estado de tu espacio». Los conteos «Sedes / Cursos / Controles» no aparecen en otro lugar; quitar o fusionar estos bloques es una decisión de producto, no de espaciado. Decidir si se mantienen.
- Enlaces de texto sin alinear (iteración 1 y 3): `BpmTextButton` lleva 12 dp de relleno lateral y, aun con relleno 0, Material centra el texto en un ancho mínimo de 58 dp. Los textos cortos («Volver») quedan unos 4 dp a la derecha del borde y los enlaces de `BpmRecordCard` («Revocar», «Descartar») unos 10 dp. Se corrigió en el ingreso con `AuthLink`; un arreglo común sería una opción `flushStart` en `BpmTextButton` y que `BpmRecordCard` la use, lo que cambia todas las pantallas con enlaces.
- Título truncado en tarjetas con chip largo (iteración 3): en 390 dp «Registro de limpieza de campana» queda cortado a dos líneas junto a «Necesita tu revisión». Opciones: chip debajo del título en compacta, o aceptar el recorte.

## Registro de ejecución


### Iteración 1: Acceso (22/11/2026)

- **Capturas:** `docs/diseno-loop/1/antes` y `despues` (hojas de 390/768/1280). Se añadió `AccessScreens.kt` al arnés (ingreso en 5 modos, conexión en 2 estados, verificación de constancia válida/inválida, selector de organización, aceptar invitación) y el filtro `BPM_SCREENSHOTS_ONLY=prefijo1,prefijo2` (con `--rerun` en Gradle para que no salte la prueba por caché).
- **Hallazgos y cambios:**
  - Ingreso (`IdentityScreen.kt`): el selector «Entrar / Crear cuenta» era una copia manual de `BpmSegmentedTabs` → ahora usa el componente del sistema (48 dp, `Role.Tab`); no cambia de modo con una petición en curso.
  - Ingreso: «¿Olvidaste tu contraseña?», «Volver a iniciar sesión», «Recuperar contraseña» y «Ya confirmé mi correo» quedaban sangrados 12 dp respecto a los campos y a «Mostrar contraseña» → `AuthLink` (enlace con relleno lateral 0, 48 dp de alto) para todos.
  - Verificar constancia (`CertificateVerificationScreen.kt`): los cuatro datos de la constancia estaban a la misma distancia que el título y el código → agrupados con 4 dp entre líneas.
  - Sin cambios: conexión, selector de organización e invitación cumplen la lista (un título, margen único, botones de talla correcta, texto ≥ 14 sp).
- **Falsos positivos descartados:** «0.9 %» en la constancia era mi fixture (la app usa escala 0–100); el alto de 3600 px del ingreso a 1280 es de la captura «completa», no de la pantalla.
- **Verificación:** `check-spacing --strict` 0 errores y 0 avisos; `jvmTest` y `assembleDebug` en verde; 33 capturas sin fallos.
- **Para decidir (no tocado):** `PageNavigation` (Anterior/Siguiente) mide 44 dp y convive con botones de 52 dp en la misma tarjeta del selector de organización; es un componente compartido, se revisa en la pasada transversal (iteración 14).

### Iteración 2: Inicio (22/11/2026)

- **Capturas:** `docs/diseno-loop/2/antes` (ADMIN, CHEF y WORKER «Hoy») y `despues` (ADMIN y CHEF). Se parametrizó el `Shell` del arnés por rol (`role`) y se añadió `HomeScreens.kt` (`inicio-admin` con indicadores y controles de hoy, `inicio-chef` sin bloque de capacitación). Se corre con `BPM_SCREENSHOTS_ONLY=inicio,hoy`.
- **Hallazgos y cambios:**
  - `ChecklistAssignmentScreen.kt`: en cada tarjeta de «Controles de hoy» el título, la sede, la frecuencia, la hora, el estado y el botón iban a la misma distancia, sin agrupar → los datos descriptivos van agrupados con 4 dp entre líneas (`bpm-spacing §1.3`). El cambio alcanza también a «Controles de tus sedes» y a la lista de programaciones, que usan la misma tarjeta: se revisarán en las iteraciones 8 y 10.
  - Inicio WORKER («Hoy»): cumple la lista (un título, tarjeta destacada única, filas planas, chips de estado, acción secundaria al pie); sin cambios.
  - Indicadores (`DashboardPanel`): filas con tarjetas del mismo alto, texto ≥ 14 sp; sin cambios. En 390 dp el séptimo indicador queda solo en su fila (siete tarjetas en dos columnas): se acepta.
- **Verificación:** `check-spacing --strict` 0 errores y 0 avisos; `jvmTest` y `assembleDebug` en verde; 6 capturas nuevas sin fallos.
- **Para decidir:** ver arriba (Inicio de ADMIN y CHEF).

### Iteración 3: Mi cuenta, dispositivos, cambios pendientes y avisos (22/11/2026)

- **Capturas:** `docs/diseno-loop/3/antes` y `despues`. Nuevo `AccountScreens.kt` en el arnés (`cuenta-admin`, `cuenta-worker`, `dispositivos`, `pendientes`, `avisos-lista`, `avisos-detalle`) y parámetro `secondaryTitle` en `Shell`. Las pantallas alojadas se capturan con `secondaryEditor = true`, igual que `App.kt` (sin él, el scroll del shell rompe la medición).
- **Hallazgo previo descartado:** creí que «Mis avisos» tenía el título duplicado; el shell no pone cabecera a las pantallas con `secondaryEditor`, así que el título interno era el único. La convención real es que cada pantalla alojada lleva su propio `BpmPageHeader` (ya la seguían Normativa y Automatizaciones). Se añadió a la lista de revisión.
- **Cambios:**
  - Mis dispositivos (`AccountsScreens.kt`): no tenía ningún título → `BpmPageHeader` con la explicación como descripción.
  - Mis avisos (`AlertsScreen.kt`): título interno `headlineSmall` → `BpmPageHeader`; las tarjetas del listado pasan de `BpmCard` manual (todo a 8 dp, estado como texto) a `BpmRecordCard` (título con chip de estado con tono, datos agrupados, una acción); el detalle lleva el estado como chip, los datos agrupados a 4 dp y el par «Marcar como visto / atendido» en `BpmInlineActions` (regla G7) en lugar de un `FlowRow` con anchos desiguales.
  - `BpmRecordCard`: parámetro opcional `extra` (aditivo, sin efecto en los usos actuales) para que el listado de avisos conserve `PendingBadge`.
  - Cambios pendientes (`OfflineUi.kt`): «Volver» sin relleno lateral; las tarjetas usan `BpmRecordCard` con el estado como chip (azul en cola, ámbar si necesita revisión, rojo si falló).
  - Mi cuenta (ADMIN y WORKER): cumple la lista; sin cambios.
- **Verificación:** `check-spacing --strict` 0 errores y 0 avisos; `jvmTest`, `assembleDebug` y `wasmJsBrowserDistribution` en verde; 12 capturas sin fallos.
- **Sin comprobar (solo código):** el panel de notificaciones push (`pushControls`) del listado de avisos, que necesita el ViewModel de dispositivos de notificaciones.

### Iteración 4: Empresa, Equipo, formularios de alta y Cuentas y equipo (22/11/2026)

- **Capturas:** `docs/diseno-loop/4/antes` y `despues` (Empresa, Equipo, formulario de sede, Aceptar invitación; los formularios de persona y organización solo «antes»). Nuevo `CompanyScreens.kt` en el arnés; `Shell` admite ahora `team` (workspace con sedes y personas), `form` y `formDraft`. Sin `formDraft` los formularios salen en blanco: el arnés los pinta con un borrador vacío.
- **Cambios:**
  - Empresa (`WorkspaceScreen.kt`): los datos de la organización y los de cada sede (dirección, distrito, teléfono) van agrupados a 4 dp, separados del nombre. `SectionHeading` pone la acción «Agregar sede» a la derecha del título, como en `BpmPageHeader`.
  - Equipo: «Gestionar roles, invitaciones y bajas» usa `bpmPrimaryButtonWidth()` igual que «Agregar persona»; en 390 dp ya miden lo mismo (antes uno a todo el ancho y otro a contenido).
  - Formulario «Nueva sede» (`WorkspaceForm.kt`): era el único con el título dentro de la tarjeta y un rótulo «CRECER EN EQUIPO»; ahora lleva `BpmPageHeader` fuera de la tarjeta, como los formularios por pasos de persona y organización.
  - Cuentas y equipo (`AccountsScreens.kt`): no tenía título de página → `BpmPageHeader("Cuentas y equipo")`. Aceptar invitación: título de página fuera de la tarjeta y se quita el «Aceptar una invitación» repetido dentro (revisión de la iteración 1, que se había capturado fuera del shell).
  - Formularios de persona y organización (por pasos): cumplen la lista; sin cambios.
- **Sin comprobar a la vista:** las tres pestañas de Cuentas y equipo (Equipo, Invitaciones, Organización y sedes) y el formulario de edición de sede: necesitan ViewModels con repositorio de cuentas (se leyó el código: ya usan `BpmRecordCard`, `BpmFormLayout`, `BpmDetailPane` y `BpmListToolbar`).
- **Verificación:** `check-spacing --strict` 0 errores y 0 avisos; `jvmTest` y `assembleDebug` en verde.
