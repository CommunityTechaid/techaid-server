# NOTE: This is the production and uat version. Please use Dockerfile.dev for local development testing
FROM gradle:9.8.1-jdk17 AS builder
USER root
# Dependencies go in their own layer, before the sources. The GHA layer cache (cache-to mode=max in
# ci.yml) then reuses them for every commit that leaves the build files alone, instead of
# re-downloading everything from Maven Central, which rate-limits shared runner IPs (HTTP 429).
COPY ./build.gradle settings.gradle .editorconfig /app/
RUN gradle -p /app resolveDependencies
ARG GIT_COMMIT=unknown
COPY ./src /app/src
RUN gradle -p /app clean build -x test -PgitCommit=${GIT_COMMIT}

# Download the Application Insights Java agent in a throwaway stage so the runtime image needs no
# wget (CVE-2026-58469, CVE-2026-58471, CVE-2026-58472)
# Agent version: https://github.com/microsoft/ApplicationInsights-Java/releases
# AI_AGENT_SHA256 must be updated together with AI_AGENT_VERSION
FROM alpine:3 AS agent
ARG AI_AGENT_VERSION=3.7.10
ARG AI_AGENT_SHA256=93a70c8f5d364c7e777f6c4d1b235dba91aef8448bd3fa94359f1d7f3e2eb0ec
RUN wget -q "https://github.com/microsoft/ApplicationInsights-Java/releases/download/${AI_AGENT_VERSION}/applicationinsights-agent-${AI_AGENT_VERSION}.jar" \
    -O /applicationinsights-agent.jar \
    && echo "${AI_AGENT_SHA256}  /applicationinsights-agent.jar" | sha256sum -c -

FROM eclipse-temurin:17-jre-alpine
COPY --from=builder /app/build/libs/*.jar /app/app.jar
COPY --from=agent /applicationinsights-agent.jar /app/applicationinsights-agent.jar
# Only what the container runs: tini as PID 1, tzdata for /etc/localtime below. openssl, curl
# and bash were Dokku-era leftovers that nothing invokes at runtime (checked 2026-10-24), so they
# were removed to shrink what has to be patched. `az containerapp exec` still gets busybox sh.
# `apk upgrade` pulls in Alpine security fixes for the base image's own packages, which
# otherwise wait for the next eclipse-temurin image rebuild.
RUN apk upgrade --no-cache \
    && apk add --no-cache \
    tini \
    tzdata

ENV TZ=UTC
RUN cp /usr/share/zoneinfo/UTC /etc/localtime
WORKDIR /app
#COPY ./CHECKS /app
COPY ./Procfile /app
COPY ./applicationinsights.json /app/
# COPY ./DOKKU_SCALE /app
ENTRYPOINT [ "/sbin/tini", "--"]
CMD ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-javaagent:/app/applicationinsights-agent.jar", \
  "-jar", "/app/app.jar"]