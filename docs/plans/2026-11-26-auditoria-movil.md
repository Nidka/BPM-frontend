# Auditoría móvil (360–412 px) — 26/11/2026

Alcance: recorrido de Personal, Jefe de cocina y Administrador a 390×844 en la web de producción (capturas con
Playwright, cuentas demo) y lectura del código de las pantallas. **No había cuenta de Superadministrador de prueba**:
sus pantallas se revisaron solo por código (comparten componentes con Administrador); queda pendiente la pasada visual.
Origen: reporte de una persona usuaria con el APK de depuración (fotos de incidencias, textos y detalle poco claros).

Leyenda: **Hecho** = corregido en este cambio; **Pendiente** = hallazgo real que no se alcanzó a corregir.

## Hallazgos

| # | Pantalla / rol | Hallazgo | Estado |
|---|---|---|---|
| 1 | Incidencia, foto (Android) | Elegir una foto no la adjuntaba: cerrar el selector dispara «volver a primer plano», que consulta la lista (`loading`) y la foto elegida se descartaba sin error ni envío. Ver causa en el informe de la tarea. | **Hecho** |
| 2 | Incidencia, foto (Android) | HEIC/WebP y fotos de 50–200 MP o de más de 10 MiB se rechazaban; no había cámara real aunque el botón decía «Tomar una foto». | **Hecho** (conversión a JPEG, reducción, botón «Tomar foto») |
| 3 | Reporte enviado | «Agregar foto» desaparecía si se había marcado la foto: cerrar el panel sin adjuntar dejaba sin salida. | **Hecho** |
| 4 | Detalle de incidencia (Jefe/Admin) | Jerga («Criterio aplicado», «Versión 1 del catálogo», «Consultar historial»); no decía qué hacer; contador «0 antes · 0 después» sin contexto. | **Hecho**: tarjeta «Qué sigue» con pasos y acción principal; «Por qué es un problema»; «Ver qué pasó con este reporte»; secciones de fotos con conteo y botón |
| 5 | Lista de incidencias | Cuatro filas de fichas de estado empujaban las tarjetas bajo el pliegue; tarjetas de texto plano sin color ni icono. | **Hecho**: fichas en una línea con desplazamiento; tarjeta con icono, estado con icono, riesgo en color |
| 6 | Avisos (todos los roles) | Tarjetas de texto sin jerarquía; «Sin leer: 3» como texto; sin icono por tipo. | **Hecho**: icono por tipo, ficha de estado con icono, contador en ficha |
| 7 | Operación diaria (Jefe/Admin) | Hasta 8 fichas apiladas antes del contenido. | **Hecho** (una línea desplazable) |
| 8 | Empresa (Admin) | Datos de la organización ocupaban la primera pantalla; la «Gestión» quedaba bajo el pliegue. | **Hecho** (Gestión primero) |
| 9 | Estados vacíos (todos) | Tarjeta de texto seco. | **Hecho**: `EmptyState` lleva el tomate y texto centrado en toda la app |
| 10 | Esqueletos de carga | Filas genéricas que no se parecen a la pantalla final. | **Hecho** para incidencias, avisos y operación diaria (`BpmSkeletonRecordCards`, con destello); el resto ya usaba plantillas con forma |
| 11 | Hora (todas) | 24 horas con «PET»; formato distinto por plataforma. | **Hecho**: un solo formateador común, 12 horas «a. m./p. m.», sin zona |
| 12 | Aviso del sistema | Texto genérico «Tienes un aviso en BPM»; al tocar solo abría la app. | **Hecho** (cliente y backend): texto específico y navegación a la pantalla relacionada |
| 13 | Entrada de hora límite (formulario de incidencia) | Se escribe en 24 h («HH:mm»). Es un campo de entrada, no de lectura. | Pendiente (migrar a selector de hora 12 h) |
| 14 | Jefe: Equipo / Controles / Perfil | No se revisaron a fondo en esta pasada. | Pendiente |
| 15 | Superadministrador | Sin cuenta demo para recorrerlo. | Pendiente |
| 16 | Avisos: detalle | Sigue siendo una tarjeta larga de texto con dos botones de ancho distinto. | Pendiente |

## Criterios aplicados
- Un solo botón principal por pantalla, arriba, dicho en lenguaje de tarea («Registrar corrección»).
- Cada estado lleva color **y** icono **y** texto.
- Filtros en una sola línea; el contenido empieza sin desplazarse.
- Vacíos con el tomate y el siguiente paso; cargas con la forma real de la pantalla.
