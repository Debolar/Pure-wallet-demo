# ---------- Stage 1: build the .jar (big image with Maven + JDK) ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Download libraries first: this layer is cached until pom.xml changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI, so skip them here to keep builds fast
RUN mvn -B -q package -DskipTests

# ---------- Stage 2: run it (small image with only the Java runtime) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl is only for the health check
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home appuser
COPY --from=build /build/target/purse.jar app.jar
ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION
# Never run as root
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health/liveness || exit 1
# Use up to 75% of the container's memory limit for Java
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
