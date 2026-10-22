# Entrada, validación y teclado

Las reglas de formato puras viven en `core/domain/InputRules.kt`. Identidad,
workspace y controles declaran sus límites mediante `IdentityInputRules`,
`WorkspaceInputRules` y `ChecklistInputRules`. Los casos de uso las ejecutan antes
de consultar repositorios; los mismos objetos llegan al campo para mostrar ayuda.
No hay expresiones regulares de negocio en las pantallas ni validación en los DTO.
La API revalida las escrituras: un teclado restringido no protege la entrada
pegada, el teclado físico ni una solicitud construida fuera de la aplicación.

| Dato | Regla | Entrada móvil |
|---|---|---|
| Nombres y apellidos | Letras Unicode y marcas diacríticas, espacios, apóstrofos y guiones; sin dígitos; hasta 80 por campo y 100 en conjunto | Texto, capitalización por palabra, sin autocorrección |
| Persona de contacto | Mismo formato personal; opcional, hasta 160 | Igual que nombre personal |
| Razón social y nombre comercial | Texto de hasta 200; razón social obligatoria, mínimo 2; admite números | Texto |
| Sede y dirección | Sede 2–160, dirección 3–300; admiten números | Texto |
| Distrito e identificación fiscal | Opcionales; 120 y 20 respectivamente | Texto; el identificador fiscal admite alfanuméricos, no se presupone que siempre sea RUC peruano |
| Correo | Correo sin espacios interiores/controles; dominio con punto; hasta 320 bytes | Email, sin autocorrección |
| Teléfono | Opcional, 7–15 dígitos, hasta 30 caracteres con formato; `+` solo al inicio, espacios, guiones y paréntesis | Teléfono |
| Contraseña de registro | 8–128 caracteres; confirmación idéntica | Contraseña; no se modifica ni se recorta |
| Contraseña de acceso | Obligatoria, hasta 4096; no impone al usuario existente la política de un registro nuevo | Contraseña |
| Plantilla y preguntas | Nombre 2–180, área 2–80, descripción 0–500, grupo 2–120, enunciado 2–300 | Texto |
| Número/temperatura | Signo opcional, coma o punto, hasta dos decimales, magnitud menor que 100000000 | Decimal; el teclado que presente Android depende del IME instalado |
| Respuesta y observación | Hasta 2000 bytes, según contrato de la API | Texto |

`DecimalInput` comparte análisis y validación entre editor y ejecución. No acepta
exponentes, NaN, infinito ni separadores mezclados. La medición puede estar fuera
del criterio sanitario: es un incumplimiento que debe poder registrarse, no un
error de formato que deba borrarse. Los rangos publicados sí requieren mínimo ≤
máximo. La unidad es opcional y respeta el límite de 20 bytes del servidor.

## Presentación y foco

`BpmTextField` recibe una regla; no decide cuál corresponde. Conserva lo escrito
sin eliminar caracteres mientras el IME compone tildes o pega texto. Muestra el
error al abandonar el campo o después de intentar enviar y lo actualiza al
corregir. Los mensajes son nodos accesibles y regiones de anuncio moderado.
Una validación fallida no borra las contraseñas efímeras del formulario.

`BpmInputKind` centraliza teclado, capitalización y autocorrección. «Siguiente»
avanza al próximo campo visible; «Listo» oculta el teclado y no envía formularios.
`rememberBpmFormNavigation` conserva el FocusRequester de cada clave aunque se
agreguen preguntas o cambie su tipo; recalcula únicamente el orden. Al enviar con
errores, identidad, workspace y editor enfocan el primer campo señalado.
Compose gestiona Enter con la acción Siguiente/Listo en campos de una línea, sin
enviar formularios. Se resuelven tanto Unspecified (predeterminado de Compose
1.11) como Default antes de pasar las opciones al campo. No hay interceptores
propios de teclas; los multilínea y Tab/Shift+Tab conservan el comportamiento nativo.
BpmTextField conserva TextFieldValue internamente para mantener selección y
composición sin reenviar movimientos del cursor al ViewModel. Los campos
dinámicos conservan claves estables.
La distribución de un teclado físico configurado en el emulador sigue siendo
responsabilidad de Android; no se interceptan caracteres para remediarla.

El buscador de incorporación admite prefijos, por lo que no exige un correo
completo mientras busca. El alta sí exige correo completo, READY vigente, rol y
sedes válidas, con autorización nueva en el backend.

## Verificación

Pruebas compartidas cubren nombres con tildes/ñ/marcas combinadas/apóstrofos,
nombres con dígitos, empresa y sede con números, teléfonos pegados, correos,
decimales negativos/coma/precisión y rechazo antes de HTTP. El backend tiene
pruebas equivalentes y mantiene sus errores por campo.
Revisar en Android con teclado en pantalla: correo → Siguiente → contraseña →
Listo; teléfono; mínimo/máximo decimal; agregar/cambiar/reordenar una pregunta.
El build de Android no sustituye esa comprobación con el IME del dispositivo.

Referencias: [configuración de campos Compose](https://developer.android.com/develop/ui/compose/text/user-input)
y [navegación de foco](https://developer.android.com/develop/ui/compose/touch-input/focus/focus-in-text-fields).
