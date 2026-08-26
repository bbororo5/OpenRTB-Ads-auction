FROM alpine:3.20 AS otel-javaagent

ARG OTEL_JAVAAGENT_VERSION=2.29.0

RUN apk add --no-cache ca-certificates curl \
    && curl -fsSL \
        "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar" \
        -o /opentelemetry-javaagent.jar

FROM gradle:9.2.1-jdk21 AS build

ARG APP_MODULE
WORKDIR /workspace

COPY settings.gradle build.gradle ./
COPY ssp-app ./ssp-app
COPY dsp-app ./dsp-app
RUN mkdir -p system-test

RUN gradle --no-daemon ":${APP_MODULE}:installDist"

FROM eclipse-temurin:21-jre

ARG APP_MODULE
WORKDIR /app

RUN mkdir -p /otel

COPY --from=otel-javaagent /opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build "/workspace/${APP_MODULE}/build/install/${APP_MODULE}" /app
RUN ln -s "/app/bin/${APP_MODULE}" /app/bin/run-app

EXPOSE 8080 8081

ENTRYPOINT ["/app/bin/run-app"]
