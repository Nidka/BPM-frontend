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
  retoma tras iniciar sesión.

## Concurrencia y errores

Cada fila lleva `rowVersion` y se reenvía el leído. Ante `VERSION_CONFLICT` (o 404) se cierra el
formulario, se recarga la lista y se avisa; nunca se reintenta solo. `LAST_ADMIN_REQUIRED` deja abierta la
confirmación con un mensaje que indica transferir antes la administración. Los mensajes son sanitizados
(`accountFailureMessage`); el servidor nunca aporta texto.

## Proxy web

`web/accounts-proxy.mjs` (registrado en `web/server.mjs`) cubre `PATCH` de organización, sede y miembro,
acciones de miembro, `invitations` (GET/POST/resend/revoke), `POST /invitations/accept` y `me/devices`
(GET/DELETE). Valida cuerpos exactos (sin campos extra), filtros cerrados (`status`, `query`, `pageSize`,
`pageToken`), origen y Bearer; proyecta respuestas (nunca token de invitación ni token push) y solo conserva
los códigos de dominio documentados. `workspace-proxy.mjs` acepta ahora `status=ACTIVE|INACTIVE` solo en
miembros y sedes y devuelve `rowVersion`/`status` cuando existen. Pruebas en `web/accounts-proxy.test.mjs`.

## Decisiones y límites

- La organización se lee con `GET /organizations/{id}/workspace?pageSize=1` porque es la lectura que
  expone `rowVersion` de la organización.
- Las sedes para asignar se leen hasta 5 páginas de 100 (500 sedes); más allá no aparecen en el selector.
- No existe en el contrato el borrado de miembros: solo baja lógica.
- Los dispositivos web/Android/iOS se listan por igual; el registro de push sigue en `features/notifications`.
- Android/iOS: el código es común (commonMain); no se compiló androidMain/iosMain sin SDK/macOS.
