#!/bin/sh

# Remove any fixed-number symlinks created by gpio-setup.sh. Real gpiochip nodes
# are character devices, never symlinks, so this only touches what we added.
for chip in /dev/gpiochip*; do
	[ -L "$chip" ] && rm -f "$chip"
done

rmmod gpio_mock

# Revert the debugfs-root traversal bit that gpio-setup.sh opened for the latency JVM
# (the gpio-mock subtree itself is gone with the module). Best-effort.
chmod 700 /sys/kernel/debug 2>/dev/null || true
