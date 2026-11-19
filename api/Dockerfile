# syntax=docker/dockerfile:1
# Build context: api/
# Build with the official Maven image so nothing downloads the Maven distribution
# during the build. The wrapper's wget of apache-maven-3.9.16-bin.tar.gz from Maven
# Central failed repeatedly on GitHub runners (runs 44617903084, 44618887379), even
# with retries. Maven 3.9.16 matches .mvn/wrapper (docs/VERSIONS.md).
FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml ./
# Dependency resolution still uses Maven Central (Maven's own HTTP client, which has
# its own retries); one bounded outer retry covers a longer outage.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline || { echo "dependency download failed, retrying in 20s"; sleep 20; mvn -B -q dependency:go-offline; }
COPY src src
# Tests and Spotless run in `make test` / CI; the image build only packages the jar.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app
COPY --from=build /src/target/api.jar app.jar
# Non-root: the base image's existing unprivileged "ubuntu" user (uid 1000).
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
