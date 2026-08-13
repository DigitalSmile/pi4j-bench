#!/bin/sh

# Best-effort cleanup for the linuxfs GPIO mock — must NOT fail the trial.
# (linuxfs may keep a /sys/class/gpio value fd open for the life of the JVM, so an
# in-trial rmmod can report "in use"; that's fine — the next trial's setup reloads
# in a fresh JVM where the fd is already gone.)

# Unexport what we pre-exported (gpioN dirs only; gpiochipN has a 'c', not a digit).
for d in /sys/class/gpio/gpio[0-9]*; do
	[ -d "$d" ] || continue
	echo "${d##*/gpio}" > /sys/class/gpio/unexport 2>/dev/null || true
done

for chip in /dev/gpiochip*; do
	[ -L "$chip" ] && rm -f "$chip"
done

rmmod gpio_mock 2>/dev/null || true
exit 0
