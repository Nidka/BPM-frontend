# Pruebas de usuario de punta a punta — 26/11/2026

Método: tres personas probadoras (Personal, Jefe de cocina, Administrador) recorrieron https://appbpm.com en un teléfono de 390×844
(es-PE, America/Lima) como si fuera su primer día y sin ayuda, con las cuentas demo, y anotaron en cada paso si una persona no técnica
lo lograría. La primera ronda se hizo sobre la versión `5de967c`; las correcciones de esta entrega se verificaron en la segunda ronda
(sección «Segunda ronda»). Capturas y guiones en la carpeta de trabajo de la sesión (`scratchpad/e2e/<rol>/`).

Leyenda: PASS = se logra sin ayuda · FRICCIÓN = se logra pero con dudas o toques de más · FAIL = no se puede / sin salida.

## Personal
| # | Flujo | Resultado | Hallazgo principal | Estado |
|---|---|---|---|---|
| 1 | Entrar, introducción, Hoy | PASS + fricción | La introducción reaparece en un navegador nuevo; «Meta de hoy 1 de 6» no dice que mezcla lección y tareas | Pendiente (guardar «ya vi la introducción» en la cuenta) |
| 2 | Hacer una tarea (con foto) | PASS + fricción | «Tomar foto» abría un panel con aviso de conexión y pedía dos toques | Corregido (panel sin aviso falso, título «Fotos», botón «Listo»); el doble toque sigue |
| 3 | Lección, práctica, evaluación | PASS + 1 FAIL | Tras «Siguiente módulo», «atrás» dejaba una pantalla en blanco | Corregido (el módulo nuevo reemplaza al anterior en el historial) |
| 4 | Reportar con foto | PASS · «Ver mis reportes» FAIL | El botón no hacía nada (el formulario se reabría solo) | Corregido (cierra el borrador y muestra la lista; también está en «Reporte enviado») |
| 5 | Avisos | PASS + fricción | Abrir un aviso no lo marcaba como visto | Corregido (se marca al abrir); títulos genéricos vienen del servidor |
| 6 | Ajustes y ayuda | PASS | — | — |

## Jefe de cocina
| # | Flujo | Resultado | Hallazgo principal | Estado |
|---|---|---|---|---|
| 1 | Entrar y Turno | PASS + fricción | «Primeros pasos» no se marca solo | Pendiente |
| 2 | Control paso a paso con «No cumple» | PASS + fricción | Resultado verde y festivo con 71 %; no decía qué quedó mal | Corregido (color y mensaje según el resultado) |
| 3 | Corregir una incidencia con foto después | PASS + fricción | «Empezar a corregir» pedía tres toques y dos pantallas vacías; textos «(America/Lima)», «Criterio», «Copia local guardada», «Descripción:» | Corregido (un solo «Sí, empezar ahora»; textos en lenguaje llano; responsable rotulado con «Cambiar») |
| 4 | Equipo y detalle de persona | FRICCIÓN | Dato suelto «Sedes en común» sin acciones | Corregido (tarjeta con iconos y rol en ficha); acciones por persona: pendiente |
| 5 | Aprender | PASS + fricción | Fichas de la cabecera sin rótulo | Pendiente |
| 6 | Avisos, perfil, ajustes, ayuda | PASS + fricción | Dos botones parecidos («visto»/«atendido») | Corregido (un botón «Ya lo atendí»; «visto» automático) |

## Administrador
| # | Flujo | Resultado | Hallazgo principal | Estado |
|---|---|---|---|---|
| 1 | Entrar e Inicio | PASS | — | — |
| 2 | Programar un control | FRICCIÓN | Hay que entrar a la plantilla; el error de duplicado sale lejos del botón | Pendiente |
| 3 | Invitar a una persona | FAIL parcial | «Invitar persona» no hacía nada visible; el enlace del correo cargaba sin logo ni fuentes (10 errores 404) | Corregido (abre ya el formulario; el servidor sirve los recursos bajo `/cuenta/`) |
| 4 | Asignar un curso | PASS con fallos | La fecha límite se veía un día después y en formato ISO; cancelar sin confirmar; «CANCEL AR» | Corregido (fecha local dd/mm/aaaa, confirmación, botones sin partirse) |
| 5 | Incidencias (reportar, asignar, validar) | PASS con fricción | Textos técnicos, selector de hora de 24 h | Corregido (selector en «a. m./p. m.», textos llanos) |
| 6 | Reportes y exportar | PASS con fricción | Sin PDF; columnas del CSV en inglés y hora UTC | Pendiente (backend) |
| 7 | Sedes | PASS con fricción | «Editar sede» salta a otra pantalla | Parcial (detalle con iconos); unificar: pendiente |
| 8 | Avisos y ajustes | PASS con fricción | Avisos idénticos sin dato concreto | Parcial (diseño nuevo); textos del servidor: pendiente |
| 9 | Recuperar contraseña | PASS | El error «Revisa el correo.» queda tras corregir | Pendiente (menor) |

## Segunda ronda (tras el despliegue)
Verificado en producción tras el despliegue de `4474afb` (390×844):

| Corrección | Resultado |
|---|---|
| «Ver mis reportes» abre la lista de reportes propios | PASS |
| «Invitar persona» abre Invitaciones con el formulario ya abierto | PASS |
| Botón «Cancelar» de las confirmaciones en una sola línea | PASS |
| Recursos del enlace de invitación (`/cuenta/composeResources/…`) | PASS (200, antes 404) |
| Aviso: se marca visto al abrirlo, tarjeta con acciones «Ya lo atendí» e «Ir a verlo» | PASS |
| Incidencia: «Qué sigue», secciones de fotos y plazo; detalle con iconos | PASS |
| Fecha de asignación de cursos en día local, «Siguiente módulo» sin pantalla en blanco, resultado del control por puntaje, «Empezar a corregir» en un paso | Cubierto por pruebas unitarias; no repetido de punta a punta en producción (requiere crear datos nuevos) |

No quedaron repetidos de punta a punta los flujos de lección completa, evaluación y control completo; las capturas de la primera ronda siguen
siendo la referencia.


## Segunda ronda completa (los tres roles, 390×844, tras `415bb8c`)

Cada persona probadora repitió todos sus flujos desde cero. Resultado y lo que quedó:

| Rol | Flujo | Veredicto | Estado final |
|---|---|---|---|
| Personal | Ver mis reportes | PASS | — |
| Personal | Tarea con observación y foto (nota obligatoria, panel «Fotos») | PASS | — |
| Personal | Aviso: se marca visto, «Ya lo atendí» / «Ir a verlo» | PASS | — |
| Personal | Horas en 12 h | PASS | — |
| Personal | Primeros pasos / introducción en navegador nuevo | FAIL (ronda 1 de la 2.ª) | Corregido: la introducción espera hasta 9 s a saber si ya hay actividad; el paso «reportar» se marca al visitarlo (verificado a mano en producción) |
| Personal | Evaluación: última alternativa tapada por el pie | FRICCIÓN | Corregido: ficha tocable «Hay más abajo» |
| Personal | Jerga XP / códigos | FRICCIÓN | Corregido: «puntos», «Se aprueba con N %» |
| Personal | Tarea hecha se reabría; botón Reportar tapaba la última fila; «Aceptar una invitación» | FRICCIÓN | Corregido |
| Jefe | Control con «No cumple» (nota opcional, resultado por puntaje) | PASS | — |
| Jefe | Incidencia: iniciar en un paso, textos llanos, hora 12 h, historial, Sede como ficha | PASS | Revisión mostraba «12:00»: corregido a 12 h |
| Jefe | Avisos (bandeja) | FAIL: 503 por el tipo «incidencia asignada» | Corregido en proxy y cliente; verificado en producción (aviso «Te asignaron una incidencia…» visible, detalle con acciones) |
| Jefe | Primeros pasos se marcan solos | PASS | — |
| Admin | Programar control a un toque desde la plantilla | PASS | «Confirmar» sin partir palabra; el motivo del rechazo se repite junto al botón |
| Admin | Invitar persona; enlace de invitación con logo y fuentes; «Cancelar» en una línea | PASS | Roles con descripción distinta y nombres de la app |
| Admin | Asignar curso (fecha local, confirmar cancelar) | PASS | — |
| Admin | Incidencias (asignar, validar, hora 12 h) | PASS | Texto de autovalidación solo para quien corrigió |
| Admin | Reportes: PDF y CSV (verificados descargando los archivos) | PASS | CSV sin UUID (nombres) y PDF con nombres largos en varias líneas |
| Admin | Sedes, avisos, ajustes | PASS | Avisos antiguos: texto específico al leer (backend) |
| Admin | Recuperar contraseña | PASS parcial | «Revisa el correo.» sigue tras corregir: pendiente |

Pendiente real (no corregido): enlace de invitación sin «Te invitaron a X como Y» ni correo precargado; «Editar sede» salta a otra vista;
zona horaria como texto libre; filas fijas de la Academia (~130 px); «Ir a verlo» de una capacitación abre Aprender y no el curso; un solo
envío de foto de la tarea aún pide dos toques; al recargar la página se cierra la sesión (decisión de seguridad: no se guarda el token);
el inicio de sesión de las pruebas a veces no completaba (se reprodujo solo en el guion de pruebas: los campos se escribían antes de
terminar de cargar; con la cuenta correcta el acceso por API y el real funcionan).

## Tercera ronda (producción, 390×844, tras `e6368e5`)

| Corrección | Resultado |
|---|---|
| Enlace de invitación: «Te invitaron a <empresa>», rol y correo precargado; sin errores 404 | PASS |
| «Editar sede» abre directamente el formulario; zona horaria como selector (Lima por defecto) | PASS |
| Tarea con foto: «Guardar y agregar foto» abre el selector con un solo toque | PASS |
| Aviso de capacitación: texto específico y «Ir a verlo» abre ese curso (`aprender/<id>`) | PASS |
| Introducción no reaparece para quien ya tiene actividad | PASS |
| Pestañas de la Academia en una línea (cursos, asignaciones, rutas, logros) | Hecho; no retomado visualmente en esta ronda |
| Recuperar contraseña: «Revisa el correo.» se borra al editar el correo | PASS |
| Evaluación: ficha «Hay más abajo» (visible con alto de pantalla reducido, 390×640) | PASS |
| CSV con nombres (encabezado `Fecha local,Enviado el,Sede,Control,Área,Versión de la plantilla,Cumplimiento (%)`) y PDF A4 | PASS (por API con el token del administrador; el PDF de 1 página no ejercita el salto de línea de nombres largos) |
| Avisos antiguos con texto específico (0 genéricos en la bandeja del jefe) | PASS |
| «Siguiente módulo» y volver | No retestado: exige completar una evaluación de módulo real |

**Superadministrador:** sin cuenta de prueba; revisión con el arnés de capturas de Compose (`./gradlew :composeApp:jvmTest --tests '*WorkerScreenshots*'`,
`composeApp/build/screenshots/super-*-390-full.png`). Se retocó «Organización abierta» y «Datos de la organización» (filas con icono, botón en una línea).

**Sesión web al recargar:** se deja como está. `specs/IDENTITY.md` (líneas 17 y 445) establece que la web no persiste tokens («Web no persiste tokens y
exige entrar tras recargar»); guardar el refresh token en `sessionStorage` lo dejaría legible por cualquier script de la página, contradice esa
decisión de seguridad y no se hace sin que se cambie la especificación.

**Datos E2E creados en esta ronda:** una invitación a `e2e-r3-…@yopmail.com` (ya revocada) y un registro de tarea de personal2 («E2E r3 foto en un toque»
con foto `t.png`); el aviso de capacitación de personal2 quedó marcado como visto.
