# ModelGate container images — one Dockerfile, two runnable targets.
#
#   build   throw-away Maven build of all 10 modules
#   proxy   gateway runtime        (SERVER_PORT=8080, needs Redis + the mocks)
#   mock    mock upstream runtime  (SERVER_PORT=9001/9002, zero dependencies)
#
# Build by hand:
#   docker build --target proxy -t modelgate-proxy:0.1.0 .
#   docker build --target mock  -t modelgate-mock:0.1.0  .
#
# Normally you do not: docker-compose.yml builds both targets for you.
#
# Deliberately no `# syntax=` directive — the BuildKit frontend bundled with
# Docker 23+ already understands RUN --mount=type=cache, and pinning an external
# frontend image would add one more Docker Hub round trip to every build.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

# ── stage 1: build ───────────────────────────────────────────────────────────
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src

COPY maven-settings.xml ./
COPY . .

# The project carries its own Maven settings: this machine's global settings.xml
# points localRepository at a Windows path and uses an http mirror, which Maven
# 3.9 refuses. See maven-settings.xml for the full story.
#
# The /root/.m2 cache mount outlives the build, so a rebuild after a source-only
# change recompiles instead of re-downloading the Spring Boot dependency tree.
# Tests are skipped: they need a local redis-server binary (see modelgate-testkit)
# and the container is not the place to prove correctness — CI and the host are.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s maven-settings.xml -DskipTests package

# ── stage 2: shared runtime base ─────────────────────────────────────────────
FROM ${JRE_IMAGE} AS runtime

# curl is used by HEALTHCHECK below and by the smoke tests in docker/README.md.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# The containers never need to write to their own filesystem: state lives in
# Redis, accounting in the DB, logs on stdout. So everything runs non-root.
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin modelgate

COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

WORKDIR /app

# ── stage 3: gateway ─────────────────────────────────────────────────────────
FROM runtime AS proxy

# `*.jar` matches the Spring Boot repackaged jar only — the plain one that the
# plugin leaves behind is named *.jar.original.
COPY --from=build --chown=modelgate:modelgate \
     /src/modelgate-proxy/target/modelgate-proxy-*.jar /app/app.jar

ENV SERVER_PORT=8080
EXPOSE 8080

# Fails when Redis is unreachable too, because the docker profile turns the Redis
# health indicator on — a gateway without its shared quota counters is not healthy.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

USER modelgate
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# ── stage 4: mock upstream ───────────────────────────────────────────────────
FROM runtime AS mock

COPY --from=build --chown=modelgate:modelgate \
     /src/modelgate-mock/target/modelgate-mock-*.jar /app/app.jar

ENV SERVER_PORT=9001
EXPOSE 9001

# The mock exposes no actuator, so probe the HTTP connector itself. Without -f,
# curl still exits 0 on a 404, which is fine: reaching the port is the point.
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=6 \
  CMD curl -sS -o /dev/null --max-time 2 "http://127.0.0.1:${SERVER_PORT}/" || exit 1

USER modelgate
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
