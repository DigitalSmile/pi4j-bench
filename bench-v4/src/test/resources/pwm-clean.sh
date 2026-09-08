#!/bin/sh

# Best-effort unload: a module that is already gone is not an error, so a teardown hiccup
# never fails an otherwise good benchmark run. pwm-setup.sh re-checks and reloads as needed.
if [ -d /sys/bus/platform/drivers/pwm-mock ]; then
	rmmod pwm_mock || echo "pwm-clean: rmmod pwm_mock failed (still in use?) — leaving it loaded" >&2
fi
exit 0
