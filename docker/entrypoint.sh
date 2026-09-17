#!/bin/sh
#
# ModelGate entrypoint. One script serves both the gateway and the mock images:
# the jar is always /app/app.jar and everything differences comes from the env.
#
#   JAVA_OPTS   JVM flags            (set per service in docker-compose.yml)
#   SERVER_PORT the Spring Boot port (also read by the image HEALTHCHECK)
#   args        appended after -jar  -> Spring Boot CLI args keep working:
#                 docker run ... --modelgate.quota.backend=memory
#
# JAVA_OPTS is intentionally unquoted: it carries a space-separated flag list in
# a single environment variable, so word splitting is exactly what we want here.
set -eu

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
