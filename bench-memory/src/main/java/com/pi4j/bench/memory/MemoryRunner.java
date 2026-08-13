package com.pi4j.bench.memory;

import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.RssSampler;

/// Block D — RSS/NMT trajectory over 1 M I2C reads.
/// `--mode=fixed` (per-call ofConfined) vs `--mode=issue628` (pre-fix ofAuto ramp).
/// TODO(Phase 4): drive real Pi4J I2C reads + `jcmd VM.native_memory summary` diff.
public final class MemoryRunner {

    private static final int TOTAL_OPS = 1_000_000;
    private static final int SNAPSHOT_EVERY = 50_000;

    void main(String[] args) {
        var mode = parseMode(args);
        System.out.println("bench-memory: " + EnvManifest.capture());
        System.out.println("mode=" + mode + " totalOps=" + TOTAL_OPS);

        for (int op = 0; op <= TOTAL_OPS; op++) {
            if (op % SNAPSHOT_EVERY == 0) {
                System.out.printf("op=%,d VmRSS=%d kB%n", op, RssSampler.vmRssKib());
            }
            // TODO: real I2C read here; mode chooses ofConfined vs ofAuto arena.
        }
    }

    private static Mode parseMode(String[] args) {
        for (var a : args) {
            if (a.startsWith("--mode=")) {
                return Mode.valueOf(a.substring("--mode=".length()).toUpperCase());
            }
        }
        return Mode.FIXED;
    }

    enum Mode { FIXED, ISSUE628 }
}
