# syntax=docker/dockerfile:1
# One Dockerfile for every service:
#   docker build --build-arg SERVICE=order-service     -t shopflow/order-service .
#   docker build --build-arg SERVICE=inventory-service -t shopflow/inventory-service .
ARG SERVICE

# ---------- 1. build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY . .
# cache ~/.m2 between builds (BuildKit) so dependencies are not downloaded every time
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${SERVICE} -am package -DskipTests

# ---------- 2. split the fat jar into layers ----------
# dependencies change rarely, our code changes often -> separate image layers,
# so a code-only change pushes/pulls a few KB instead of ~60 MB
FROM eclipse-temurin:21-jre-alpine AS extract
ARG SERVICE
WORKDIR /extract
COPY --from=build /src/${SERVICE}/target/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination out

# ---------- 3. runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# fixed numeric non-root UID so K8s runAsNonRoot can verify it
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
USER 10001

COPY --from=extract /extract/out/dependencies/ ./
COPY --from=extract /extract/out/spring-boot-loader/ ./
COPY --from=extract /extract/out/snapshot-dependencies/ ./
COPY --from=extract /extract/out/application/ ./

# heap sized from the container memory limit; crash (and get restarted) instead of limping on OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
