# Agente normativo (RF-46/47) y automatizaciones (RF-48)

## Consultar normativa

Inicio → «Consultar normativa» (todos los roles). La persona escribe una pregunta y el servidor responde con
Claude usando **solo** la normativa que cargó el administrador general; cada respuesta muestra sus fuentes
(fragmentos literales). Si la normativa no la respalda, se marca «Sin respaldo en la normativa cargada». Las
consultas viven solo en memoria durante la sesión. Límite: 20 por hora y persona (lo informa el servidor).
Requiere el backend con V17 y `ANTHROPIC_API_KEY`; sin clave la pantalla explica que el asistente no está
activado. La pantalla va dentro de `ServerOnly` (no funciona sin conexión).

El administrador general (SUPER_ADMIN) ve además la pestaña «Normativa cargada»: cargar (texto completo, hasta
400 000 caracteres), editar, archivar y reactivar. Solo la normativa activa se usa en las respuestas.

## Automatizaciones (webhooks para n8n)

Empresa → «Automatizaciones» (ADMIN). Registra hasta 5 direcciones HTTPS públicas y elige qué avisos recibe
cada una. El secreto de firma se muestra una sola vez al crear o rotar (con botón para copiarlo) y nunca se
guarda en el dispositivo. «Enviar prueba» manda un evento `TEST` y muestra la respuesta. Requiere V18 del
backend; contrato de firma en BPM-service/specs/WEBHOOKS.md.

## Web

`web/normative-proxy.mjs` y `web/webhooks-proxy.mjs` validan entradas y salidas con contrato cerrado
(`web/proxy-common.mjs`); la consulta normativa espera hasta 130 s. Ambos archivos están declarados en el
Dockerfile.
