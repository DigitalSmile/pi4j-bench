#!/bin/bash

sleep 2

# Mock GPIO chip layout is customizable through environment variables so tests
# can shape the chips without editing the driver. Each is a comma separated list
# where the number of entries equals the number of chips to create:
#   GPIO_MOCK_NGPIOS  - lines per chip            (default "8,1")
#   GPIO_MOCK_LABELS  - label per chip            (default "accessible,inaccessible")
#   GPIO_MOCK_HOGS    - occupied line per chip,   (default "2,-1", -1 means none)
#   GPIO_MOCK_NUMBERS - desired /dev/gpiochip<N>  (optional, e.g. "0,1,99")
# Example: GPIO_MOCK_NGPIOS=16,4 GPIO_MOCK_LABELS=bankA,bankB GPIO_MOCK_HOGS=-1,-1 ./gpio-setup.sh
NGPIOS="${GPIO_MOCK_NGPIOS:-9,1}"
# bench-v3: first chip label contains "pinctrl" so Pi4J 3.0.2 GpioDContext's default
# chip finder (chip.getLabel().contains("pinctrl")) selects it on the mock lane.
LABELS="${GPIO_MOCK_LABELS:-pinctrl-mock,inaccessible}"
HOGS="${GPIO_MOCK_HOGS:-2,-1}"
NUMBERS="${GPIO_MOCK_NUMBERS:-97,98}"
# Set to 1 to enable verbose debug logging from the mock driver (visible in dmesg)
DEBUG="${GPIO_MOCK_DEBUG:-1}"

/bin/bash  ../native/gpio/build.sh

# Reload cleanly. The previous trial's clean couldn't rmmod (its JVM held the chip fd
# until exit), so a stale module is usually still loaded here — but THIS is a fresh
# JVM, so that fd is gone and rmmod now succeeds. Drop any stale module + symlinks,
# then insmod fresh. Tolerate the rare case where the module can't be removed but is
# already present (reuse it) so the run still proceeds.
rmmod gpio_mock 2>/dev/null || true
for c in /dev/gpiochip*; do [ -L "$c" ] && rm -f "$c"; done

if ! insmod gpio-mock.ko ngpios="$NGPIOS" labels="$LABELS" hog_lines="$HOGS" debug="$DEBUG"; then
	if lsmod | grep -q '^gpio_mock'; then
		echo "gpio_mock already loaded and not removable; reusing it" >&2
	else
		echo "insmod gpio-mock.ko failed (labels=$LABELS)" >&2
		exit 1
	fi
fi

# The kernel assigns /dev/gpiochip<N> numbers automatically (always the lowest
# free integer) - a GPIO driver cannot request a specific one the way i2c can.
# To expose a chip under a fixed number, we symlink it to the requested
# /dev/gpiochip<N>. The mock's chips are children of its platform device in sysfs
# and the kernel numbers them consecutively in registration order, which matches
# the order of the labels/ngpios/NUMBERS lists - so we map them by position,
# without needing the libgpiod 'gpiodetect' tool.
if [ -n "$NUMBERS" ]; then
	i=1
	for chip in $(ls -d /sys/devices/platform/gpio-mock/gpiochip* 2>/dev/null | sort -V); do
		real="${chip##*/}"  # gpiochip<N> as assigned by the kernel
		number=$(echo "$NUMBERS" | cut -d',' -f"$i")
		i=$((i + 1))
		[ -z "$number" ] && continue
		# only create a symlink if the chip is not already under the wanted number
		if [ "$real" != "gpiochip$number" ]; then
			ln -sf "/dev/$real" "/dev/gpiochip$number"
		fi
	done
fi

# Sleep a second to let udev rules to be applied
sleep 2