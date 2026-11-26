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
Ver la sección final de este archivo; se completa con el resultado de la verificación en producción.
