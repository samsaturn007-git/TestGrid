# syntax=docker/dockerfile:1
# Build context: api/
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# The wrapper's single wget has no retry; a transient Maven Central error failed CI once
# (run 44617903084). Retry with backoff so one network blip doesn't fail `make up`.
RUN --mount=type=cache,target=/root/.m2 \
    for delay in 5 15 0; do ./mvnw -B -q dependency:go-offline && exit 0; \
      [ "$delay" -eq 0 ] && exit 1; echo "maven download failed, retrying in ${delay}s"; sleep "$delay"; done
COPY src src
# Tests and Spotless run in `make test` / CI; the image build only packages the jar.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app
COPY --from=build /src/target/api.jar app.jar
# Non-root: the base image's existing unprivileged "ubuntu" user (uid 1000).
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
