FROM gradle:6.0.1-jdk11  as builder
USER root
COPY ./src /app/src
COPY ./build.gradle settings.gradle /app/
WORKDIR /app
#generate wrapper
RUN gradle wrapper --gradle-version 6.0.1


# FROM adoptopenjdk/openjdk11:alpine-jre
# COPY ./ /app
# ENV GRADLE_VERSION 6.0.1
# RUN apk add --no-cache --update \
#     openssl \
#     curl \
#     bash \
#     tini \
#     wget \
#     tzdata
# ENV TZ=UTC
# RUN cp /usr/share/zoneinfo/UTC /etc/localtime
# #install gradle 
# RUN curl https://downloads.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip > gradle.zip; \
#     unzip gradle.zip; \
#     rm gradle.zip; \
#     apk update && apk add --no-cache libstdc++ && rm -rf /var/cache/apk/*

# ENV PATH "$PATH:/opt/apache-maven-3.6.3/bin:/gradle-${GRADLE_VERSION}/bin/"
# WORKDIR /app

# RUN chmod +x start.sh
# #generate gradle wrapper

# # Fetch project dependencies
# RUN ./gradlew getDeps
# # script which watches source file changes in background and executes bootRun
# CMD ["sh", "start.sh"]
# # ENTRYPOINT [ "/sbin/tini", "--"]
# # CMD ["java", "-jar", "/app/app.jar"]


FROM eclipse-temurin:11

COPY ./ /app

COPY --from=builder /app/gradlew /app/gradlew

WORKDIR /app

# Fetch project dependencies
RUN chmod +x start.sh && ./gradlew getDeps

# script which watches source file changes in background and executes bootRun
CMD ["tail", "-f", "/dev/null"]