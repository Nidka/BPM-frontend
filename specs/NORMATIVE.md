# Buscador de normativa (RF-46/47) y automatizaciones (RF-48)

## Buscar en la normativa

Inicio → «Buscar en la normativa» (todos los roles). La persona escribe palabras («temperatura pollo») y ve los
párrafos de la normativa activa que las contienen, con el título y la referencia del documento y las palabras
resaltadas. La búsqueda es de texto completo en español en el servidor (PostgreSQL): no distingue tildes ni
mayúsculas, reconoce plurales y variantes, y si ninguna página tiene todas las palabras muestra las que tienen
alguna. Se busca mientras se escribe (pausa de 350 ms). **No usa IA ni servicios externos: no tiene costo por
consulta.** Usa el campo compartido `BpmSearchField` (560 dp como máximo, todo el ancho en compacta) y los
resultados van en la columna de lectura de 720 dp. Requiere V17 del backend; con un servidor anterior muestra
«Buscador aún no disponible». Va dentro de `ServerOnly`.

El administrador general (SUPER_ADMIN) ve además la pestaña «Normativa cargada»: cargar (texto completo, hasta
400 000 caracteres; el servidor lo divide en párrafos buscables), editar, archivar y reactivar. Solo la
normativa activa aparece en las búsquedas.

## Automatizaciones (webhooks para n8n)

Empresa → «Automatizaciones» (ADMIN). Registra hasta 5 direcciones HTTPS públicas y elige qué avisos recibe
cada una. El secreto de firma se muestra una sola vez al crear o rotar (con botón para copiarlo) y nunca se
guarda en el dispositivo. «Enviar prueba» manda un evento `TEST` y muestra la respuesta. Requiere V18 del
backend; contrato de firma en BPM-service/specs/WEBHOOKS.md.

## Web

`web/normative-proxy.mjs` y `web/webhooks-proxy.mjs` validan entradas y salidas con contrato cerrado
(`web/proxy-common.mjs`) y están declarados en el Dockerfile.
