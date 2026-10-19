FROM eclipse-temurin:21-jdk AS build
# El Node descargado por Kotlin necesita libatomic en Linux.
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && apt-get clean
WORKDIR /src
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY config ./config
COPY kotlin-js-store ./kotlin-js-store
COPY composeApp ./composeApp
RUN chmod +x gradlew && ./gradlew :composeApp:wasmJsBrowserDistribution --no-daemon --console=plain

FROM node:24-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build --chown=node:node /src/composeApp/build/dist/wasmJs/productionExecutable ./public
COPY --chown=node:node web/server.mjs ./web/server.mjs
COPY --chown=node:node web/workspace-proxy.mjs ./web/workspace-proxy.mjs
COPY --chown=node:node web/checklists-proxy.mjs ./web/checklists-proxy.mjs
COPY --chown=node:node web/checklist-runs-proxy.mjs ./web/checklist-runs-proxy.mjs
COPY --chown=node:node web/pagination.mjs ./web/pagination.mjs
COPY --chown=node:node web/session-headers.mjs ./web/session-headers.mjs
COPY --chown=node:node web/google-auth-browser.mjs web/google-auth-controller.mjs ./web/
COPY --chown=node:node config/firebase.public.json ./config/firebase.public.json
ENV DIST_DIR=/app/public
USER node
EXPOSE 3000
CMD ["node", "web/server.mjs"]
