package com.pi4j.bench.latency;

import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.HdrRecorder;

/// Block C — edge event → listener latency/jitter runner.
/// Flavor (V3 gpiod / V4 ffm) is selected by the `-Pv=` source-set at build time.
/// TODO(Phase 3): debugfs toggler @200 Hz, kernel-ts delta, jHiccup + JFR wiring.
public final class LatencyRunner {

    void main(String[] args) {
        var cfg = Config.parse(args);
        System.out.println("bench-latency: " + EnvManifest.capture());
        System.out.println("config: " + cfg);

        var rec = new HdrRecorder();
        // Placeholder self-check until the real stimulus pipeline lands.
        for (int i = 0; i < cfg.warmupSamples(); i++) {
            rec.recordNanos(1_000L + i);
        }
        System.out.printf("p50=%.3fµs p99=%.3fµs p99.9=%.3fµs%n",
                rec.percentileMicros(50), rec.percentileMicros(99), rec.percentileMicros(99.9));
    }

    record Config(String gc, boolean gcPressure, int warmupSamples) {
        static Config parse(String[] args) {
            var gc = "G1";
            var gcPressure = false;
            var warmup = 10_000;
            for (var a : args) {
                switch (a) {
                    case "--zgc" -> gc = "ZGC";
                    case "--gc-pressure" -> gcPressure = true;
                    default -> {
                        if (a.startsWith("--warmup=")) {
                            warmup = Integer.parseInt(a.substring("--warmup=".length()));
                        }
                    }
                }
            }
            return new Config(gc, gcPressure, warmup);
        }
    }
}
