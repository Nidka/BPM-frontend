# Cuentas, invitaciones, dispositivos y tipo de establecimiento

Implementa en el cliente la sección «Gestión de cuentas, invitaciones, dispositivos y tipo de
establecimiento» de `ORGANIZATIONS.md` del backend (requiere su migración V12 antes de activar la UI;
no implica despliegue). Recorrido vertical en `features/accounts` (sin importar otras features) y
cambios pequeños en `features/workspace`.

## Pantallas y rutas

| Ruta | Pantalla | Acceso en la UI |
|---|---|---|
| `empresa/cuentas` | Cuentas y equipo: pestañas Equipo, Invitaciones y Organización y sedes | ADMIN/SUPER_ADMIN (tarjeta en Empresa, botón en Equipo) |
| `cuenta/dispositivos` | Mis dispositivos: listar y revocar | cualquier persona autenticada (Mi cuenta) |
| `cuenta/invitacion?token=` | Aceptar invitación | cualquier persona autenticada (Mi cuenta o enlace del correo) |

- **Equipo**: filtro Activas/Inactivas, búsqueda y paginación por cursor; editar rol y sedes; desactivar y
  reactivar; transferir administración. Las acciones con efecto piden confirmación en línea (sin ventana
  emergente). Si quien transfiere es un ADMIN miembro debe elegir su nuevo rol (CHEF/WORKER) y sedes; un
  SUPER_ADMIN no miembro omite ambos campos. La transferencia solo se ofrece sobre miembros activos que no
  son ADMIN ni la propia persona.
- **Invitaciones**: invitar con correo, rol y sedes; listado con estado (Pendiente, Aceptada, Revocada,
  Vencida) y filtro; reenviar (con cuenta regresiva de 1 minuto calculada con `lastSentAt`; el servidor
  vuelve a validar con 429) y revocar con confirmación. El `id` de la invitación lo genera el cliente: se
  conserva al reintentar el mismo contenido y cambia si se edita (evita `RESOURCE_CONFLICT`).
- **Organización y sedes**: edición completa (reemplazo) de organización, incluido el tipo de
  establecimiento (Restaurante, Cafetín, Catering, Otro servicio de alimentos), y de cada sede (nombre,
  dirección, distrito, teléfono, zona horaria IANA, activa/inactiva) con filtro Activas/Inactivas.
- **Crear organización** (`workspace`): el tipo de establecimiento es obligatorio y empieza sin elegir;
  `RELATED_SERVICE` se mapea a `OTHER_FOOD_SERVICE` (también en borradores locales antiguos).
- **Aceptar invitación**: se pega el código o el enlace completo, o llega por `?token=`. El token solo
  precarga el campo; **nunca se acepta solo**. Se valida (43 caracteres base64url) antes de llamar, viaja
  solo en el cuerpo y no se registra ni se guarda en disco. Tras aceptar se vuelve a consultar el perfil
  (`verifySession`) para recargar las membresías. `INVITATION_ACCEPT_URL` del backend debe apuntar a
  `https://<app>/cuenta/invitacion`; si la persona no tiene sesión, la ruta (con token) se conserva y se
  retoma tras iniciar sesión. Si aún no tiene cuenta, la pantalla de acceso lo avisa («Tienes una invitación pendiente»): crea la
  cuenta con el correo invitado, confirma el correo y la app vuelve a `cuenta/invitacion?token=` (`pendingRoute` no se borra en el
  alta ni en la verificación; solo cerrar sesión lo reinicia).

### Compartir la invitación por enlace
`POST /organizations/{org}/invitations` y `.../invitations/{id}/resend` devuelven además `acceptUrl` (cadena o null) y, si es
null, `token`. Solo esas dos respuestas los traen; la lista no. El cliente (`SharedInvitation`, `InvitationShare.Link|Code`) los lee
sin guardarlos: no se escriben en disco, no salen en `Invitation` ni en la lista y desaparecen al cerrar el panel (`closeShare`),
al abrir otra acción o al salir de la pantalla. Si se pierde, se reenvía (el enlace anterior deja de servir).

Tras crear o reenviar aparece el panel `InvitationSharePanel` con el enlace (o el código) y tres botones:

- **Copiar enlace** (o «Copiar código»): portapapeles del sistema (`TextSharer.copy`; Android lo marca como sensible, iOS lo limita a
  este dispositivo y lo caduca a los 10 minutos).
- **Enviar por WhatsApp**: abre `https://wa.me/?text=` con «Te invito a unirte a <empresa> en BPM. Crea tu cuenta con <correo> y
  abre: <enlace>» codificado en porcentaje UTF-8 (`InvitationSharing`). Con código, el texto indica dónde pegarlo.
- **Compartir**: hoja nativa en Android (`ACTION_SEND`) e iOS (`UIActivityViewController`) mediante `expect/actual` (`rememberTextSharer`);
  en web `navigator.share` si existe y, si no, copia el mensaje.

Aviso visible: «El enlace solo funciona para la cuenta con el correo <correo>, vale 7 días y se usa una vez».

## Concurrencia y errores

Cada fila lleva `rowVersion` y se reenvía el leído. Ante `VERSION_CONFLICT` (o 404) se cierra el
formulario, se recarga la lista y se avisa; nunca se reintenta solo. `LAST_ADMIN_REQUIRED` deja abierta la
confirmación con un mensaje que indica transferir antes la administración. Los mensajes son sanitizados
(`accountFailureMessage`); el servidor nunca aporta texto.

## Proxy web

`web/accounts-proxy.mjs` (registrado en `web/server.mjs`) cubre `PATCH` de organización, sede y miembro,
acciones de miembro, `invitations` (GET/POST/resend/revoke), `POST /invitations/accept` y `me/devices`
(GET/DELETE). Valida cuerpos exactos (sin campos extra), filtros cerrados (`status`, `query`, `pageSize`,
`pageToken`), origen y Bearer; proyecta respuestas (token de invitación solo en el POST de invitar y en `resend`, nunca en listas ni en
revocar; tampoco token push) y solo conserva
los códigos de dominio documentados. `workspace-proxy.mjs` acepta ahora `status=ACTIVE|INACTIVE` solo en
miembros y sedes y devuelve `rowVersion`/`status` cuando existen. `acceptUrl` y `token` pasan **solo** en `POST .../invitations` y
`.../resend`, con formato validado: `acceptUrl` https (http solo en localhost), sin credenciales ni fragmento, ruta `/cuenta/invitacion`
y un único `token` de 43 caracteres base64url; `token` de 43 caracteres solo cuando `acceptUrl` es null. Un formato inválido responde
503 sin entregar nada. Pruebas en `web/accounts-proxy.test.mjs`.

## Decisiones y límites

- La organización se lee con `GET /organizations/{id}/workspace?pageSize=1` porque es la lectura que
  expone `rowVersion` de la organización.
- Las sedes para asignar se leen hasta 5 páginas de 100 (500 sedes); más allá no aparecen en el selector.
- No existe en el contrato el borrado de miembros: solo baja lógica.
- Los dispositivos web/Android/iOS se listan por igual; el registro de push sigue en `features/notifications`.
- Android/iOS: el código es común (commonMain); no se compiló androidMain/iosMain sin SDK/macOS.
