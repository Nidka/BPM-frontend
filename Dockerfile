FROM eclipse-temurin:21-jdk AS build
# El Node descargado por Kotlin necesita libatomic en Linux.
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && apt-get clean
WORKDIR /src
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY kotlin-js-store ./kotlin-js-store
COPY composeApp ./composeApp
RUN chmod +x gradlew && ./gradlew :composeApp:wasmJsBrowserDistribution --no-daemon --console=plain

FROM node:24-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build --chown=node:node /src/composeApp/build/dist/wasmJs/productionExecutable ./public
COPY --chown=node:node web/server.mjs ./web/server.mjs
ENV DIST_DIR=/app/public
USER node
EXPOSE 3000
CMD ["node", "web/server.mjs"]
