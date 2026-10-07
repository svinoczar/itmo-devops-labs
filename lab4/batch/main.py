import os
import time
import logging
from pythonjsonlogger import jsonlogger

handler = logging.StreamHandler()
handler.setFormatter(jsonlogger.JsonFormatter(
    "%(asctime)s %(levelname)s %(message)s %(name)s",
    rename_fields={"asctime": "time", "levelname": "level"}
))
logging.basicConfig(level=logging.INFO, handlers=[handler])

logger = logging.getLogger("batch")


CPU_LOAD = int(os.getenv("CPU_LOAD", "100")) #есть ядро
MEM_MB = int(os.getenv("MEM_MB", "50")) #есть память
SLEEP_SEC = int(os.getenv("SLEEP_SEC", "5")) #пауза

def main():
    iteration = 0
    held_memory = None
    
    logger.info("batch started", extra={
        "cpu_load": CPU_LOAD,
        "mem_mb": MEM_MB,
        "sleep_sec": SLEEP_SEC,
    })
    
    while True:
        try:
            iteration += 1
            
            for i in range(CPU_LOAD * 500_000):
                x = i * i
            
            held_memory = bytearray(MEM_MB * 1024 * 1024)
            
            logger.info("batch tick", extra={
                "iteration": iteration,
                "cpu_load": CPU_LOAD,
                "mem_mb": MEM_MB,
            })

            time.sleep(SLEEP_SEC)
        
        except Exception as e:
            logger.error("batch error", extra={"error": str(e)})
            time.sleep(SLEEP_SEC)

if __name__ == "__main__":
    main()