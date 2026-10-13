# Espacio de trabajo y diseño BPM Aula

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
| Empresa | Datos, sedes y altas autorizadas | Edición/desactivación y paginación |
| Equipo | Miembros y alta por correo de cuenta existente | Invitaciones, cambios de rol y bajas |
| Aprender | Catálogo publicado y estado de progreso existente | Lecciones, evaluaciones, XP y constancias |
| Control | Plantillas activas e incidencias visibles | Ejecutar/responder controles y gestionar incidencias |

Cada lista es de hasta 100 registros; los conteos son de los registros cargados,
no estadísticas globales. No se presentan botones que simulen completar cursos
o registrar controles. R2, operación diaria, investigación y alertas quedan en
sus specs, no están terminados por tener navegación.

Este recorrido necesita internet. No persiste cambios pendientes ni ofrece
éxito offline. La cola/versionado/sincronización sigue el futuro contrato OFFLINE.
El alta de miembro no envía correos: la persona debe registrarse primero.

## Contratos, seguridad y pruebas

`features/workspace/{domain,data,presentation}` sigue MVVM y Clean Architecture.
AppGraph inyecta repositorio/casos de uso/VM. El backend valida usuario, tenant,
rol y sedes en cada llamada; ocultar botones solo mejora la experiencia.

El proxy `web/workspace-proxy.mjs` acepta únicamente los cinco endpoints del
workspace, métodos explícitos, Bearer, JSON limitado y origen válido para POST.
No reenvía cookies ni cabeceras de tenant. Filtra los campos públicos y sanitiza
errores/fields. El DTO de /me conserva platformRole para mostrar las acciones
permitidas, pero nunca lo envía como autoridad al backend.

Tests: sesión requerida, DTO sin permisos, errores sanitizados, cancelación al
cambiar tenant/logout, proxy cerrado. Prueba manual administrativa: cuenta
autorizada → crear empresa → sede → agregar otra cuenta existente → cerrar
sesión → entrar con la segunda cuenta y comprobar solo sus permisos.
La contraseña y el consentimiento Google los introduce el titular.
