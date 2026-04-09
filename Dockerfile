# NOTE: This is the production and uat version. Please use Dockerfile.dev for local development testing
FROM gradle:8.6-jdk17 as builder
USER root
COPY ./src /app/src
COPY ./build.gradle settings.gradle /app/
RUN gradle -p /app clean build -x test

FROM eclipse-temurin:17-jre-alpine
COPY --from=builder /app/build/libs/*.jar /app/app.jar
RUN apk add --no-cache --update \
    openssl \
    curl \
    bash \
    tini \
    wget \
    tzdata

# Download Application Insights Java agent
# Agent version: https://github.com/microsoft/ApplicationInsights-Java/releases
ARG AI_AGENT_VERSION=3.5.4
RUN wget -q "https://github.com/microsoft/ApplicationInsights-Java/releases/download/${AI_AGENT_VERSION}/applicationinsights-agent-${AI_AGENT_VERSION}.jar" \
    -O /app/applicationinsights-agent.jar

ENV TZ=UTC
RUN cp /usr/share/zoneinfo/UTC /etc/localtime
WORKDIR /app
#COPY ./CHECKS /app
COPY ./Procfile /app
COPY ./applicationinsights.json /app/
# COPY ./DOKKU_SCALE /app
ENTRYPOINT [ "/sbin/tini", "--"]
CMD ["java", "-javaagent:/app/applicationinsights-agent.jar", "-jar", "/app/app.jar"]