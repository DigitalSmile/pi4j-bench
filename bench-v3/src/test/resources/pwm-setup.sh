#!/bin/bash

# Loads the pwm-mock kernel module and PROVES it registered a chip.
#
# The previous version ended in `sleep 0.5`, so a failed insmod still exited 0 and the lane
# happily fell through to whatever pwmchip0 happened to be. On a board with its own PWM
# controller (StarFive JH7110 / Milk-V Mars) that is real hardware, and the benchmark only
# noticed several writes later with 'Invalid argument (22)' on `polarity`.
# See docs/adr/0001-pwm-chip-auto-discovery.md.

# Set to 1 to enable verbose debug logging from the mock driver (visible in dmesg)
DEBUG="${PWM_MOCK_DEBUG:-0}"
# Channels exposed by the mock chip (driver default is 3)
CHANNELS="${PWM_MOCK_CHANNELS:-3}"

DRIVER=pwm-mock

if ! /bin/bash ../native/pwm/build.sh; then
	echo "pwm-setup: failed to build $DRIVER.ko (kernel headers for $(uname -r) missing?)" >&2
	exit 1
fi

# JMH runs setup once per fork, and a previous run may have left the module in place; only
# insmod when the driver is not already registered, otherwise insmod's EEXIST looks like a
# real failure.
if [ ! -d "/sys/bus/platform/drivers/$DRIVER" ]; then
	if ! insmod pwm-mock.ko debug="$DEBUG" channels="$CHANNELS"; then
		echo "pwm-setup: insmod pwm-mock.ko failed — see dmesg" >&2
		exit 1
	fi
fi

udevadm trigger --settle

# Sleep a second to let udev rules to be applied
sleep 0.5

# Report which pwmchip the mock actually got. The index is assigned in registration order, so
# it is only 0 on boards with no PWM controller of their own; the lane discovers it from sysfs
# (bench.pwm.chip=auto). Two ways to read the owning driver: the bound-driver link, and the
# canonical device path for kernels/chips that do not expose it.
found=""
for chip in /sys/class/pwm/pwmchip*; do
	[ -e "$chip" ] || continue
	drv=$(basename "$(readlink -f "$chip/device/driver" 2>/dev/null)" 2>/dev/null)
	if [ "$drv" != "$DRIVER" ]; then
		case "$(readlink -f "$chip")" in
		*"/platform/$DRIVER."*) drv="$DRIVER" ;;
		esac
	fi
	if [ "$drv" = "$DRIVER" ]; then
		found="${chip##*/}"
		break
	fi
done

if [ -z "$found" ]; then
	echo "pwm-setup: $DRIVER is loaded but registered no pwmchip under /sys/class/pwm — see dmesg" >&2
	exit 1
fi

echo "pwm-setup: $DRIVER is $found ($CHANNELS channels)"
