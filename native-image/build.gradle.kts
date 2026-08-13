// GraalVM native-image lane for the V4 latency runner (Block C — flat warmup).
// Wired in Phase 5; placeholder keeps the module in the graph.
plugins {
    id("pi4j-bench.java")
}

// TODO(Phase 5): apply org.graalvm.buildtools.native, register reachability metadata
// for FFM downcall stubs, build a native image of com.pi4j.bench.latency.LatencyRunner.
