# Plan de controles conservados y envío pendiente

> Ejecución directa en esta sesión mediante `superpowers:executing-plans`; verificar cada tarea antes de continuar.

**Objetivo:** preparar varios controles propios ya iniciados, responderlos sin red y sincronizar sus respuestas/finalización sin perder ni duplicar operaciones.
**Arquitectura:** capacidad de checklists con dominio puro, repositorio local atómico, sincronizador serial y ViewModel. AppGraph compone; App adapta sesión/tenant y navegación. Se reutiliza el editor de respuestas existente. El servidor sigue autorizando y calculando los resultados.
**Tecnología:** Kotlin Multiplatform, Compose, StateFlow, Ktor y DraftStorage privado de cada plataforma.
**Especificación:** `specs/OFFLINE.md`, `specs/CHECKLISTS.md`; RF-12–17 y RF-33 del plan del proyecto.

## Restricciones

- Preparar exige una lectura autorizada en línea de un borrador propio. No se crea un control ficticio ni se reutiliza el período de una asignación vencida.
- Se conservan los permisos remotos: al abrir una nueva sesión se revalida cada control antes de revelar sus respuestas. No hay autenticación sin red ni arranque web offline garantizado.
- Una entrega pendiente conserva claves, versión y cuerpo exactos. La confirmación de guardado se persiste antes de finalizar. Respuesta perdida o escritura local fallida repiten la misma operación.
- Sincronización en primer plano tras abrir/retomar, solicitud explícita y hasta tres intentos automáticos espaciados; no temporizador de actividad ni renovación artificial de sesión.
- Conflictos, denegación y datos inválidos requieren intervención; no sobrescribir ni descartar respuestas automáticamente.
- Los textos distinguen «Guardado en este dispositivo», «Pendiente de enviar» y «Confirmado». No muestran detalles técnicos ni éxito remoto supuesto.
- La cola y almacenamiento siguen acotados; un límite/fallo bloquea una escritura nueva sin reemplazar registros existentes.

## Revisión prioritaria

1. Respuesta de guardado/finalización perdida: conservar claves y evitar duplicados tras reinicio.
2. Logout/cambio de empresa durante HTTP: cancelar y no restaurar datos de contexto anterior.
3. Permisos revocados o sede ajena: no revelar copia local en una nueva sesión.
4. Dos controles con ediciones rápidas: serialización local y aislamiento; cinco recorridos sobreviven a reinstanciar repositorio.
5. Error de disco/cuota: no anunciar guardado ni enviar cuerpo que no quedó duradero.

## Tarea 1: Cola duradera y sincronización

Archivos: `features/checklists/domain/ChecklistOffline.kt`, `data/LocalChecklistOfflineRepository.kt`, `commonTest/.../ChecklistOfflineTest.kt`.
Interfaces: `ChecklistOfflineRepository` lee/reemplaza una colección por DraftOwner; `ManageOfflineChecklists` prepara/edita/encola; `SyncOfflineChecklists` procesa operaciones con los puertos ChecklistRunReader/Writer existentes.
- [x] Escribir y ejecutar pruebas negativas de cinco controles, pérdida de respuesta, conflicto, fallo local y actor ajeno.
- [x] Implementar estados, serialización validada y exclusión mutua por repositorio/sincronizador.
- [x] Ejecutar JVM completo y confirmar ausencia de regresiones.

## Tarea 2: Flujo y componentes reutilizados

Archivos: `presentation/ChecklistAnswerEditor.kt`, `ChecklistOfflineViewModel.kt`, `ChecklistOfflineScreen.kt`, `app/App.kt`, `core/di/AppGraph.kt`.
- [x] Probar recuperación autorizada, edición local, preparación, cancelación y envío/finalización.
- [x] Extraer editor usado en línea y sin conexión; incorporar rutas lista/detalle accesibles a CHEF y estados claros.
- [x] Probar que «Siguiente»/«Listo» y validaciones mantienen el mismo comportamiento.

## Tarea 3: Verificación y entrega

- [x] Documentar uso, límites y pendientes reales; no afirmar que el contrato offline completo del piloto ya está cubierto.
- [x] Ejecutar JVM, pruebas Node/imports, distribución web, APK y framework iOS.
- [x] Revisar pantalla móvil/escritorio y diff final; registrar resultados y entrega aislada sin mezclar stubs de capacitación.

## Registro de ejecución

- Base aislada: `17c362a`; los archivos previos del directorio original se conservan.
- Base: 145 pruebas JVM y 30 Node aprobadas antes de integrar la funcionalidad.
- Las pruebas nuevas fallaron primero por APIs ausentes (cola, ViewModel, resolución y control de colisiones); después aprobaron.
- Suite ampliada: 159 pruebas JVM, sin fallos. Web/Android/framework iOS compilados con la entrega final exacta. Prueba visual y funcional aprobada a 320, 390, 768 y 1440 píxeles; sin errores de ejecución observados.
- IndexedDB real: dos comparaciones concurrentes producen un solo ganador; una versión obsoleta no reemplaza datos; la transacción confirma el valor persistido.
- Decisión: comenzar con controles preparados, sin inventar autenticación offline ni períodos atrasados. El inicio totalmente offline, dispositivos y cargas de archivos siguen en el siguiente recorrido.
- Revisión del autor en una pasada separada: se detectó y corrigió la colisión de la misma respuesta entre pestañas, con prueba negativa y suite verde. No se utilizó revisión por subagentes.

- Prueba funcional en Chrome con perfil y API aislados: edición Unicode sin conexión, estado pendiente sin escritura remota y luego dos operaciones confirmadas (guardar y finalizar). No se usaron cuentas ni datos reales.
- Fuentes integradas en el directorio original solo después de comprobar que coincidían con main; se preservaron los stubs previos del backend. Artefactos web y APK actualizados.
- No se publicaron cambios de esta entrega ni se modificó producción. La comprobación pública del despliegue anterior sigue devolviendo 404 para el proxy de programación; el backend responde 200 a health.
- Advertencias existentes del build: tamaño del bundle Wasm y recomendaciones de webpack; no se ocultaron ni se configuró una falsa reducción de tamaño.
