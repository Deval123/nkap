# The gateway, built from source so a clone with no JDK can run it.
#
# Multi-stage: the build stage has Maven and a JDK and produces the jar; the final image
# carries only a JRE. The jar is unpacked into Spring Boot's layers so a code change
# rebuilds only the thin application layer and leaves the large dependency layer cached.
#
# Base images are pinned by exact tag on purpose: a build that must still work in a year
# cannot depend on what a floating tag means then.

# ---- build ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# `mvn package` builds the layered, runnable "-boot" jar (see the repackage execution in
# server/pom.xml). The BuildKit cache mount keeps the Maven repository between builds
# without baking it into an image layer, so editing a source file recompiles but does not
# re-download.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl server -am package -DskipTests \
 && cp server/target/nkap-server-*-boot.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- run ----------------------------------------------------------------------------
FROM eclipse-temurin:21.0.5_11-jre-alpine

# Set by the release workflow (--build-arg), empty on a local `docker build` -- a label with
# an empty value is still valid OCI, just not useful, which is the correct fallback for a
# build nobody intends to publish. See .github/workflows/release.yml.
ARG NKAP_VERSION=""
ARG NKAP_REVISION=""

# `docker inspect` should answer "where did this come from" without a search -- this sits in
# a payment path, and that is the minimum a reader is owed (issue #86).
LABEL org.opencontainers.image.source="https://github.com/deval123/nkap" \
      org.opencontainers.image.revision="${NKAP_REVISION}" \
      org.opencontainers.image.version="${NKAP_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="Nkap gateway: REST API, ledger, reconciler and webhooks for MTN MoMo."

WORKDIR /app
# Ordered most- to least-stable, so an application change invalidates only the last COPY.
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
