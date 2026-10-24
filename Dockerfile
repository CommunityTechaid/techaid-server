# NOTE: This is the production and uat version. Please use Dockerfile.dev for local development testing
FROM gradle:9.8.0-jdk17 AS builder
USER root
ARG GIT_COMMIT=unknown
COPY ./src /app/src
COPY ./build.gradle settings.gradle .editorconfig /app/
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
RUN apk add --no-cache --update \
    openssl \
    curl \
    bash \
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