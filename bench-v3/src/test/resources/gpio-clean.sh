#!/bin/sh

sleep 2

# Remove any fixed-number symlinks we created (real gpiochip nodes are char devices).
for chip in /dev/gpiochip*; do
	[ -L "$chip" ] && rm -f "$chip"
done

rmmod gpio_mock

sleep 2