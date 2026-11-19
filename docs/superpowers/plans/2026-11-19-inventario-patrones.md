# Inventario de rutas por patrón de pantalla

Estado al 19/11/2026. Patrones: Panel, Lista–detalle, Rejilla, Pasos, Formulario, Acceso (ver `BpmScreenPatterns.kt`).
«Hecho» = usa el patrón y la clase de ancho; «Parcial» = rejilla/ancho correctos pero el resto de la pantalla sigue con su estructura; «Pendiente» = aún no migrada.

| Pantalla | Ruta | Patrón | Roles | Estado |
|---|---|---|---|---|
| Inicio | `inicio` | Panel | todos | Hecho (indicadores + apoyo) |
| Mi cuenta | `cuenta` | Panel | todos | Hecho |
| Empresa | `empresa` | Rejilla | ADMIN, BPM | Hecho |
| Equipo | `equipo` | Lista–detalle | ADMIN, BPM | Hecho (filas densas; no tiene vista de detalle) |
| Aprender | `aprender` | Rejilla | ADMIN, CHEF, WORKER | Hecho |
| Seguimiento | `seguimiento` | Lista–detalle | ADMIN, BPM | Hecho (el detalle abre con la lista al lado) |
| Detalle de revisión | `seguimiento/{runId}`, `control/revisiones/{runId}` | Lista–detalle | ADMIN, CHEF | Hecho (lista a la izquierda desde 840 dp) |
| Control | `control` | Panel | ADMIN, CHEF | Hecho (controles de hoy + accesos) |
| Catálogo de plantillas | `control/plantillas` | Rejilla | ADMIN, CHEF | Hecho (rejilla) |
| Detalle de plantilla | `control/plantilla/{id}` | Lista–detalle | ADMIN, CHEF | Pendiente |
| Revisión de tu sede | `control/plantilla/{id}/iniciar`, `control/ejecucion/{runId}` | Pasos | ADMIN, CHEF | Hecho (720 dp; barra de acción fija al pie en compacta) |
| Mis revisiones | `control/revisiones` | Lista–detalle | ADMIN, CHEF | Hecho (rejilla) |
| Controles en este dispositivo | `control/guardados`, `control/guardados/{runId}` | Lista / Pasos | ADMIN, CHEF | Parcial (detalle en 720 dp) |
| Control programado | `control/programacion/{id}` | Lista–detalle | ADMIN, CHEF | Hecho (rejilla) |
| Operación diaria | `control/operacion`, `empresa/operacion` | Lista–detalle | ADMIN, CHEF | Parcial (rejillas) |
| Incidencias | `control/incidencias`, `empresa/incidencias`, `tareas/incidencias` | Lista–detalle | ADMIN, CHEF, WORKER | Hecho (detalle junto a la lista desde 840 dp) |
| Mis tareas | `tareas` | Pasos | WORKER | Pendiente |
| Plantillas de control (gestión) | `empresa/plantillas`, `…/detalle/{id}` | Lista–detalle | ADMIN | Hecho (rejilla) |
| Nueva plantilla | `empresa/plantillas/nueva` | Formulario | ADMIN | Pendiente |
| Versiones de plantilla | `…/detalle/{id}/versiones` | Lista | ADMIN | Hecho (rejilla) |
| Programar control | `…/detalle/{id}/asignar` | Formulario | ADMIN | Hecho (patrón) |
| Controles programados | `empresa/controles-programados[/{id}]` | Lista–detalle | ADMIN | Hecho (rejilla) |
| Cuentas y equipo | `empresa/cuentas` | Lista–detalle | ADMIN | Hecho (rejilla) |
| Academia (gestión) | `empresa/academia` | Rejilla | ADMIN | Hecho (rejilla) |
| Reglas de avisos | `empresa/avisos` | Formulario | ADMIN | Hecho (rejilla) |
| Curso / lección | `aprender/{courseId}` | Pasos | ADMIN, CHEF, WORKER | Hecho (patrón) |
| Logros | `aprender-logros` | Rejilla | todos con Aprender | Hecho |
| Rutas de aprendizaje | `aprender-rutas` | Rejilla | todos con Aprender | Hecho |
| Encuesta del estudio | `estudio` | Pasos | ADMIN, CHEF, WORKER | Hecho (patrón) |
| Investigación | `investigacion` | Panel | investigación, BPM | Parcial (rejillas) |
| Mis avisos | `avisos` | Lista | todos | Hecho (rejilla) |
| Reportes | `reportes` | Panel | ADMIN, CHEF | Hecho (indicadores y rejillas) |
| Elegir organización | `organizaciones` | Acceso | BPM, varias empresas | Hecho (patrón Acceso) |
| Nueva organización / sede / persona | `empresa/nueva-organizacion`, `empresa/nueva-sede`, `equipo/agregar-persona` | Formulario | ADMIN, BPM | Hecho |
| Mis dispositivos | `cuenta/dispositivos` | Lista | todos | Hecho (rejilla) |
| Aceptar invitación | `cuenta/invitacion` | Acceso | todos | Hecho (patrón) |
| Cambios pendientes | `cambios-pendientes` | Lista | todos | Hecho (cola de una columna de 720 dp) |
| Ingreso | `acceso` | Acceso | sin sesión | Hecho (divide en dos paneles desde 840 dp) |
| Conexión | `conexion` | Acceso | sin sesión | Hecho |
| Verificar constancia | `verificar/{codigo}` | Acceso | pública | Hecho |

## Pendientes en orden

1. ~~Acceso~~ hecho.
2. ~~Pasos~~ hecho en la ejecución de un control (`BpmActionBar`); queda «Mis tareas», que es una lista de operación y no un flujo por pasos.
3. **Formularios:** editor de nueva plantilla, formularios de operación diaria e incidencias (hoy dentro de pantallas de gestión).
4. ~~Lista–detalle~~ hecho en revisiones, seguimiento e incidencias.
5. ~~Panel de Control~~ hecho.
6. **Deuda medida:** 11 `BoxWithConstraints` en pantallas (R12) y 265 avisos congelados en `scripts/spacing-baseline.json` (R8 196, R7 34, R10 24).
7. **Verificación:** capturas con datos reales por rol (faltan cuentas ADMIN, CHEF, WORKER) y compilación Android.
