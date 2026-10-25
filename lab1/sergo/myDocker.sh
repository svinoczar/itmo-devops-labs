unshare --pid --net --mount --uts --ipc --user --map-root-user --fork --mount-proc sleep infinity & MYDOCKER_PID=$!
sudo nsenter --target "$MYDOCKER_PID" --uts --net bash -c 'hostname mycontainer && ip link set lo up'


CGROUP_API="/sys/fs/cgroup/mydocker"
sudo mkdir -v "$CGROUP_API"

echo "$MYDOCKER_PID" | sudo tee "$CGROUP_API"/cgroup.procs

echo 20M | sudo tee "$CGROUP_API"/memory.max
echo "90000 100000" | sudo tee "$CGROUP_API"/cpu.max
echo 15 | sudo tee "$CGROUP_API"/pids.max

echo 0 | sudo tee "$CGROUP_API"/memory.swap.max

sudo nsenter --target "$MYDOCKER_PID" --all /home/sergo/docker_labs/itmo-devops-labs/lab_1/service/api