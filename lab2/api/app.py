import os
import time
from flask import Flask, request, Response
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST

app = Flask(__name__)

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


@app.route('/health')
def health():
    return 'ok'


@app.route('/fail')
def fail():
    return 'ok'


@app.route('/slow')
def slow():
    return 'ok'


@app.route('/load')
def load():
    return 'ok'


@app.route('/metrics')
def metrics():
    return Response(generate_latest(), mimetype=CONTENT_TYPE_LATEST)


if __name__ == '__main__':
    port = int(os.getenv('PORT', 5000))
    app.run(host='0.0.0.0', port=port)
