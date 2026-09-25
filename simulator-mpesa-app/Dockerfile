# The scriptable fake M-Pesa operator, built from source. The same shape as simulator/Dockerfile,
# which is MTN's, and server/Dockerfile, whose comments explain the multi-stage build and the
# layered "-boot" jar.
#
# The deployable depends on its sibling modules (simulator-core, simulator-mpesa), so its build
# needs `-am` to build them in the same reactor.

# ---- build ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# `mvn package` builds the layered, runnable "-boot" jar: the repackage execution is bound in
# simulator-mpesa-app/pom.xml, with a classifier so the plain jar stays a plain jar.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl simulator-mpesa-app -am package -DskipTests \
 && cp simulator-mpesa-app/target/nkap-simulator-mpesa-app-*-boot.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- run ----------------------------------------------------------------------------
FROM eclipse-temurin:21.0.5_11-jre-alpine

# Set by the release workflow (--build-arg), empty on a local `docker build`. See
# server/Dockerfile's own comment and .github/workflows/release.yml.
ARG NKAP_VERSION=""
ARG NKAP_REVISION=""

# The description is what a registry page shows, so the exposure is said there and not only in
# the README: GET /_nkap/received serves the Consumer Key and Secret this simulator was sent, in
# the clear, and a Password a passkey can be read back from, to anyone who can reach /_nkap.
LABEL org.opencontainers.image.source="https://github.com/deval123/nkap" \
      org.opencontainers.image.revision="${NKAP_REVISION}" \
      org.opencontainers.image.version="${NKAP_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="Scriptable fake M-Pesa (Safaricom Daraja STK Push) operator, for tests and sandboxes only. Its /_nkap control plane is unauthenticated by design and reports the credentials it received, Consumer Key and Secret in the clear: never point anything holding real credentials at it."

WORKDIR /app
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
