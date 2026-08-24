Block E — FFM API deep-dive benchmarks
E1. Downcall option ladder — the raw cost of the bridge

Question: what does a native call cost by itself, with the kernel and Pi4J out of the picture?

Method: benchmark a trivial target — getpid() from libc and a no-op function from a tiny hand-built .so — through five call paths: (a) plain FFM downcall, (b) Linker.Option.critical(false), (c) critical(true) with a heap MemorySegment passed as an argument, (d) the same function via JNI, (e) via JNA. JMH, Mode.AverageTime + SampleTime, ns/op, 3 forks.

Metrics: ns/op per rung of the ladder; the deltas isolate the price of thread-state transitions and of heap-access pinning.

Expected / slide tie-in: plain downcall ≈ JNI, critical() measurably cheaper, JNA clearly behind — the exact numbers behind the "cost of a downcall" card on the execution-time slide.

E4. Virtual threads + blocking downcalls — carrier pinning

Question: what happens when Loom meets FFM? A blocking downcall is a native frame, so it pins the carrier thread — how badly does that hurt scalability?

Method: N tasks each performing a blocking native call (poll() on a GPIO line-request fd from the mock driver, or plain nanosleep) with N = 100 / 1 000 / 10 000, run twice: on virtual threads (default scheduler, jdk.virtualThreadScheduler.parallelism left stock) vs on a fixed platform-thread pool sized to core count. Record aggregate throughput and per-task latency percentiles; capture jdk.VirtualThreadPinned JFR events to prove the mechanism.

Metrics: throughput scaling curve vs N, p99 latency, count/duration of pinning events.

Expected / slide tie-in: virtual threads collapse to carrier-count parallelism on blocking downcalls while platform threads degrade gracefully — a practical warning nobody else shows: "blocking native I/O from virtual threads — handle with care." Fresh, Java-25-native content.

E5. Time-to-safepoint with a native call in flight

Question: critical() speeds up the call — but what does it do to everyone else? A JVM cannot reach a safepoint while certain native frames are running.

Method: one thread loops a deliberately slow downcall (a native busy-spin of 1 µs / 100 µs / 10 ms), in two variants: regular downcall vs Linker.Option.critical(). A second thread continuously triggers safepoint operations (e.g. System.gc() or Thread.getAllStackTraces()). Measure time-to-safepoint via -Xlog:safepoint parsing and JFR safepoint events; also record the p99.9 latency of an innocent bystander thread doing plain Java work.

Metrics: TTSP distribution per call-duration × option; bystander-thread latency tail.

Expected / slide tie-in: with critical() long native calls stretch TTSP and stall the whole JVM — the honest flip side of the recipe pill: "critical() buys nanoseconds on the call and can cost milliseconds JVM-wide if the call is slow." Directly feeds the guarantees/jitter slide.

E6. Cleaner pressure from Arena.ofAuto()

Question: issue #628 showed the RSS ramp; why doesn't GC keep up? Quantify the Cleaner-side mechanics.

Method: allocate 1 M short-lived segments in Arena.ofAuto() at a fixed rate (10k / 100k / 1M allocations per second) vs the same workload on per-call ofConfined(). Track: Cleaner queue depth over time (reflection or JFR allocation profiling on the internal cleanable list), GC pause count/duration (JFR), RSS trajectory, and time until memory is actually returned after load stops.

Metrics: queue depth vs allocation rate, reclamation lag (seconds from unreachable to free), GC pause deltas, RSS-over-time curves side by side.

Expected / slide tie-in: ofAuto() reclamation lags allocation at high rates because segments must first become unreachable, then wait for a GC cycle, then for the Cleaner thread — turning the #628 case from "a bug we fixed" into "a mechanism you now understand." Strengthens the memory-rules slide.

E7. VarHandle vs ByteBuffer vs array — bulk access inside a segment

Question: the audience will ask: "is this any faster than good old ByteBuffer?" Have the number ready.

Method: fill/read a 4 KiB and a 1 MiB region four ways: (a) MemorySegment element access via VarHandle in a manual loop, (b) MemorySegment.copyFrom / bulk copy, (c) direct ByteBuffer (putInt/getInt loop and bulk put), (d) plain byte[] as the on-heap baseline. Verify with -prof perfnorm (amd64) that C2 hoists bounds/liveness checks out of the loop; include one run with the segment accessed through a non-constant VarHandle to show what breaks the optimization.

Metrics: ns/op and GB/s per method and size; instructions-per-element from perfnorm.

Expected / slide tie-in: warmed-up VarHandle loops match direct ByteBuffer, bulk copies win at size, and a non-static final handle destroys throughput — evidence for both the "four pillars" slide and the "cache your handles" rule.

E8. Data exchange across the boundary — FFM copy vs JNI array regions

Question: for real workloads the payload matters more than the call: what does moving data cost each way?

Method: transfer heap byte[] ↔ native memory at 64 B / 4 KiB / 1 MiB via: (a) MemorySegment.copy (heap segment view ↔ native segment), (b) JNI GetByteArrayRegion / SetByteArrayRegion in a helper .so, (c) JNI GetPrimitiveArrayCritical (the fast-but-dangerous baseline), (d) JNA Memory.read/write as the convenience-tax datapoint. Both directions, JMH throughput mode, GB/s.

Metrics: GB/s per method × size × direction; crossover points where bulk copy amortizes the call overhead.

Expected / slide tie-in: FFM bulk copy rides Unsafe.copyMemory-class intrinsics and should match or beat Get/SetByteArrayRegion while staying safe; JNA trails badly. This is the natural continuation of the SPI 1 B → 4 KiB sweep: same story, one level deeper — "the bridge is free once you move data in blocks."

E-GC. Hot-path FFM calls under allocation pressure

Question: does an FFM downcall itself get slower when the application is actively allocating and deallocating objects — and is FFM any more (or less) sensitive to this than JNI/JNA?

Rationale: the Block C latency harness measures the full "kernel event → listener" pipeline under GC pressure, so a fatter tail there could come from the poll/scheduler/event machinery rather than from the call itself. This benchmark isolates the call: same hot-path methods as Block A, but with a controlled allocation storm running in the same JVM. Mechanisms it would expose: safepoint checks on call boundaries, interaction of thread-state transitions with GC barriers, cache/memory-bandwidth pollution by the allocator, and the per-call arena allocating on the hot path.

Method: reuse the Block A JMH benchmarks (GPIO read/write, I2C 1-byte register) with one added dimension — background allocation rate as @Param({"0", "200", "1000"}) MB/s, produced by dedicated allocator threads creating short-lived garbage (started in @Setup, or modeled with JMH @Group: one measured thread + N allocator threads). Fixed heap (-Xms=-Xmx), run the full matrix on both G1 and ZGC. Mode must be SampleTime: average-time mode would smear the pauses into the mean, while the interesting signal lives entirely in the p99/p99.9 tail. Run the identical matrix in bench-v3 (JNI/JNA path) — the deliverable is a relative statement, not an absolute one.

Metrics: p50 / p99 / p99.9 ns/op per {bridge × allocation rate × GC}; delta of each percentile vs the zero-pressure baseline.

Expected / slide tie-in: median essentially unmoved; the tail grows with pressure — but by a similar factor across FFM, JNI and JNA, since all of them cross the same thread-state and safepoint machinery. That "boring" result is exactly the point: it lets the talk state, with data, that FFM adds no special GC-sensitivity to the hot path — the tail belongs to the collector, not to the bridge. Feeds the execution-time slide ("sources of latency") and preempts the inevitable audience question.