FROM python:3.13-slim

RUN groupadd --system batch \
    && useradd --system --gid batch --no-create-home batch

WORKDIR /app
COPY --chown=batch:batch main.py ./main.py

USER batch
EXPOSE 8082

ENTRYPOINT ["python", "/app/main.py"]
