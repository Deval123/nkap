# The scriptable fake operator, built from source. Same shape as server/Dockerfile;
# the comments there explain the multi-stage build and the layered "-boot" jar.
#
# The simulator depends on no other module, so its build does not need `-am`.

# ---- build ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# `spring-boot:repackage` is run here, not bound in the pom: the project uses the Spring
# Boot BOM rather than the starter parent, so `mvn package` alone produces a plain jar,
# and binding repackage would hand a fat jar to the modules that depend on this one as a
# test library. Repackaging only in the image keeps that blast radius at zero.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl simulator package -DskipTests \
 && cp simulator/target/nkap-simulator-*-boot.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- run ----------------------------------------------------------------------------
FROM eclipse-temurin:21.0.5_11-jre-alpine
WORKDIR /app
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
