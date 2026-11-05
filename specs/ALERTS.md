# Avisos personales y reglas

La ruta `avisos` abre la bandeja propia desde el icono del encabezado. El contador
se carga en el contexto autorizado; un fallo conserva un estado desconocido y su
reintento. No hay botón permanente de recarga: las consultas se revalidan al
entrar/volver y después de cambios confirmados.

ADMIN/SUPER_ADMIN configuran en `empresa/avisos`. El filtro y el ámbito del editor
usan el selector de sedes paginado del workspace; las features no se importan
entre sí. El tipo y ámbito de una regla existente son inmutables. Nombre,
intervalos, hora local, días, estado y canales se validan antes de enviar; el
backend vuelve a autorizar y validar. No se crean rangos sanitarios ni reglas
predeterminadas como si fueran requisitos aprobados.

`features/alerts` implementa dominio, casos de uso, DTO/transporte Ktor,
repositorio local, editor y ViewModel. AppGraph compone dependencias. Los borradores
se aíslan por origen, usuario y organización; salir espera su guardado. Una
respuesta incierta conserva exactamente el comando y clave para reintentar.
Un conflicto de versión requiere revisar la versión actual y conserva la copia.

Ver y atender se confirman en servidor antes de cambiar el estado. Atender un
aviso no resuelve su control o tarea: el detalle lo explica. La raíz abre la
fuente autorizada y pasa la sede correcta a la recepción; cambia de contexto al
cambiar usuario/organización. No hay ventanas Popup que rompan la accesibilidad
web; listas y formularios se componen en el árbol principal y se adaptan al ancho.

## Notificaciones Android

`features/notifications` tiene un puerto de plataforma y registro privado de
dispositivos, independiente de alerts. El usuario activa desde Mis avisos; no se
pide permiso al iniciar sesión. La aplicación usa el registro público Firebase
Android y Messaging SDK; ninguna cuenta de servicio va al cliente.

Los identificadores de registro/instalación se aíslan por usuario y organización.
El token no forma parte del estado de pantalla ni sus logs. Al perder el contexto
se retira sincrónicamente la vinculación local y se elimina el token antes de una
activación posterior. Un cambio de token invalida el estado visible; la revisión
y vinculación se comprueban atómicamente. El payload solo admite tres IDs/campos,
verifica destinatario/organización y deduplica alertId. La notificación es genérica;
los detalles privados se consultan después de autenticar en BPM.

iOS y web conservan la bandeja. Su registro externo permanece no disponible hasta
configurar SDK/APNs/VAPID; no hay botones de activación útiles sin esa capacidad.
El envío depende de V7, backend, worker separado y credenciales de proveedor en el
servidor. Compilar o registrar un dispositivo no prueba recepción física.

## Verificación

Pruebas compartidas: validación, DTO/errores, aislamiento de contexto, borrador
estable, acuses, limpieza de permisos y consentimiento/rotación. Proxy explícito:
UUID canónico, tamaño, JSON estricto, cabeceras y proyección sin tokens ni datos
internos. Se verifican JVM, web, APK Android y framework iOS Simulator, más UI web
en 320/390/768/1440 con API/PostgreSQL de prueba y sin envíos reales.

La confirmación de alta y actualización compara versiones Long explícitas. La prueba
con la respuesta real de creación (versión 1) reproduce y evita el falso estado
pendiente observado en QA. Los días de inactividad usan BpmInputKind.Integer;
Siguiente/Listo se conserva y el dominio sigue validando el intervalo 1–365.

Verificación de entrega: 246 pruebas JVM, 79 pruebas Node y compilaciones web,
APK Android y framework iOS Simulator. QA local confirma alta de regla y acuse
con API/PG reales de prueba, cuatro anchos y bandejas separadas por rol. La
recepción física de push depende del operador y un dispositivo con permiso.
