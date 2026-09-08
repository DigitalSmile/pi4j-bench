#!/bin/bash

# GPIO mock for the **linuxfs** lane. Same chip layout as the gpiod mock (identical
# ngpios/hog → identical dynamically-assigned sysfs base, so pin numbers line up),
# but with the simulated IRQ DISABLED (disable_sim_irq=1).
#
# Why: Pi4J 3.0.2's LinuxFsDigitalOutput sets direction=OUT then writes edge=both
# whenever a sysfs 'edge' file exists; gpiolib forbids locking an OUTPUT line as an
# IRQ (gpiochip_lock_as_irq -> -EIO). No IRQ ⇒ no 'edge' file ⇒ Pi4J skips that write.
# (The gpiod lane needs the IRQ, so it uses gpio-setup.sh with IRQ enabled.)
#
# We also pre-export every line as root and make the sysfs attributes world-writable,
# so the non-root JMH JVM can write direction/value without racing udev on the
# deprecated /sys/class/gpio permission model.
NGPIOS="${GPIO_MOCK_NGPIOS:-9,1}"
# Only the primary chip's label contains "mock" — both the pre-export loop below and
# the benchmark's base discovery target it (the 9-line chip), not the 1-line aux chip.
LABELS="${GPIO_MOCK_LABELS:-linuxfs-mock,inaccessible}"
HOGS="${GPIO_MOCK_HOGS:-2,-1}"
DEBUG="${GPIO_MOCK_DEBUG:-0}"

/bin/bash  ../native/gpio/build.sh

# Idempotent reload — fresh JVM here, so a prior trial's held fd is gone and rmmod works.
rmmod gpio_mock 2>/dev/null || true
for c in /dev/gpiochip*; do [ -L "$c" ] && rm -f "$c"; done

if ! insmod gpio-mock.ko ngpios="$NGPIOS" labels="$LABELS" hog_lines="$HOGS" debug="$DEBUG" disable_sim_irq=1; then
	if lsmod | grep -q '^gpio_mock'; then
		echo "gpio_mock already loaded and not removable; reusing it" >&2
	else
		echo "insmod gpio-mock.ko (linuxfs) failed" >&2
		exit 1
	fi
fi

# Pre-export each line of our mock chip(s) and make the sysfs attributes writable by
# everyone (settle first so udev can't revert us — there's no gpio udev rule anyway).
#
# Export failures are EXPECTED for lines the kernel holds (hog_lines keeps one busy on
# purpose), so they must not abort setup — but they are reported rather than swallowed.
# The benchmark reads the same exported/not-exported state to pick its line, so this
# summary is what explains an 'offset N is not exported' failure later.
udevadm settle 2>/dev/null || true
prepared=""
for gc in /sys/class/gpio/gpiochip*; do
	[ -r "$gc/label" ] || continue
	label="$(cat "$gc/label")"
	case "$label" in *mock*) ;; *) continue ;; esac
	base="$(cat "$gc/base" 2>/dev/null)" || continue
	ngpio="$(cat "$gc/ngpio" 2>/dev/null)" || continue
	ok=""
	busy=""
	off=0
	while [ "$off" -lt "$ngpio" ]; do
		pin=$((base + off))
		echo "$pin" > /sys/class/gpio/export 2>/dev/null || true
		if [ -d "/sys/class/gpio/gpio$pin" ]; then
			chmod -R a+rw "/sys/class/gpio/gpio$pin" 2>/dev/null || true
			ok="$ok $off"
		else
			busy="$busy $off"
		fi
		off=$((off + 1))
	done
	echo "gpio-linuxfs-setup: '$label' base=$base ngpio=$ngpio exported offsets:${ok:- none}${busy:+, kernel-held (not exported):$busy}"
	prepared="$prepared $label"
done
chmod a+rw /sys/class/gpio/export /sys/class/gpio/unexport 2>/dev/null || true

if [ -z "$prepared" ]; then
	echo "gpio-linuxfs-setup: no mock gpiochip under /sys/class/gpio — the module did not register one; see dmesg" >&2
	exit 1
fi

sleep 0.3
