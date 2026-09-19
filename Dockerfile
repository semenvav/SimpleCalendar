# syntax=docker/dockerfile:1

# --- Stage 1: build the web UI -----------------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /build

# Dependencies first, so editing UI source does not re-run npm ci on every build.
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# --- Stage 2: build the server -----------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build

# Same trick: resolve dependencies against the build files alone before copying sources.
# VERSION comes along because build.gradle.kts reads it — it is a build file like the others.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts VERSION ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

# --- Stage 3: runtime --------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# curl is only here so the container can report its own health.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=backend /build/build/install/simple-calendar/ ./
COPY --from=frontend /build/dist/ ./static/

# Which published build this is; CI passes the number it is about to tag (see
# .github/workflows/ci.yml) and /api/health reports it. Only this last stage takes it, so a new
# number never invalidates the layers that compile the server.
ARG SC_VERSION=dev

ENV SC_PORT=8080 \
    SC_HOST=0.0.0.0 \
    SC_DATA_DIR=/data \
    SC_STATIC_DIR=/app/static \
    SC_VERSION=${SC_VERSION}

RUN mkdir -p /data
VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS --noproxy '*' http://localhost:8080/api/health || exit 1

ENTRYPOINT ["/app/bin/simple-calendar"]
