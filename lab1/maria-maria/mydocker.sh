#!/bin/bash
if [ "$EUID" -ne 0 ]; then
    echo "Run with sudo"
    exit 1
fi

echo "mydocker starting..."

#cgroup
CGROUP=/sys/fs/cgroup/mydocker

if [ -d "$CGROUP" ]; then
    rmdir "$CGROUP"
fi

mkdir "$CGROUP"

#limits
echo "50M" > "$CGROUP/memory.max"
echo "50000 100000" > "$CGROUP/cpu.max"
echo "50" > "$CGROUP/pids.max"

echo "[mydocker] cgroup created, limits set"

#namespace
unshare --pid --fork --mount-proc --uts --ipc --mount --net bash -c '
    hostname mydocker-container
    ip link set lo up
    echo $$ > /sys/fs/cgroup/mydocker/cgroup.procs
    cd /home/user/itmo-devops/lab1
    source .venv/bin/activate
    python main.py &
    SERVER_PID=$!
    trap "kill $SERVER_PID 2>/dev/null; exit" INT TERM
    sleep 2
    echo "[mydocker] testing /health:"
    curl -s http://localhost:5000/health || echo "FAIL"
    echo ""
    echo "[mydocker] server PID (inside): $SERVER_PID"
    wait $SERVER_PID
'