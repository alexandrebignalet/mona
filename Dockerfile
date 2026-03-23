# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Gradle wrapper and dependency manifests first for layer caching
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Warm up the dependency cache
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build the fat JAR
COPY src ./src
RUN ./gradlew shadowJar --no-daemon -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Install curl for the Litestream download and ca-certificates
RUN apk add --no-cache curl ca-certificates

# Install Litestream
ARG LITESTREAM_VERSION=0.3.13
RUN curl -sSL \
      "https://github.com/benbjohnson/litestream/releases/download/v${LITESTREAM_VERSION}/litestream-v${LITESTREAM_VERSION}-linux-amd64.tar.gz" \
    | tar -xz -C /usr/local/bin litestream \
 && chmod +x /usr/local/bin/litestream

WORKDIR /app

# Copy the fat JAR from the build stage
COPY --from=builder /build/build/libs/*.jar mona.jar

# Copy Litestream config and startup script
COPY litestream.yml /etc/litestream.yml
COPY start.sh /start.sh
RUN chmod +x /start.sh

EXPOSE 8080

CMD ["/start.sh"]
