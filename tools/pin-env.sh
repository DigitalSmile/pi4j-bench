#!/usr/bin/env bash
# Quiet the machine before measuring (plan §5.2). Run with sudo.
set -euo pipefail

# TODO(Phase 0): governor=performance, disable turbo/boost, verify isolcpus=2,3
#   nohz_full=2,3 on the kernel cmdline, AC power, log hwmon temperature.
echo "pin-env: TODO — governor/turbo/isolcpus checks"
