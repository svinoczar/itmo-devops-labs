FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode package -DskipTests
ARG OTEL_JAVAAGENT_VERSION=2.20.1
RUN curl --fail --silent --show-error --location \
    "https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent-${OTEL_JAVAAGENT_VERSION}.jar" \
    --output /build/target/opentelemetry-javaagent.jar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system api \
    && useradd --system --gid api --no-create-home api

WORKDIR /app
COPY --from=build /build/target/api-1.0.0.jar ./api.jar
COPY --from=build /build/target/opentelemetry-javaagent.jar ./opentelemetry-javaagent.jar
COPY --chown=api:api entrypoint.sh ./entrypoint.sh
RUN chmod 0555 ./entrypoint.sh

USER api
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=2s --start-period=20s --retries=3 \
    CMD ["curl", "--fail", "--silent", "http://127.0.0.1:8080/health"]

ENTRYPOINT ["/app/entrypoint.sh"]
