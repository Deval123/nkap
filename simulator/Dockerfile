# The scriptable fake operator, built from source. Same shape as server/Dockerfile;
# the comments there explain the multi-stage build and the layered "-boot" jar.
#
# The deployable depends on its sibling modules (simulator-core, simulator-mtn), so its
# build needs `-am` to build them in the same reactor.

# ---- build ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# `mvn package` builds the layered, runnable "-boot" jar: the repackage execution is bound in
# simulator/pom.xml, with a classifier so the plain jar stays a plain jar. That matters here
# more than in the other images: server and provider-mtn depend on this module's plain jar
# as a test library, and a fat jar in its place would not resolve.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl simulator -am package -DskipTests \
 && cp simulator/target/nkap-simulator-*-boot.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- run ----------------------------------------------------------------------------
FROM eclipse-temurin:21.0.5_11-jre-alpine

# Set by the release workflow (--build-arg), empty on a local `docker build`. See
# server/Dockerfile's own comment and .github/workflows/release.yml.
ARG NKAP_VERSION=""
ARG NKAP_REVISION=""

LABEL org.opencontainers.image.source="https://github.com/deval123/nkap" \
      org.opencontainers.image.revision="${NKAP_REVISION}" \
      org.opencontainers.image.version="${NKAP_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="Scriptable fake MTN MoMo operator for testing against Nkap or your own integration."

WORKDIR /app
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
