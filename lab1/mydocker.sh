#!/bin/bash
set -uo pipefail   # временно без -e, чтобы видеть ошибки, а не падать молча

CONTAINER_NAME="new-ultimate-innovative-russian-container-better-than-docker"
CGROUP_PATH="/sys/fs/cgroup/${CONTAINER_NAME}"
JAR_PATH="/home/czar/itmo/devops/lab1/api/target/api-1.0.0.jar"
MEM_LIMIT=104857600
CPU_QUOTA="50000 100000"
PIDS_LIMIT=25

sudo mkdir -p "${CGROUP_PATH}"
echo "${MEM_LIMIT}"  | sudo tee "${CGROUP_PATH}/memory.max"  >/dev/null
echo "${CPU_QUOTA}"  | sudo tee "${CGROUP_PATH}/cpu.max"     >/dev/null
echo "${PIDS_LIMIT}" | sudo tee "${CGROUP_PATH}/pids.max"    >/dev/null

sudo systemctl reset-failed "${CONTAINER_NAME}" 2>/dev/null || true

sudo systemd-run --unit="${CONTAINER_NAME}" \
  -p User=czar \
  -p SystemCallFilter='~uname' \
  -p SystemCallErrorNumber=EPERM \
  -- unshare -Urmpniu --fork --mount-proc sh -c \
  "ip link set lo up && capsh --drop=cap_sys_time -- -c 'java -jar ${JAR_PATH}'"

PID=""
for i in $(seq 1 20); do
  FOUND=$(pgrep -f "^java -jar ${JAR_PATH}$" | head -n1)
  if [ -n "${FOUND}" ]; then
    PID="${FOUND}"
    break
  fi
  sleep 0.5
done


echo "${PID}" | sudo tee "${CGROUP_PATH}/cgroup.procs" >/dev/null

echo "Контейнер запущен с PID ${PID}!"
echo "Юнит: ${CONTAINER_NAME}"
echo "Cgroup: ${CGROUP_PATH}"
echo ""
echo "Для остановки контейнера: sudo systemctl stop ${CONTAINER_NAME}"

# echo "===== Диагностика ====="
# echo "-- Namespaces --"
# sudo nsenter -t "${PID}" -p -m ps -ef
# echo "-- Capabilities (CapEff) --"
# sudo nsenter -t "${PID}" -p -m cat /proc/1/status | grep CapEff
# echo "-- Seccomp --"
# sudo nsenter -t "${PID}" -p -m cat /proc/1/status | grep Seccomp
# echo "-- Health check --"
# sudo nsenter -t "${PID}" -n curl -s http://localhost:8080/health
# echo ""
# echo "-- Cgroup лимиты --"
# cat "${CGROUP_PATH}/memory.max" "${CGROUP_PATH}/cpu.max" "${CGROUP_PATH}/pids.max"
# echo ""
