#!/usr/bin/env bash
# Quiet the machine before measuring (plan §5.2). Run with sudo. Degrades gracefully
# where a knob is absent (VMs have no cpufreq) — envCheck warns, this does what it can.
#   sudo tools/pin-env.sh            # isolate cores 2,3 (override with ISOL_CPUS=…)
set -uo pipefail
ISOL_CPUS="${ISOL_CPUS:-2,3}"

# 1. CPU governor → performance (stable clocks; the biggest single noise source).
if ls /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null 2>&1; then
	for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
		echo performance >"$g" 2>/dev/null || true
	done
	echo "pin-env: governor → performance"
else
	echo "pin-env: no cpufreq (VM/container?) — skipping governor" >&2
fi

# 2. Turbo/boost off — opportunistic clock bursts widen the latency tail.
if [[ -w /sys/devices/system/cpu/intel_pstate/no_turbo ]]; then
	echo 1 >/sys/devices/system/cpu/intel_pstate/no_turbo && echo "pin-env: intel turbo off"
elif [[ -w /sys/devices/system/cpu/cpufreq/boost ]]; then
	echo 0 >/sys/devices/system/cpu/cpufreq/boost && echo "pin-env: cpufreq boost off"
else
	echo "pin-env: no turbo/boost knob found — skipping" >&2
fi

# 3. Verify the measurement cores are isolated on the kernel cmdline (set once at boot).
if grep -qE "isolcpus=([0-9,-]*,)?${ISOL_CPUS}" /proc/cmdline; then
	echo "pin-env: isolcpus covers ${ISOL_CPUS} ✓"
else
	echo "pin-env: WARN cores ${ISOL_CPUS} not isolated — add 'isolcpus=${ISOL_CPUS} nohz_full=${ISOL_CPUS}' to the kernel cmdline once" >&2
fi

# 4. AC power — laptops/SBCs throttle hard on battery.
for ac in /sys/class/power_supply/A{C,DP}*/online; do
	[[ -r "$ac" && "$(cat "$ac" 2>/dev/null)" == "0" ]] && echo "pin-env: WARN running on battery — plug in AC" >&2
done

# 5. Log hwmon temperature — SBC runs that throttle are discarded (§5.2).
for t in /sys/class/hwmon/hwmon*/temp1_input; do
	[[ -r "$t" ]] || continue
	name="$(cat "$(dirname "$t")/name" 2>/dev/null || echo hwmon)"
	echo "pin-env: ${name} temp=$(($(cat "$t") / 1000))°C"
done
exit 0
