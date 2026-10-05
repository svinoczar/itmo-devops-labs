# --BUILD STAGE 67--
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

# копируем помник и скачиваем в образ локальный мавен-репозиторий с нужными зависимостями
COPY pom.xml .
RUN mvn -B dependency:go-offline

# собираем jarник и тд
COPY src ./src
RUN mvn -B package -DskipTests
ARG OTEL_JAVAAGENT_VERSION=2.20.1
RUN curl --fail --silent --show-error --location \
    "https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent-${OTEL_JAVAAGENT_VERSION}.jar" \
    --output /build/target/opentelemetry-javaagent.jar


# --RUNTIME STAGE 67--
FROM eclipse-temurin:21-jre-jammy

# создаем нового пользователя чтобы все запускать не из под рута
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system worker \
    && useradd --system --gid worker --no-create-home worker

WORKDIR /app
COPY --from=build /build/target/shop-worker-1.0.0.jar ./worker.jar
COPY --from=build /build/target/opentelemetry-javaagent.jar ./opentelemetry-javaagent.jar
COPY --chown=worker:worker entrypoint.sh ./entrypoint.sh
RUN chmod 0555 ./entrypoint.sh

USER worker
EXPOSE 8081

HEALTHCHECK --interval=10s --timeout=2s --start-period=20s --retries=3 CMD [ "curl",  "--fail", "--silent", "http://127.0.0.1:8081/health"]

ENTRYPOINT [ "/app/entrypoint.sh" ]
    