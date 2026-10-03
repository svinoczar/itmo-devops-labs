import os
import time
import logging
import psycopg
from pythonjsonlogger import jsonlogger


handler = logging.StreamHandler()
handler.setFormatter(jsonlogger.JsonFormatter(
    "%(asctime)s %(levelname)s %(message)s %(name)s",
    rename_fields={"asctime": "time", "levelname": "level"}
))
logging.basicConfig(level=logging.INFO, handlers=[handler])

logger = logging.getLogger("worker")

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = int(os.getenv("DB_PORT", "5432"))
DB_NAME = os.getenv("DB_NAME", "shop")
DB_USER = os.getenv("DB_USER", "shop")
DB_PASSWORD = os.getenv("DB_PASSWORD", "shop")
POLL_INTERVAL = int(os.getenv("POLL_INTERVAL", "5"))


def get_db():
    return psycopg.connect(
        host=DB_HOST, 
        port=DB_PORT, 
        dbname=DB_NAME, 
        user=DB_USER, 
        password=DB_PASSWORD
    )

def process_one():
    with get_db() as conn:
        with conn.cursor() as cur:
            cur.execute("""
                UPDATE orders
                SET status='processed', processed_at=NOW()
                WHERE id = (
                    SELECT id FROM orders
                    WHERE status='new'
                    ORDER BY id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, item
            """)
            row = cur.fetchone()
        conn.commit()                     

    if row is not None:                    
        order_id, item = row[0], row[1]
        logger.info("order processed", extra={"order_id": order_id, "item": item})
        return True
    return False

def main():
    logger.info("worker started", extra={"poll_interval": POLL_INTERVAL})
    while True:
        try:
            process_one()
        except Exception as e:
            logger.error("worker error", extra={"error": str(e)})
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()