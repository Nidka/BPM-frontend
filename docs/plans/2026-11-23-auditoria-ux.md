# Auditoría de experiencia de uso (2026-11-23)

Recorrido de todos los flujos por rol (Personal, Jefe de cocina, Administrador y Superadministrador, este último con el
arnés de capturas) con tres preguntas: ¿lo entiende una persona de cocina sin formación técnica?, ¿la jerarquía y la
distribución de la información son correctas? y ¿el texto es demasiado plano (falta negrita, fichas de estado, conteos,
horas, sedes)? Cada hallazgo indica el problema, la pantalla, la corrección y su estado.

Estados: **Hecho** (en este cambio), **Parcial** (hecho en lo principal, queda un resto descrito) y **Pendiente** (no
se hizo; con motivo). Las capturas se generan con `./gradlew :composeApp:jvmTest --tests '*WorkerScreenshots*'` (390, 768,
1280 y 1920 dp) en `composeApp/build/screenshots/`.

## 1. Principio: nada bloquea por algo que la administración no configuró

Regla aplicada en todo el recorrido: si falta una configuración, la persona igual puede seguir (valores integrados, texto
libre, selección automática cuando hay una sola opción) y, si no puede, el vacío dice qué hacer y, para quien administra,
ofrece el botón que lo arregla.

| # | Problema | Pantalla | Corrección | Estado |
|---|---|---|---|---|
| B1 | «Tu organización todavía no configuró categorías» impedía reportar | Reportar (Personal) | Categorías integradas (Temperatura, Limpieza, Plagas, Equipo dañado, Producto en mal estado, Higiene del personal, Otro problema) siempre visibles, junto a las de la organización sin repetirse; «No sé qué tipo es» lleva a «Otro problema» | Hecho |
| B2 | El servidor exigía un `catalogId` existente | API de incidencias | `POST /incidents` acepta `catalogCode` (categoría integrada); el servidor crea la fila del catálogo de la organización la primera vez. Sin migración (ver sección 5) | Hecho (backend `c4a3e32`) |
| B3 | Reportar eran 3 pasos (tipo, foto, dónde y nota) con textos obligatorios | Reportar | 2 pasos: tipo → «Cuéntanos qué pasó» (texto libre, lugar opcional, casilla de foto). Con elegir el tipo ya se puede enviar; la descripción toma el nombre de la categoría | Hecho |
| B4 | Confirmación sin «qué pasa ahora» | Reporte enviado | Tarjeta «¿Qué pasa ahora?» (aviso al supervisor, asignación, revisión, dónde seguirlo) | Hecho |
| B5 | Jefe de cocina y Administrador no tenían entrada rápida para reportar | Turno (Jefe), Inicio (Admin) | Botón «Reportar un problema» en Turno y en «Acciones rápidas» del Inicio; ruta `reportar` en modo enfoque para cualquier rol que pueda reportar; sede automática si hay una sola | Hecho |
| B6 | Formulario clásico de incidencia pedía «Elegir criterio del catálogo» (vacío sin catálogo) | Incidencias (formulario) | Fichas con las mismas categorías integradas + las de la organización | Hecho |
| B7 | Inicio del administrador sin sedes: texto sin acción | Inicio (Admin) | Botón «Agregar sede» a un toque | Hecho |
| B8 | Personal sin sede asignada: solo texto («Pide a tu administrador…») | Hoy (Personal) | Es una dependencia real (no hay datos que mostrar); el texto dice qué pedir. Falta ofrecer «Reportar» y «Aprender» aunque no haya sede | Pendiente |
| B9 | Controles sin programar (Jefe) y plantillas vacías (Personal/Jefe) dicen «tu administrador…» sin acción | Controles | Para el administrador ya existe «Programar control» en su pantalla; el vacío de Personal/Jefe no puede resolverse por ellos | Parcial |
| B10 | Vacíos de administración (rutas, cursos, criterios) con texto pero sin botón en el propio vacío | Academia, Incidencias | El botón de crear está en la cabecera de cada pantalla; no se duplicó en el vacío | Pendiente |

## 2. Hallazgos de la pantalla Hoy (Personal, escritorio 1280–2000 dp)

| # | Problema | Corrección | Estado |
|---|---|---|---|
| H1 | La tarjeta «Buscar en la normativa» era más ancha que la rejilla de dos columnas y quedaba desalineada | Pasa a la columna principal de Hoy (Personal y Jefe) como `NormativeTile`, con el mismo ancho que las demás tarjetas; el acceso genérico de Inicio ya no se dibuja para quien tiene Hoy propio | Hecho |
| H2 | «Sigue aprendiendo» mostraba la barra llena con el módulo sin terminar | El servidor informa 100 % cuando están todas las lecciones vistas aunque falte la evaluación. `assignmentProgress` limita la barra a 95 % hasta que el curso está completado y la tarjeta dice «40 % del curso» o «Viste todas las lecciones. Falta la evaluación final.» | Hecho |
| H3 | «Meta de hoy» valía 0 de 2 en Hoy y 0/1 en Aprender | Una sola definición: Hoy = 1 lección + tareas del turno, con desglose en fichas («Lección hecha», «Tareas 2/7»); Aprender cuenta solo la lección y se llama «Lección de hoy» (sección 4) | Hecho |
| H4 | La píldora «0/2» arriba repetía la tarjeta Meta de hoy | Se quitó de Hoy; arriba quedan solo racha y XP | Hecho |
| H5 | Encabezado «Tu turno · 1 tarea» redundante | «Tu turno» + ficha del turno abierto («TURNO TARDE») y, sin panel lateral, ficha de avance («2 DE 7») | Hecho |
| H6 | La tarjeta derecha «Tu turno» repetía la lista de la izquierda | Ahora «Avance del turno»: barra, «5 tareas por hacer» y ficha roja «1 VENCIDA»; ya no repite la siguiente tarea | Hecho |
| H7 | Inicio de sesión: Enter debe enviar el formulario en web; el mensaje de error desplaza el botón en móvil | Enter ya enviaba en los campos de acceso (verificado en `IdentityScreen`); el desplazamiento por el mensaje se mantiene (aceptado) | Hecho / aceptado |

## 3. Carga: esqueletos en lugar de barras y «Cargando…»

Componentes nuevos en `core/designsystem/BpmSkeleton.kt`: `BpmSkeleton` (bloque con destello, quieto con «reducir
movimiento»), `BpmSkeletonLine`, `BpmSkeletonCircle`, `BpmSkeletonRow`, `BpmSkeletonList`, `BpmSkeletonCard`,
`BpmSkeletonKpis`, `BpmSkeletonVideo`, `BpmSkeletonDetail` y `BpmSkeletonDashboard`. Cada plantilla anuncia «Cargando…» una
sola vez a los lectores de pantalla (región con `liveRegion`).

Aplicado en: Hoy (tareas), Aprender (camino y curso), Turno del jefe (indicadores y controles), Controles del jefe,
Inicio del administrador y Superadministrador (indicadores y sedes), listados paginados (`BpmPagedList`, selector de
opciones), plantillas y detalle de controles (catálogo, asignación, versiones, historial, ejecución), operación diaria,
incidencias (lista, historial y detalle), alertas y reglas, Academia (cursos, rutas, logros, asignaciones, administración),
reportes y reportes de administración, investigación y encuesta, normativa, constancias, organización, formularios
de empresa, selector de organización, reproductor de video de la lección (`BpmSkeletonVideo`), estado de conexión y
restauración de sesión.

Se conservaron las barras que representan avance real o una acción en curso: avance de curso y metas, subida y descarga de
archivos, envío de evaluación, comprobación de respuesta, guardado/publicación, sincronización y el botón de acceso.
Estado: **Hecho**. Resto: las barras de acción en curso podrían pasar a un indicador dentro del botón (**Pendiente**, no
es carga de contenido).

## 4. Aprender y evaluación (Personal)

| # | Problema | Corrección | Estado |
|---|---|---|---|
| A1 | «Meta de hoy 0/1» en Aprender contradecía Hoy | Se renombra «Lección de hoy» (píldora «0/1 lección» y tarjeta lateral); la meta de Hoy es lección + tareas | Hecho |
| A2 | Lecciones con video: la transcripción estaba oculta y sin lectura en voz alta | La transcripción (derivada de los subtítulos WebVTT que ya guarda el servidor, sin campo nuevo) se abre con un botón y tiene «Escuchar la transcripción» | Hecho |
| A3 | Subtítulos y velocidad | Subtítulos activos por defecto (pista nativa Android/web, superpuestos en iOS). Velocidad y botón de subtítulos: los del reproductor nativo (web `<video controls>`, ExoPlayer, AVPlayer) | Parcial: no verificado en dispositivo |

## 5. Backend: reportar sin catálogo

- `POST /api/v1/organizations/{id}/incidents` acepta `catalogId` **o** `catalogCode` (exactamente uno). Códigos:
  `TEMPERATURA`, `LIMPIEZA`, `PLAGAS`, `EQUIPO_DANADO`, `PRODUCTO_MAL_ESTADO`, `HIGIENE_PERSONAL`, `OTRO`.
- La primera vez que una organización usa un código, el servidor inserta su fila en `incident_catalog` (versión 1, sin
  fotos obligatorias) con `INSERT … ON CONFLICT DO NOTHING`; si ya tenía ese código usa el suyo. Idempotente y seguro con
  reportes simultáneos (probado contra PostgreSQL).
- **No hay migración nueva**: la tabla y sus restricciones no cambian, así que no hay nada que aplicar a mano en la base.
  Sí hay que **desplegar el backend** para que el cliente nuevo pueda enviar `catalogCode`.
- El proxy web (`web/incidents-proxy.mjs`) valida el cuerpo del reporte: ahora acepta `catalogCode` (solo los siete códigos integrados) o `catalogId`, exactamente uno, con prueba.
- Degradación del cliente hasta que el backend esté desplegado: un servidor anterior rechaza el campo desconocido con
  «solicitud inválida»; el cliente muestra el error saneado y, si hay categorías de la organización, sugiere elegir una de
  la lista. Las categorías de la organización (con `catalogId`) siguen funcionando igual.

## 6. Accesibilidad

Sección **Accesibilidad** en Perfil de todos los roles (Personal, Jefe, Administrador y Superadministrador), con lenguaje
simple y guardado en el dispositivo (`FeedbackPreferences`):

| # | Necesidad | Qué se hizo | Estado |
|---|---|---|---|
| X1 | Baja visión: texto más grande | «Tamaño del texto»: Normal / Grande (×1,15) / Muy grande (×1,3), se suma al tamaño del sistema y se aplica a toda la app al instante | Hecho |
| X2 | Baja visión: contraste | «Más contraste»: texto de apoyo, bordes y deshabilitados más oscuros (colores de juego y esquema Material) | Hecho (cubre texto de apoyo y bordes; los colores de estado conservan su tono) |
| X3 | Escuchar (voz en voz alta) | `BpmListenButton` + `TextSpeaker` por plataforma: Android `TextToSpeech`, iOS `AVSpeechSynthesizer`, web `speechSynthesis`; se oculta sin soporte o si se apaga «Escuchar». En ideas de «Lo esencial», lectura de la lección, preguntas (con sus opciones) y transcripción | Hecho (no probado en dispositivos físicos) |
| X4 | Sordera / poca audición | Todo sonido ya tenía equivalente visual (franja de respuesta con título y explicación, celebración con texto); el aviso de Sonido lo dice. Vibración opcional | Hecho |
| X5 | Movimiento reducido | Confeti, celebración, camino, franja de respuesta, aparición y ahora el destello de los esqueletos lo respetan (`rememberReduceMotion`) | Hecho |
| X6 | Lectores de pantalla y 48 dp | Regiones de carga con descripción; botón «Escuchar» ≥ 48 dp; interruptores de 56 dp. Revisión completa de descripciones de todos los iconos y de objetivos táctiles de pantallas heredadas | Parcial |
| X7 | No depender solo del color | Estados con ficha de texto («1 VENCIDA», «LECCIÓN HECHA», «TAREAS 2/7»); respuestas con rótulo («¡Muy bien!», «Casi»). Quedan filas de administración que distinguen por color y borde | Parcial |
| X8 | Texto «Muy grande» en cajas de alto fijo | Puede recortar texto en algunas fichas pequeñas a 360 dp; verificar con el escalado máximo | Pendiente |

## 7. Texto plano → énfasis y fichas

| # | Dónde | Cambio | Estado |
|---|---|---|---|
| T1 | Hoy: meta, turno, vencidas | Fichas con conteo y estado (3 DE 8, TURNO TARDE, 1 VENCIDA) | Hecho |
| T2 | Sigue aprendiendo | Porcentaje y mensaje de evaluación pendiente | Hecho |
| T3 | Reportar | Ficha con el tipo elegido en «Cuéntanos qué pasó» | Hecho |
| T4 | Pantallas de administración heredadas (Material) | Muchas listas siguen en texto plano con `BpmStatusChip` solo en algunas filas | Pendiente |

## 8. Otros hallazgos sin resolver

- Verificar visualmente Turno del jefe y Controles con el escalado «Muy grande».
- `BpmSkeleton*` usan un destello continuo; en pantallas con muchas regiones simultáneas podría limitarse a una sola
  animación compartida (rendimiento en Android antiguos).
- Los avisos de «sin conexión» y «Requiere conexión» de video no tienen aún acción alternativa a un toque.
