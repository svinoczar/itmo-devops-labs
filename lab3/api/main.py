import os
import logging
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel
import psycopg
from pythonjsonlogger import jsonlogger
from prometheus_fastapi_instrumentator import Instrumentator

#логирование для мониторинга
handler = logging.StreamHandler()
handler.setFormatter(jsonlogger.JsonFormatter(
    "%(asctime)s %(levelname)s %(message)s %(name)s",
    rename_fields={"asctime": "time", "levelname": "level"}
))
logging.basicConfig(level=logging.INFO, handlers=[handler])

logger = logging.getLogger("api")      

#Конфиг из env
DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = int(os.getenv("DB_PORT", "5432"))
DB_NAME = os.getenv("DB_NAME", "shop")
DB_USER = os.getenv("DB_USER", "shop")
DB_PASSWORD = os.getenv("DB_PASSWORD", "shop")
HEALTH_FAIL = os.getenv("HEALTH_FAIL", "false").lower() == "true"

app = FastAPI()
Instrumentator().instrument(app).expose(app)

#Pydantic для POST /order - отвечает 422 на неккоректные данные пользователя
class OrderIn(BaseModel):
    item: str


def get_db():
    return psycopg.connect(
        host=DB_HOST, 
        port=DB_PORT, 
        dbname=DB_NAME, 
        user=DB_USER, 
        password=DB_PASSWORD
    )

def init_db():
    with get_db() as conn:
        with conn.cursor() as cur:
            cur.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id SERIAL PRIMARY KEY,
                    item TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'new',
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    processed_at TIMESTAMPTZ
                )
            """)
        conn.commit()

@app.on_event("startup")
def on_startup():
    init_db()

@app.get("/health")
def health():
    if HEALTH_FAIL:
        return JSONResponse(status_code=503, content={"status": "fail"})
    return {"status": "ok"}

@app.post("/order")
def create_order(order: OrderIn):
    with get_db() as conn:
        with conn.cursor() as cur:
            cur.execute("INSERT INTO orders (item) VALUES (%s) RETURNING id", (order.item,))
            row = cur.fetchone()
        conn.commit()
    
    order_id = row[0]
    logger.info("order created", extra={"order_id": order_id, "item": order.item})
    return {"id": order_id, "status": "created"}

@app.get("/orders")
def list_orders():
    with get_db() as conn:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT id, item, status, created_at
                FROM orders
                ORDER BY id DESC
                LIMIT 100
            """)
            rows = cur.fetchall()

    return [
        {
            "id": r[0],
            "item": r[1],
            "status": r[2],
            "created_at": r[3].isoformat() if r[3] else None,
        }
        for r in rows
    ]
