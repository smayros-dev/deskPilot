# syntax=docker/dockerfile:1
# Build: Maven inside the image (dependencies cached between builds), then split the jar into layers so that
# a code change only rebuilds the small application layer.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/deskpilot-*.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system deskpilot && useradd --system --gid deskpilot --uid 1001 deskpilot
WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER deskpilot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
