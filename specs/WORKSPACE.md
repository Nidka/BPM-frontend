# Espacio de trabajo y diseño BPM

## Base visual

Diseño lúdico profesional inspirado en la claridad de Duolingo, con identidad
propia: petróleo/coral, fondos claros, tarjetas redondeadas, botones con relieve
y mascota BPM. No replica su marca, búho, textos ni recursos. Las dos ilustraciones
proceden de los assets originales del proyecto y se empaquetan como recursos
Compose; no requieren un CDN ni credenciales de almacenamiento.

`core/designsystem` contiene tema, Brand, Mascot, BpmCard, RaisedButton y estados
vacíos compartidos. Login usa composición dividida a partir de 900 dp. Workspace
usa menú lateral desde 950 dp y navegación inferior en tamaños menores. Los
formularios permiten desplazamiento, muestran errores por campo y bloquean
envíos mientras se guardan. No hay XP, rachas ni progreso inventados.

El README del prototipo declara «BPM Aula» como nombre provisional. La app muestra
temporalmente «BPM», sin eslogan, hasta que el equipo defina el nombre definitivo.
`BpmBrand` centraliza el nombre de UI; Android, iOS y el título web mantienen la
misma denominación. El brote de la biblioteca sustituye la letra `b.` inventada.
Es el símbolo del prototipo, no una marca definitiva aprobada.

`WorkspaceTopBar` usa `TopAppBar` de Material 3: marca y organización en una segunda
línea acotada. No muestra un botón permanente de recarga en ningún tamaño. El selector
solo está habilitado si hay varias organizaciones o permiso para crearlas. Las
secciones principales no acumulan botones de historial; el detalle conserva su
regreso al catálogo. «Cerrar sesión» pertenece a Mi cuenta. La navegación lateral
o inferior mantiene los destinos autorizados; no se añaden funciones simuladas.

Las vistas de consulta se revalidan al entrar o volver al primer plano si llevan
dos minutos sin una lectura confirmada. `DataFreshness` usa tiempo monotónico;
los ViewModels evitan cargas simultáneas y no recargan formularios ni ejecuciones.
No hay sondeo ni solicitudes periódicas que prolonguen la sesión. Los errores
ofrecen «Reintentar» en el contenido. Guardar o cambiar empresa mantiene su recarga
explícita. Esto es revalidación de datos, no recargar la página del navegador.

`RaisedButton`, `BpmSecondaryButton` y `BpmTextButton` centralizan acciones
principales, secundarias y de texto, con área táctil mínima, foco y feedback.
Los controles de selección y el botón Google conservan su semántica y requisitos
de marca. Inicio muestra la mascota también en móvil; los indicadores fluyen a
varias filas según ancho y escala de texto, sin fijar tres columnas estrechas.

Los avisos se insertan al inicio del contenido, debajo de la cabecera. Al aparecer
una advertencia de sesión o de acceso, el contenido se desplaza hasta el aviso.
La cabecera permanece en su posición. En formularios, el mismo aviso se inserta
después del título y vuelve al inicio del desplazamiento cuando aparece.

Las altas tienen rutas propias: `empresa/nueva-organizacion`, `empresa/nueva-sede`
y `equipo/agregar-persona`. App las registra en Navigation Compose y comprueba
tanto el destino como la capacidad de crear antes de componer el formulario,
incluidas URL directas. La organización exige SUPER_ADMIN; sede/equipo requieren
gestión de la empresa activa. El backend mantiene la autorización definitiva.
Mientras se carga el contexto se espera su resultado, sin anunciar un rechazo
prematuro. El éxito confirmado vuelve a la pantalla previa y recarga los datos;
cancelar o usar el historial no envía el formulario. Las claves de reintento
permanecen estables mientras la misma pantalla sigue abierta.

El formulario usa el árbol normal de UI, con contenido desplazable e íconos
etiquetados para regresar. En ventanas bajas, las acciones se desplazan con el
contenido; con altura suficiente permanecen al pie sin ocultar los campos. Se retiró Dialog después de reproducir
en la versión fijada de Compose web que, al cerrarlo, el árbol accesible no
recuperaba la navegación principal. La misma pantalla se comparte con móvil,
donde el NavHost conserva el regreso del sistema, sin añadir handlers propios
ni adaptadores visuales por plataforma.

## Recorrido implementado

1. Registro/verificación o Google web → perfil BPM confirmado por backend.
2. Una única AppGraph comparte el repositorio de sesión con workspace mediante
   un proveedor de token en memoria. Las pantallas no reciben el token.
3. WorkspaceViewModel carga organizaciones permitidas y selecciona la primera
   o conserva la actual si todavía está autorizada.
4. Cambiar empresa limpia el workspace anterior y cancela consultas anteriores.
   Salir cancela solicitudes y borra todo el estado de organización.
5. SUPER_ADMIN crea organizaciones. ADMIN/SUPER_ADMIN agrega sedes y cuentas
   BPM existentes como ADMIN/CHEF/WORKER, con sedes asignadas donde corresponda.
6. Éxito confirmado por API cierra el formulario y recarga datos. Los errores
   conservan el formulario. Organización/sede mantienen UUID durante reintentos.

Una cuenta USER nueva sin membresías muestra instrucciones para pedir acceso;
no puede crearse una empresa ni elevar sus permisos. No se asignan cuentas
administrativas reales como parte del despliegue.

## Módulos y límites

| Sección | Disponible | Siguiente implementación |
|---|---|---|
| Inicio | Bienvenida y conteos de listas autorizadas | Indicadores agregados y actividad |
| Empresa | Datos, sedes y altas autorizadas | Edición/desactivación |
| Equipo | Miembros y alta por correo de cuenta existente | Invitaciones, cambios de rol y bajas |
| Aprender | Catálogo publicado y estado de progreso existente | Lecciones, evaluaciones, XP y constancias |
| Control | Catálogo, preguntas, inicio, guardado y finalización en línea; incidencias visibles | Persistencia/sincronización offline e incidencias |

Cada página carga 25 registros, con un máximo de 100 por solicitud. Inicio usa
`totals` autorizados del backend para sedes, cursos y controles publicados. No se presentan botones que simulen completar cursos
o registrar controles. R2, operación diaria, investigación y alertas quedan en
sus specs, no están terminados por tener navegación.

Este recorrido necesita internet. No persiste cambios pendientes ni ofrece
éxito offline. La cola/versionado/sincronización sigue el futuro contrato OFFLINE.
El alta de miembro no envía correos: la persona debe registrarse primero.

## Contratos, seguridad y pruebas

`features/workspace/{domain,data,presentation}` sigue MVVM y Clean Architecture.
La raíz app adapta identity a WorkspaceViewer; la presentación no importa identity.
WorkspaceScreen compone el contenedor, WorkspaceContent las secciones y
WorkspaceForm los formularios. App contiene el NavHost y comprueba cada destino
según sesión y rol, incluso al abrir un fragmento web directamente. Mi cuenta
muestra datos públicos y permite revalidar sesión. Cambiar empresa o cerrar sesión
limpia la pila; formularios y menús se reinician al cambiar de contexto.
AppGraph inyecta repositorio/casos de uso/VM. El backend valida usuario, tenant,
rol y sedes en cada llamada; ocultar botones solo mejora la experiencia.

El proxy `web/workspace-proxy.mjs` acepta únicamente las rutas explícitas del
workspace y sus listas, Bearer, JSON limitado y origen válido para POST.
No reenvía cookies ni cabeceras de tenant. Filtra los campos públicos y sanitiza
errores/fields. El DTO de /me conserva platformRole para mostrar las acciones
permitidas, pero nunca lo envía como autoridad al backend.

Tests: sesión requerida, DTO sin permisos, errores sanitizados, cancelación al
cambiar tenant/logout, proxy cerrado. Prueba manual administrativa: cuenta
autorizada → crear empresa → sede → agregar otra cuenta existente → cerrar
sesión → entrar con la segunda cuenta y comprobar solo sus permisos.
La contraseña y el consentimiento Google los introduce el titular.

La política de destinos y el historial se describen en el README. WORKER accede a
Inicio/Aprender/Mi cuenta; CHEF añade Control/Equipo; ADMIN incluye Empresa y su
aprendizaje; SUPER_ADMIN conserva gestión sin mostrarse como alumno u operador.
Esto no amplía los endpoints disponibles ni reemplaza autorización del backend.

El catálogo de Control pertenece a features/checklists y se compone desde App
mediante un slot, sin dependencias entre funcionalidades. Su detalle con ruta propia,
contrato de lectura y pruebas se describen en CHECKLISTS.md.

El selector de organización tiene la ruta `organizaciones`, con búsqueda en servidor, páginas y filas de selección en LazyColumn, organización actual
señalada y regreso mediante la navegación existente. No agrega una columna de
botones al contenido empresarial. La acción para crear una organización está
separada y solo aparece con permiso de plataforma. En pantallas grandes el
selector limita su ancho; en ventanas bajas reduce contenido secundario para
conservar espacio de búsqueda y selección. Los avisos de sesión permanecen en
la lista desplazable y vuelven al inicio cuando aparecen.
La altura se ajusta al contenido hasta su máximo, para no reservar un panel
vacío cuando hay pocas organizaciones o coincidencias.
No usa una capa Popup: en la versión fijada de Compose web, el menú anterior
dejaba el árbol de accesibilidad asociado a la capa retirada al cerrarse. La
selección conserva la cancelación y limpieza de contexto existentes, sin
ampliar permisos. Los endpoints paginados se describen abajo. El encabezado es un acceso a esa pantalla, no un combobox.
El regreso explícito usa el historial del navegador en web y el NavController en
móvil. La integración experimental fijada agrega una entrada web cuando se hace
pop solo en NavController; delegar el regreso al navegador evita que «Atrás»
reabra inmediatamente el formulario que se acaba de cancelar. La adaptación
pertenece a la raíz app/wasmJsMain y no introduce APIs de navegador en features.

## Respuesta a la interacción y páginas de servidor

`BpmFeedback` configura la indicación Material por control, con una capa suave
del color apropiado para su fondo. Se aplica al selector, filas de organización,+navegación y RaisedButton. No se impone una configuración global de ripple ni
se eliminan las señales de interacción. La cabecera acota la superficie pulsable
y respeta sus esquinas. El botón con relieve se desplaza 2 dp al pulsarlo mediante
una animación breve, sin modificar su espacio de layout. `animateDpAsState`
respeta MotionDurationScale del contexto Compose; con escala cero no se prolonga.
El selector y los botones con relieve mantienen un contorno visible al recibir
foco por teclado; el estado deshabilitado conserva su significado.

Las organizaciones, sedes, miembros, cursos e incidencias ya se consultan por
páginas reales, con `pageSize=25`, `pageToken` y `query`. El backend devuelve
`pagination.nextPageToken` y permite continuar después del registro 100. `Anterior`
reutiliza el historial de cursores y `Siguiente` consume el cursor recibido;
«Página N» es una posición de navegación, no un total de páginas inventado.
Solo se conserva la página visible y un historial de cadenas opacas.

La búsqueda espera 300 ms y cancela la consulta anterior; limpiar o cambiar el
texto vuelve a la primera página. No se filtra solo la página en memoria. Buscar
organizaciones no cambia la empresa activa: el cambio ocurre al seleccionar una
fila. Recargar conserva la selección y vuelve a autorizarla mediante workspace,
aunque la organización esté fuera de la primera página del selector.

Cada recurso tiene estado de carga y error propio. Un fallo de red permite
reintentar la misma página; un cursor inválido reinicia esa lista. Una denegación
vacía el contexto empresarial y nunca se presenta como «sin resultados». Las
respuestas canceladas no pueden restaurar otra empresa o una sesión cerrada.
El formulario de miembro conserva IDs seleccionados al buscar o cambiar de
página de sedes y muestra cuántas se eligieron. El servidor valida los IDs y
conserva el límite de 100 asignaciones por alta.

`core/domain/Page` comparte solo valores puros; los DTO, parámetros Ktor y
validación de cursores viven en `core/network/Pagination`, usados por los dos
adaptadores reales. El VM invoca casos de uso, nunca HTTP. `PageNavigation`
comparte controles accesibles y respuesta de interacción del tema. Los proxies
permiten exclusivamente parámetros acotados y reenvían solo campos públicos,
metadatos de página y totales validados. Android e iOS usan el mismo contrato
y validan tamaño de página y continuidad del cursor.

Publicación: primero backend con los nuevos endpoints/metadatos y después el
frontend. No hay nuevas variables ni migraciones. Hasta publicarlo, Railway sigue
ejecutando la versión anterior; reconstruir el frontend local no publica el API.
No hay sondeo periódico de listas ni actividad artificial para mantener sesiones.
Las lecturas de negocio siguen contando como actividad según la política del API.

Referencia: [interacciones e indicaciones de Compose](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions).

## Íconos y estados de acceso

La navegación usa recursos vectoriales Lucide de 24 dp y trazo de 2 unidades,
extraídos del mismo archivo local `assets/lucide.js` del prototipo. `BpmIcon`
comparte estos recursos entre marca, cabecera, navegación y estados; no dibuja
variantes manuales ni descarga fuentes. Cada destino conserva su etiqueta visible
y accesible; el dibujo es decorativo para evitar anuncios duplicados.
La licencia ISC se empaqueta también en `composeApp/src/commonMain/composeResources/files/lucide-license.txt`; las formas SVG se
trasladaron a XML VectorDrawable para `painterResource` multiplataforma.
Se mantienen los puntos de cambio de distribución y los colores del prototipo.

`WorkspacePlaceholder` diferencia cuenta sin organización, acceso restringido,
sesión terminada y servicio no disponible. Una respuesta 403 no se presenta
como una lista vacía ni ofrece crear una empresa. Un fallo de carga permite
reintentar sin afirmar que no existen organizaciones. Los formularios conservan
los errores de campo y el aviso de sesión compartido.
Las URL de secciones no permitidas llevan a Inicio con una explicación visible;
el backend continúa autorizando cada solicitud. El rol CHEF tiene un mensaje
explícito en Equipo y no obtiene acciones administrativas.

Verificar una sesión vuelve a cargar el espacio si cambiaron las membresías o
el rol de plataforma, incluso si antes no había organización seleccionada.
Reintentar la carga de Control solicita tanto el workspace como el catálogo de preguntas;
no depende de que Compose observe un estado intermedio de carga.

Referencias: [barras de aplicación Material 3](https://developer.android.com/develop/ui/compose/components/app-bars),
[recursos Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html)
y [Lucide](https://lucide.dev/guide/). Estas decisiones adaptan el prototipo a los
recorridos disponibles; no declaran implementados todos sus módulos.
La revalidación al volver usa el [ciclo de vida de Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-lifecycle.html);
la ventana de dos minutos es una decisión del proyecto, no una obligación del framework.

La elección de sedes agrupa etiqueta y casilla en una fila toggleable de al menos
48 dp; Checkbox delega la acción al padre. La selección usa un conjunto de IDs,
sin duplicados, y no depende de la página visible. Patrón de
[accesibilidad Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

Empresa ofrece «Gestionar plantillas» a ADMIN y SUPER_ADMIN. El editor y su catálogo
se componen desde `app` mediante slots; workspace no importa la feature checklists.
La autoría tiene su propio scroll y oculta la navegación inferior en ventanas
compactas para dar espacio al formulario. Cambiar de organización o cerrar sesión
limpia el borrador. Rutas, recuperación de publicación y límites se describen en
[`CHECKLISTS.md`](CHECKLISTS.md); esta entrada no habilita permisos en el cliente.
