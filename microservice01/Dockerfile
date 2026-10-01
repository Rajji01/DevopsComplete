# Multi-stage build: the JAR is built inside Docker, so a local `mvn package` is no longer needed.
# Build: docker build -t <image-name> .

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# copy pom first so dependency downloads are cached between builds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests && cp target/*.jar app.jar

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# run as non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

COPY --from=build /build/app.jar app.jar

# Port the Spring Boot application listens on
EXPOSE 8081
ENV TESTING_PROPERTY="DONE!!!"

# Respect container memory limits (K8s limits are 128Mi-ish)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
