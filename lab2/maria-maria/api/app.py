import os
import time
from flask import Flask, request, Response
import random
import httpx
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST

import json
import logging
import sys

from datetime import datetime, timezone
from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.flask import FlaskInstrumentor


#провайдер
resource = Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "api")})
provider = TracerProvider(resource=resource)
otlp_endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317")
provider.add_span_processor(
    BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True))
)
trace.set_tracer_provider(provider)
tracer = trace.get_tracer("api")


#json-логгеры
class JsonFormatter(logging.Formatter):
    def format(self, record):
        span = trace.get_current_span()
        ctx = span.get_span_context() if span else None
        trace_id = format(ctx.trace_id, "032x") if ctx and ctx.trace_id else ""
        payload = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "level": record.levelname,
            "msg": record.getMessage(),
            "logger": record.name,
            "trace_id": trace_id,
        }
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


handler = logging.StreamHandler(sys.stdout)
handler.setFormatter(JsonFormatter())
log = logging.getLogger("api")
log.setLevel(logging.INFO)
log.handlers = [handler]
log.propagate = False
logging.getLogger('werkzeug').setLevel(logging.ERROR) #отключаем access-логи


app = Flask(__name__)
FlaskInstrumentor().instrument_app(app)


REQUESTS = Counter(
    'http_requests_total',
    'Total HTTP requests',
    ['method', 'path', 'status'],
)

ERRORS = Counter(
    'http_request_errors_total',
    'Total HTTP 5xx responses',
    ['method', 'path'],
)

LATENCY = Histogram(
    'http_request_duration_seconds',
    'HTTP request latency',
    ['method', 'path'],
)


#хуки Flask
@app.before_request
def _start_timer():
    request._start_time = time.perf_counter()


@app.after_request
def _observe(response):
    elapsed = time.perf_counter() - getattr(request, '_start_time', time.perf_counter())
    LATENCY.labels(request.method, request.path).observe(elapsed)
    REQUESTS.labels(request.method, request.path, str(response.status_code)).inc()
    if response.status_code >= 500:
        ERRORS.labels(request.method, request.path).inc()
    return response

#роуты
@app.route('/health')
def health():
    log.info("health check")
    return 'ok'


@app.route('/fail')
def fail():
    span = trace.get_current_span()
    span.set_status(trace.Status(trace.StatusCode.ERROR, "simulated failure"))
    span.set_attribute("error", True)
    log.error("simulated failure on /fail")
    return Response('simulated failure', status=500)


@app.route('/slow')
def slow():
    num = random.uniform(1.0, 3.0)
    with tracer.start_as_current_span("slow-op") as sp:
        sp.set_attribute("num.seconds", num)
        log.info(f"slow op start num={num:.2f}")
        time.sleep(num)
        log.info("slow op done")
    return f'slept {num:.2f}'


SELF_URL = os.getenv('SELF_URL', 'http://localhost:5000')

@app.route('/load')
def load():
    count = int(request.args.get('count', 20))
    paths = ['/health', '/fail', '/slow']
    for i in range(count):
        path = paths[i % 3]
        try:
            httpx.get(f'{SELF_URL}{path}', timeout=10.0)
        except Exception:
            pass
    log.info(f"load fired {count} requests")
    return f'fired {count} requests'


@app.route('/metrics')
def metrics():
    return Response(generate_latest(), mimetype=CONTENT_TYPE_LATEST)


if __name__ == '__main__':
    port = int(os.getenv('PORT', 5000))
    app.run(host='0.0.0.0', port=port)
