# Estudio CAP (RF-23, RF-24, RF-25, RF-26, RF-50)

Contrato: `specs/RESEARCH.md` del backend (`Backend-research`). Requiere su migración V9.
Código en `features/research/{domain,data,presentation}`; proxy web en `web/research-proxy.mjs`.

## Entradas en la app
Ambas aparecen en Inicio (tarjetas bajo el título) y son rutas de Inicio:
- **Encuesta del estudio** (`#estudio`): cualquier miembro con organización activa (no SUPER_ADMIN sin membresía).
- **Investigación** (`#investigacion`): solo si `GET /research/access` devuelve `canResearch` o `canManageGrants`.
  El acceso se consulta al iniciar sesión (`ResearchViewModel.loadAccess`) y se repite tras limpiar el grafo.

## Participante (`SurveyViewModel`)
- Estado: participación (`GET /participation`), consentimiento (`POST /consent`, aceptar/revocar con confirmación),
  preguntas una por pantalla con progreso y validación de respuestas completas, y envío (`POST /submissions`).
- Likert/frecuencia: valor = posición + 1; opción múltiple: índice 0-based (`SurveyRules`).
- Idempotencia: la entrega (`id`, `clientOperationId`, `clientCreatedAt`) se construye una vez (`SubmitSurvey.prepare`) y se
  conserva en `pending`; el reintento tras un fallo de red reenvía exactamente lo mismo y bloquea la edición de respuestas.
  409 `RESEARCH_SUBMISSION_EXISTS` recarga y muestra el momento como completado; 403 consentimiento / 409 versión desfasada
  recargan la participación y descartan respuestas; otros rechazos liberan la entrega.
- Nunca se muestran puntajes ni respuestas correctas: el modelo no tiene dónde guardarlos y el proxy los elimina.
- Los momentos completados de la versión vigente se muestran como "Completada".

## Investigador (`ResearchViewModel` + `InstrumentsController` + `AnalysisController`)
Pestañas: Consentimiento (documentos, publicar versión), Cuestionarios (lista, importar JSON pegado, editor de borrador con
respuestas correctas, nueva versión, publicar con validación local 8+8+10 para CAP), Resultados (n, medias por dimensión,
inicial vs final con barras simples y valores numéricos, cambio pareado, CSV), Necesidades RF-50 (alta y listado paginado con
filtro por empresa) y Accesos (SUPER_ADMIN: listar, conceder por correo, retirar).
Los listados pequeños (concesiones, documentos, instrumentos, empresas) recorren hasta 10 páginas de 100.

## Descarga del CSV
`rememberCsvSaver` (expect/actual): web = descarga por `blob:`; Android = selector "Crear documento" (SAF); iOS = hoja de
compartir con archivo temporal que se borra al terminar; JVM = carpeta temporal (solo pruebas). El repositorio acota el CSV
a 16 MiB, exige `text/csv` y un nombre de archivo seguro; los bytes se borran de memoria tras entregarlos.

## Proxy web
`research-proxy.mjs` valida rutas, consultas (`pageSize`, `pageToken`, UUID), origen, JSON y cuerpos de entrada (rechaza campos
desconocidos), proyecta las respuestas con listas de campos permitidos y conserva solo códigos de dominio conocidos. El
importador acepta `source`/`generalData` pero no los reenvía. El CSV se reenvía en streaming (máx. 16 MiB, `text/csv`,
`Content-Disposition` validado).

## Decisiones frente al contrato
- `rowVersion` se acepta como entero o texto y se reenvía con su tipo original.
- La importación se hace pegando el JSON (sin selector de archivos); `source` y `generalData` se descartan en el cliente.
- Resultados no admiten filtro por empresa en el contrato: el filtro de empresa aplica a necesidades y a la exportación.
- Revocar y volver a aceptar depende de lo que permita el servidor para un participante `WITHDRAWN`.

## Pendiente
Verificar en dispositivo el guardado del CSV (Android/iOS; sin SDK ni macOS aquí) y la accesibilidad con lector de pantalla.
