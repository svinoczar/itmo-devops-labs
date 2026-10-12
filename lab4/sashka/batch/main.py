import json
import multiprocessing
import os
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def integer_env(name: str, default: int, minimum: int) -> int:
    raw_value = os.getenv(name, str(default))
    try:
        value = int(raw_value)
    except ValueError as error:
        raise SystemExit(f"{name} must be an integer, got {raw_value!r}") from error

    if value < minimum:
        raise SystemExit(f"{name} must be >= {minimum}, got {value}")
    return value


def float_env(name: str, default: float) -> float:
    raw_value = os.getenv(name, str(default))
    try:
        value = float(raw_value)
    except ValueError as error:
        raise SystemExit(f"{name} must be a number, got {raw_value!r}") from error

    if not 0 < value <= 1:
        raise SystemExit(f"{name} must be in the interval (0, 1], got {value}")
    return value


def burn_cpu(stop_event: multiprocessing.Event, duty_cycle: float) -> None:
    period_seconds = 0.1
    busy_seconds = period_seconds * duty_cycle
    idle_seconds = period_seconds - busy_seconds

    while not stop_event.is_set():
        deadline = time.perf_counter() + busy_seconds
        value = 1
        while time.perf_counter() < deadline:
            value = (value * 1103515245 + 12345) & 0x7FFFFFFF

        if idle_seconds > 0:
            stop_event.wait(idle_seconds)


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path != "/health":
            self.send_error(404)
            return

        body = json.dumps({"status": "ok"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return


def main() -> None:
    memory_mb = integer_env("MEMORY_MB", 64, 1)
    cpu_workers = integer_env("CPU_WORKERS", 1, 1)
    duty_cycle = float_env("CPU_DUTY_CYCLE", 1.0)
    port = integer_env("PORT", 8082, 1)

    memory = bytearray(memory_mb * 1024 * 1024)
    for offset in range(0, len(memory), 4096):
        memory[offset] = 1

    stop_event = multiprocessing.Event()
    processes = [
        multiprocessing.Process(target=burn_cpu, args=(stop_event, duty_cycle))
        for _ in range(cpu_workers)
    ]
    for process in processes:
        process.start()

    server = ThreadingHTTPServer(("0.0.0.0", port), HealthHandler)

    def shutdown(signum: int, frame: object) -> None:
        stop_event.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    print(
        json.dumps(
            {
                "message": "batch load started",
                "memory_mb": memory_mb,
                "cpu_workers": cpu_workers,
                "cpu_duty_cycle": duty_cycle,
                "port": port,
            }
        ),
        flush=True,
    )

    try:
        server.serve_forever()
    finally:
        stop_event.set()
        server.server_close()
        for process in processes:
            process.join(timeout=5)
            if process.is_alive():
                process.terminate()
                process.join()


if __name__ == "__main__":
    main()
