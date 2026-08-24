# Pi4J V4 (FFM) vs V3 (JNI/JNA) — Benchmark Plan (rev. 2, Gradle edition)

Target: defensible, reproducible numbers for the Joker 2026 talk
**"Ни строчки C: Java 25 на железе без посредников"** — covering the four dimensions
promised on the slides: hot path, lifecycle, latency/jitter, memory.

Rev. 2 changes: Gradle build, a curated data-collection plugin stack, a **single
`benchAll` entrypoint**, and **locally built V3 natives** so the V3 lane runs on
amd64/riscv64 too (wired into the V3 benchmark tasks as a hard dependency).

Rev. 3 (implementation notes, 2026-08-13):
- **V4 lane pinned to `5.0.0-SNAPSHOT`**, not a 4.x Central release. The FFM plugin
  (`pi4j-plugin-ffm`) *is* on Central at 4.0.0/4.0.1/4.0.2, but the JMH suite this
  plan targets (per-mode I2C classes, PWM, listener round-trip; anchors in
  `result.txt`) lives on the upstream **`main`** branch heading to 5.0.0. So bench-v4
  depends on `com.pi4j:*:5.0.0-SNAPSHOT` built into **mavenLocal** via
  `tools/build-pi4j-v4.sh` (clones `github.com/Pi4J/pi4j` — a single Maven monorepo,
  `mvnw`/Maven 4 — and `install`s `pi4j-core` + `pi4j-plugin-ffm`, `-DskipTests`).
- The memory repro (#628) still uses the released **4.0.1** (pre-fix) vs the fixed
  line in a dedicated Gradle configuration — both on Central (Block D, §6.4).
- Toolchain in use: **Gradle 9.2.1 + JDK 25** (local Corretto 25, discovered via
  `org.gradle.java.installations.paths`); Kotlin-DSL build.

Rev. 5 (build + scope reality, 2026-08-14):
- **`me.champeau.jmh` and `io.morethan.jmhreport` are gone.** JMH is a plain
  annotation-processor dependency; lanes live in `src/main/java` (not `src/jmh/java`)
  and the runner forks `org.openjdk.jmh.Main`. No browsable HTML report — `plot_slides.py`
  reads the retained JMH JSON. Sections that still say "`src/jmh`", "`jmh` task",
  "`JMHTask`", or "`jmhReport`" below are historical (the illustrative Kotlin snippets in
  §4.1/§5.4 predate this and are kept only for shape).
- **`build-logic/` is deleted.** The single Java convention moved to a `subprojects {}`
  block in the root `build.gradle.kts` (JDK 25 toolchain, UTF-8, `--enable-native-access`,
  `-Xlint`); `native-v3` is excluded (native-only). There is no included build.
- **New module `bench-warmup` (Block E)** and **three warmup lanes**: `warmup-v4`
  (baseline JIT), `warmup-v4-aot` (JDK 25 AOT cache, JEP 483), `warmup-v4-leyden`
  (Leyden EA). Pure FFM `getpid` downcall — no Pi4J, no mocks — measuring FFM-stub
  time-to-steady-state. The lane count is now **ten**, not six (§4.3).
- **CI exists:** `.github/workflows/build.yml` builds the `:bench-runner:bundle` on
  `ubuntu-latest` with cross toolchains + multiarch libgpiod.

---

## 1. Goals and non-goals

**Goals**

1. Compare **Pi4J V4 `pi4j-plugin-ffm`** against **Pi4J V3 providers** (`gpiod` = JNA→JNI wrapper→libgpiod, `linuxfs` = pure-Java file I/O, `pigpio` = JNI→DMA) per interface: GPIO in/out, I2C (direct / SMBus / file), SPI, PWM.
2. Separate **bridge cost** (Java→kernel call path) from **bus physics**: same suite on mock kernel drivers *and* real peripherals.
3. Per-architecture results: **amd64** (dev machine), **arm64** (real board), **riscv64** (real board). With locally built V3 natives the *bridge* comparison runs on all three; official-artifact portability remains a headline ("out of the box, V3 runs only on ARM — V4 runs everywhere").
4. One command per machine, machine-readable output (JMH JSON + HDR histograms + CSV), auto-generated reports feeding slides 29–30.

**Non-goals** — no JVM-tuning contest (documented defaults only); no hard-real-time claims (we report jitter, we don't fight it).

---

## 2. Building blocks already in the Pi4J repo (reuse, don't reinvent)

| Asset | Path | What it gives us |
|---|---|---|
| Mock kernel drivers | `plugins/pi4j-plugin-ffm/src/test/native/{gpio,i2c,spi,pwm}/*-mock.c` + `Makefile` + `build.sh` | Real `/dev/gpiochipN`, I2C, SPI, PWM nodes backed by in-memory state; GPIO mock has **debugfs line control** (`/sys/kernel/debug/gpio-mock/<label>/lineN`) and **simulated edge IRQs** → event-latency measurements without hardware |
| Setup/teardown scripts | `src/test/resources/{gpio,i2c,pwm,spi}-{setup,clean}.sh` | `insmod`/`rmmod`; used via sudoers NOPASSWD (see `BaseSetup`) |
| JMH suite (FFM only) | `plugins/pi4j-plugin-ffm/src/test/java/com/pi4j/plugin/jmh/*PerformanceTest.java` + `result.txt` (upstream **`main`**) | 7 working per-interface benchmarks; historical anchors (GPIO read ≈ 0.31–0.41 µs/op on mocks) — ported verbatim into bench-v4, Phase 1 sanity check. **Done** (§6.1) |
| V3 native sources | pi4j **v2/v3 branch**, `libraries/pi4j-library-gpiod/src/main/native` (JNI wrapper `libpi4j-gpiod.so` around libgpiod) | Compilable with a plain local toolchain → the missing amd64/riscv64 natives (§5.4) |

---

## 3. The comparison matrix

### 3.1 Providers × interfaces

| Interface | V4 (main) | V3 (Maven Central `com.pi4j:*:3.x`) |
|---|---|---|
| GPIO output / input (+listener) | `ffm-digital-*` | `gpiod`†, `pigpio` (real ARM HW only) |
| I2C register + 32 B block | `ffm-i2c` **DIRECT** (ioctl) | `linuxfs-i2c`, `pigpio` |
| I2C | `ffm-i2c` **SMBUS** / **FILE** | `linuxfs-i2c` (closest to FILE) |
| SPI 1 B / 64 B / 4 KiB | `ffm-spi` | `linuxfs-spi`, `pigpio` |
| PWM (hardware, sysfs) | `ffm-pwm` | `linuxfs-pwm` |

† `gpiod` runs on non-ARM arches with our locally built native (§5.4).

### 3.2 Platforms × runnability (still a slide, now with a twist)

| | amd64 (mocks) | arm64 (real board) | riscv64 (real board) |
|---|---|---|---|
| V4 `ffm` | ✅ | ✅ | ✅ |
| V3 `linuxfs` (pure Java) | ✅ | ✅ | ✅ |
| V3 `gpiod`, official jar | ❌ no x86_64 lib | ✅ | ❌ no riscv64 lib |
| V3 `gpiod`, **local native build** | ✅ **built** (libgpiod v1.6.5 from source) | ✅ **cross-built** (from source) | ✅ **cross-built** (from source) |
| V3 `pigpio` | ❌ DMA/BCM-only | ✅ RPi ≤ 4 (RP1 broke it) | ❌ |

\* bench-only artifact, clearly labeled — the talk states: *official* V3 needs the
Docker pipeline per-arch; we hand-built it to make the comparison fair, which itself
demonstrates the maintenance cost V4 eliminates.

### 3.3 Dimensions (from slides 27–28)

| Block | What | Metric | Harness |
|---|---|---|---|
| **A. Hot path** | GPIO toggle/read; I2C 1 B reg + 32 B block; SPI 1 B/64 B/4 KiB; PWM duty update | ns/op + p50/p99/p99.9 | JMH (`me.champeau.jmh`) |
| **B. Lifecycle** | provider create/shutdown; `newAutoContext()` vs explicit | ms/op + single-shot dist. | JMH |
| **C. Latency & jitter** | edge event → listener; GC pressure; G1 vs ZGC; warmup curve; native image | µs percentiles, histograms | custom runner + HdrHistogram + jHiccup |
| **D. Memory** | RSS/NMT over 1 M calls (`ofConfined` vs `ofAuto`, #628); bytes/op | MB trajectory, B/op | custom runner + `jcmd`; JMH `-prof gc` |
| **E. Warmup/AOT** | FFM downcall time-to-steady-state, cold JVM: baseline JIT vs JDK 25 AOT cache (JEP 483) vs Leyden EA | µs percentiles, warmup curve | `bench-warmup` custom runner + HdrHistogram |

---

## 4. Gradle project

### 4.1 Layout

```
pi4j-bench/
├── settings.gradle.kts            # foojay toolchain resolver; includes below
├── build.gradle.kts               # thin aggregator + the single Java convention in a
│                                  #   subprojects {} block (build-logic is gone, rev.5)
├── bench-common/                  # zero pi4j deps: MockSetup, RssSampler, EnvManifest,
│                                  # HdrRecorder, result schema (JSON POJOs)
├── bench-runner/                  # the Java orchestrator: main() = benchAll (§4.3). Reads
│                                  # build/lanes/*.properties, forks one java per lane
├── bench-v4/                      # deps: com.pi4j:*:5.0.0-SNAPSHOT (mavenLocal, tools/build-pi4j-v4.sh)
│   ├── src/main/java/com/pi4j/plugin/jmh/   # ported {GPIOInput,GPIOOutput,I2CDirect,
│   │                              #   I2CSMBus,I2CFile,SPI,PWM}PerformanceTest + BaseSetup
│   │                              #   (plain JMH annotation processing — no me.champeau plugin)
│   └── src/test/{resources,native}/        # *-setup/clean.sh + mock-driver C sources
├── bench-v3/                      # deps: com.pi4j 3.x core+gpiod+linuxfs+pigpio
│   └── src/main/java/…            # mirrored class/method names
├── bench-latency/                 # Block C main(); flavors picked by -Pv=3|4 (source sets)
├── bench-memory/                  # Block D main(); #628 reproduction flag
├── bench-warmup/                  # Block E main(): FFM getpid downcall warmup curve;
│                                  #   emits warmup-v4 lane spec (AOT/Leyden variants forked by runner)
├── native-v3/                     # §5.4: Exec tasks building libpi4j-gpiod.so locally
├── native-image/                  # GraalVM lane for the v4 latency runner
└── tools/
    ├── run-matrix.sh              # taskset+governor wrapper → ./gradlew benchAll
    ├── pin-env.sh                 # governor=performance, turbo off, checks isolcpus
    ├── build-pi4j-v4.sh           # clone Pi4J main + mvnw install 5.0.0-SNAPSHOT → mavenLocal
    └── plots/plot_slides.py       # pandas/matplotlib → charts for slides 29–30
```

Key point vs Maven: v3/v4 classpath isolation is just two subprojects, each with its own
`pi4j-core` GAV; the Java runner forks one JVM per lane so the GAVs never share a classpath
(Appendix A). JMH is a plain annotation processor — no `me.champeau.jmh`, no `src/jmh`.

### 4.2 Data-collection plugin/library stack (point 2)

| Tool | Where | Why |
|---|---|---|
| **JMH** 1.37 (core + annprocess) | `bench-v3`/`bench-v4` deps | plain annotation processing — the runner forks `org.openjdk.jmh.Main` with `-rf json`. **No `me.champeau.jmh` plugin, no `src/jmh` source set** (rev.5). |
| ~~`io.morethan.jmhreport`~~ | **dropped (rev.5)** | the HTML report was the main config-cache offender; `plot_slides.py` reads the retained JMH JSON directly |
| **JMH built-in profilers** | run flags | `-prof gc` (B/op — Block D), `-prof perfnorm` (amd64: instructions/branches per op — great for the "why the numbers" slide), `-prof stack` as a portable fallback |
| **async-profiler** via `-prof async` | amd64/arm64 only | flamegraphs when a number looks suspicious (jar dropped into `tools/async-profiler/`; not available on riscv64 — noted) |
| **HdrHistogram** | `bench-latency`, `bench-common` | loss-less latency recording, `.hgrm` output, percentile export |
| **jHiccup** (javaagent) | Block C runs | independent JVM-pause telemetry in parallel with our listener latency — separates "our pipeline" from "the JVM hiccuped" |
| **JFR** | `-XX:StartFlightRecording=…` in Block C/D configs | allocation & GC events time-aligned with latency spikes; free on JDK 25 |
| **foojay-resolver** | `settings.gradle.kts` | auto-provisions the JDK 25 toolchain on amd64/arm64 (riscv64: manual BellSoft Liberica install, toolchain autodetected) |
| **oshi-core** | `EnvManifest` | one-liner capture of CPU model/cores/memory into every result JSON |

### 4.3 Single entrypoint (point 3) — Java orchestrator, Gradle only builds

Rev. 4: the orchestration moved **out of Gradle into Java**. The old root task graph was
an imperative, side-effecting sequence (load mock → measure → unload, strict order,
always-cleanup) fighting Gradle's pure, cacheable, parallel DAG — it needed a hand-wired
`mustRunAfter` chain, `finalizedBy`, and a pile of config-cache opt-outs
(`BenchShell.kt`, `jmhReport`) that bought no build-tool value. Now the `bench-runner`
module's `main()` **is** `benchAll`; Gradle's job shrank to what it's actually good at.

**The Gradle ↔ Java boundary:** each lane subproject has a `writeLaneSpec` task that
resolves its runtime classpath and emits `build/lanes/<name>.properties`
(`type`, `mainClass`, `classpath`, `workingDir`, `jvmArgs`, `sysProps`, `args`).
`:bench-runner:run` gathers them (`collectLaneSpecs`) and the Java runner reads them,
then **forks one child `java` per lane** — the only way to keep the v3/v4 `pi4j-core`
GAVs off one classpath (Appendix A) without Gradle's subproject machinery in the loop.

```java
// com.pi4j.bench.runner.BenchRunner (simplified) — sequencing is just statements
EnvCheck.run(cfg);                              // governor / taskset / libgpiod-v1 gate
writeEnvManifest(resultsDir, env, cfg);
for (LaneSpec s : jmhLanes) fork(s);            // v4-jmh, v3-jmh (self-load mocks per trial)
mocks.load();                                   // shared gpio + i2c for Blocks C/D
try {
    for (LaneSpec s : customLanes) fork(s);     // memory-fixed, memory-issue628, latency-v4
} finally {
    mocks.unload();                             // "always unload" = a real finally
}
new ReportMerger(...).merge(resultsDir);        // → results/<arch>/<lane>/<ts>/
```

The **ten** lanes, in execution order (`BenchRunner.ORDER`):
`v4-jmh`, `v3-jmh` (`type=jmh` → forked as `org.openjdk.jmh.Main` with per-JVM flags handed
to JMH's measurement forks via `-jvmArgsAppend`); `memory-fixed`, `memory-issue628`;
`warmup-v4`, `warmup-v4-aot`, `warmup-v4-leyden` (Block E — one `bench-warmup` spec forked
three ways: baseline, JEP-483 AOT cache, Leyden-EA JDK); `latency-v4`, `latency-v4-native`
(GraalVM image, opt-in `--native`), `latency-v3` — the `type=main` lanes drive
LatencyRunner / MemoryRunner / WarmupRunner. JMH is a plain annotation-processor dependency
(no `me.champeau.jmh`); the browsable HTML report is dropped (it was the main config-cache
offender — `plot_slides.py` reads the retained JSON). CPU pinning is applied **per fork**
(`--cpus`, via `taskset -c` in `Forker`), not by wrapping `gradlew`.

Per-machine invocation — one line; options are plain flags (see `Config.java`):

```bash
tools/run-matrix.sh                                  # sudo pin-env then :bench-runner:run --lane mock
tools/run-matrix.sh --lane hw                        # on the boards, real peripherals wired
./gradlew :bench-runner:run --args="--lane mock --quick"    # smoke test (1 JMH fork, short runs)
./gradlew :bench-runner:run --args="--lane mock --dry-run"  # print the plan + fork commands, no changes
```

`writeEnvManifest` + `ReportMerger` stamp each run with: git SHA of the bench repo,
pi4j versions, **native-v3 build fingerprint (§5.4)**, `uname -r`, JDK, oshi hardware
summary, lane, timestamp. Nothing is overwritten; `results/` is append-only.

### 4.4 Packaging the orchestrator (picocli → fat jar / native image / bundle)

The runner is a **picocli** `@Command` (`--help`/`--version`, typed options) packaged two
ways from one codebase:

| Artifact | Task | Portability |
|---|---|---|
| Dev run | `:bench-runner:run` | build machine; specs carry absolute `~/.gradle`/`~/.m2` paths |
| **Fat jar** `bench-runner-all.jar` | `:bench-runner:fatJar` | one artifact, **all arches** (needs a JVM) |
| **scp-ready bundle** `build/bundle/` | `:bench-runner:bundle` | copy to a board, run `./bench` |

The fat jar is hand-rolled (a plain `Jar` with `Multi-Release` + merged deps — no Shadow
plugin, no version gamble).

**The forking model bounds "no deps":** the orchestrator artifact is a self-contained
*launcher*, but it forks a child `java` per lane, so the measured lanes still need a **JDK**
(named in `bench.config` or on PATH) and their jars staged. The `bundle` task makes that
real — it stages every lane's jars (deduped) under `lanes/libs/` with **bundle-relative**
specs, a `bench` launcher (`java -jar`), `bench.config`, and `build-natives.sh`.
`--bundle-root` makes the runner resolve every spec path against the bundle. Pure-Java lanes
(V4/FFM, linuxfs, JMH, memory) run from the staged jars as-is.

**Mock drivers are embedded in the artifact.** `:embedMocks` bakes the mock C sources +
Makefiles + setup/clean scripts (both flavours, INDEX-listed so extraction works without
enumerating classpath dirs inside a jar; the per-kernel `.ko` is excluded) into the jar. On the first
`--lane mock` run `MockBuilder` materialises them into `mocks/<flavor>/` **next to where the
binary lives** and compiles each `.ko` **once, reused** after (`build.sh` is rewritten to a
reuse-aware guard; `--rebuild-mocks` forces). The JMH lanes then run with their CWD at
`mocks/<flavor>` so `BaseSetup` finds `src/test/resources`. insmod/rmmod need root: the
runner prints the exact **NOPASSWD `sudoers`** line for the resolved script dir(s) and writes
`sudoers.snippet` — grant once with `sudo visudo`. The V3 gpiod/linuxfs JNI `.so` are
arch-specific and not embedded, but `:native-v3:buildAll` **cross-compiles them for every arch
a toolchain is present for** (§5.4) and the bundle stages each under `lanes/natives/<arch>/`;
the runner expands the `${bench.arch}` token in the V3 lane spec to the board it's actually
running on. So one bundle carries all-platform natives — `build-natives.sh` is the fallback
only when the build host lacked a target's cross toolchain.

```bash
# Install the cross toolchains once (host), then one bundle carries every arch's V3 natives:
sudo dpkg --add-architecture arm64 && sudo apt-get install -y \
    gcc-aarch64-linux-gnu gcc-riscv64-linux-gnu libgpiod-dev libgpiod-dev:arm64
./gradlew :bench-runner:bundle                               # fat-jar bundle + all-arch natives
scp -r build/bundle board:~/bench && ssh board '~/bench/bench --lane hw'
```

**Runtime config (`bench.config`).** The bundle ships a `bench.config` template read at run
time (`--config <file>`, else auto-picked from the bundle root). It externalises the two
per-machine things without recompiling: the **JDK** used to fork the lanes (`jdk = …`) and
the **mock name/address map**. The addressing keys
(`bench.i2c.*`, `bench.gpio.*`, `bench.gpiod.*`, `bench.spi.*`, `bench.pwm.*`,
`bench.linuxfs.*`) are passed to **every lane** — MAIN runners directly and JMH lanes via
`-jvmArgsAppend` — and read through `bench-common`'s `BenchProps` (each lane keeps its old
value as the compiled default). So one file re-points all of bench-v3, bench-v4, latency and
memory at a different mock layout or real hardware. Every key defaults to today's mock
wiring, so the file is optional.

**What can't fold into the bundle:** the forked lane JVMs (separate measured processes — the
whole point) and the compiled mock `.ko`/kernel modules (must match the running kernel). The
V3 gpiod/linuxfs `.so` are arch-specific but the bundle ships cross-compiled copies for every
arch a toolchain was present for at build time (§5.4); only a target whose cross toolchain was
absent needs `build-natives.sh` on the board.

**Cross-platform build.** The fat-jar bundle is arch-agnostic: one `:bench-runner:bundle`
runs on amd64/arm64/riscv64 with a JDK 25 — no per-arch build step. With the cross toolchains
installed it also cross-compiles the V3 natives for all three arches into that one bundle. CI
(`.github/workflows/build.yml`) builds it once on `ubuntu-latest`.

---

## 5. Environment setup

### 5.1 All machines

```bash
# JDK 25: auto-provisioned by Gradle toolchains on amd64/arm64;
# riscv64: install BellSoft Liberica riscv64 manually (must be a JIT build, not Zero).
sudo apt-get install -y build-essential linux-headers-$(uname -r) libgpiod-dev gpiod
echo "$USER ALL=(ALL) NOPASSWD: /path/to/pi4j/plugins/pi4j-plugin-ffm/src/test/" | sudo tee /etc/sudoers.d/pi4j-bench
```

### 5.2 Quieting the machine — `tools/pin-env.sh` (invoked by `run-matrix.sh`, verified by `envCheck`)

governor=performance, turbo/boost off, cores 2–3 isolated (`isolcpus=2,3 nohz_full=2,3` on kernel cmdline once), AC power, no desktop noise. SBCs: heatsink mandatory; hwmon temperature logged during runs, throttled runs discarded.

### 5.3 Hardware inventory

| Arch | Machine | Real-lane peripherals |
|---|---|---|
| amd64 | dev workstation | mocks only |
| arm64 | Raspberry Pi 5 (primary) + Raspberry Pi 4 (pigpio lane) | GPIO loopback jumper, I2C sensor (BME280), SPI loopback MOSI→MISO / LED matrix, HW PWM channel |
| riscv64 | VisionFive 2 / Milk-V Mars / Lichee Pi 4A | same loopback set; mocks as fallback |

### 5.4 Building the missing V3 natives locally (point 4)

Why it works: `libpi4j-gpiod.so` is a thin JNI wrapper in plain C that links against
distro `libgpiod`; the official pipeline only ever *targeted* ARM (Docker cross-compile),
nothing in the code is ARM-specific. `pigpio` is the opposite — DMA + BCM register maps —
and is **not portable by design** (stays ARM-only; that's the DMA slide's point).

**Status (2026-08-14): all three arches build.** `:native-v3:buildAll` produces
`amd64=gpiod+linuxfs  arm64=gpiod+linuxfs  riscv64=gpiod+linuxfs` — libgpiod v1.6.5 compiled
**from source** per arch (no reliance on a multiarch `libgpiod-dev:<arch>`), verified as
`UCB RISC-V` / `aarch64` ELF with sha256 fingerprints. riscv64 was previously missing only
because `gcc-riscv64-linux-gnu` wasn't installed on the build host; with the cross toolchains
present (`gcc-aarch64-linux-gnu`, `gcc-riscv64-linux-gnu`) `buildAll` cross-compiles all three.

`native-v3` builds two ways: **`build`** compiles for THIS host with plain `gcc` (dev runs /
`writeLaneSpec`), while **`buildAll`** additionally CROSS-compiles for every arch a toolchain
is installed for (host arch via plain `gcc`; each non-host arch via `gcc-<triplet>`). The upstream Makefiles already honour `CROSS_PREFIX = $(CROSS_PREFIX)gcc`,
so each non-host arch just needs `gcc-<triplet>` + the multiarch `libgpiod-dev:<arch>`
present; `buildAll` runs `make CROSS_PREFIX=<triplet>- clean all` per (arch, lib), stages the
`.so` (plus the target's `libgpiod.so`) into `libs/<arch>/`, and **skips any arch whose
toolchain/lib is missing with a warning** (so a dev box without cross tools still builds the
host arch). `:bench-runner:bundle` depends on `buildAll` and stages every produced
`libs/<arch>/` under the bundle's `lanes/natives/<arch>/`.

`native-v3` subproject:

```kotlin
// native-v3/build.gradle.kts (essence)
val v3Src = layout.buildDirectory.dir("v3-src")
val out   = layout.buildDirectory.dir("libs/${System.getProperty("os.arch")}")

val cloneV3 by tasks.registering(Exec::class) {          // pinned tag, e.g. v3.0.2
    commandLine("git", "clone", "--depth", "1", "--branch", "v3.0.2",
                "https://github.com/Pi4J/pi4j-v2.git", v3Src.get().asFile.path)
    onlyIf { !v3Src.get().asFile.exists() }
}
val buildV3Gpiod by tasks.registering(Exec::class) {     // native toolchain of THIS machine
    dependsOn(cloneV3)
    workingDir(v3Src.get().dir("libraries/pi4j-library-gpiod/src/main/native"))
    commandLine("make", "clean", "all", "TARGET_DIR=${out.get().asFile.path}")   // plain gcc, links -lgpiod
    inputs.dir(workingDir); outputs.dir(out)              // incremental + cacheable
    doLast { /* write fingerprint.json: v3 tag, gcc -v, libgpiod version, sha256(lib) */ }
}
```

**Wiring into V3 runs (the "провязка"):**

```kotlin
// bench-v3/build.gradle.kts
tasks.named<me.champeau.jmh.JMHTask>("jmh") {
    dependsOn(":native-v3:buildV3Gpiod")
    // Pi4J v2/v3 native loader honors this property → skips the (absent) in-jar lib:
    jvmArgs.add("-Dpi4j.library.path=${project(":native-v3").layout.buildDirectory
                 .dir("libs/${System.getProperty("os.arch")}").get()}")
}
```

- On **arm64** we run both flavors — official in-jar lib *and* the local build — and
  assert they agree within noise (validates that the local build doesn't distort V3).
- `mergeReports` copies `fingerprint.json` next to every `bench-v3` result, so each
  number is traceable to the exact native binary it ran against.
- `envCheck` fails early on amd64/riscv64 if `libgpiod-dev` version ≠ the one the wrapper
  expects (v3 wrapper targets **libgpiod v1.x API** — pin the distro package; do not
  build against libgpiod v2, the API is incompatible).

---

## 6. Writing the tests

### 6.1 Block A — hot path (JMH, `src/jmh/java`) — **implemented for V4**

Phase 1 **ported the upstream FFM JMH suite verbatim** into
`bench-v4/src/jmh/java/com/pi4j/plugin/jmh/` (package kept so results line up with
`result.txt`), extending `com.pi4j.plugin.BaseSetup` (also copied). The setup/clean
scripts and mock-driver C sources ride along in `bench-v4/src/test/{resources,native}`
so `BaseSetup.setup(iface)` finds them at the JMH working dir. Registered benchmarks:

| Class | `@Benchmark` methods | Provider |
|---|---|---|
| `GPIOInputPerformanceTest`  | `testFFMInputRoundTrip`, `testFFMInputWithListenerRoundTrip` | `FFMDigitalInputProviderImpl` |
| `GPIOOutputPerformanceTest` | `testFFMOutputRoundTrip` | `FFMDigitalOutputProviderImpl` |
| `I2CDirectPerformanceTest`  | `testI2CDirectRoundTrip` | `FFMI2CProviderImpl` + `I2CImplementation.DIRECT` |
| `I2CSMBusPerformanceTest`   | `testSMBusRoundTrip` | `FFMI2CProviderImpl` + `SMBUS` |
| `I2CFilePerformanceTest`    | `testI2CFileRoundTrip` | `FFMI2CProviderImpl` + `FILE` |
| `SPIPerformanceTest`        | `testFFMWriteReadRoundTrip` | `FFMSpiProviderImpl` |
| `PWMPerformanceTest`        | `testPWMRoundTrip` | `FFMPwmProviderImpl` |

**Upgraded settings** applied over the upstream `@Fork(1)`/`@Warmup(3)`:
class-level `@Fork(3) @Warmup(5, t=2) @Measurement(5, t=2)`, plus
`--enable-native-access=ALL-UNNAMED -Xms512m -Xmx512m` from the `jmh` convention.
Compiles against `5.0.0-SNAPSHOT`; `java -jar bench-v4-jmh.jar -l` lists all 8.

Note the actual suite splits I2C modes into **separate classes** (not one class with
`@Param({"DIRECT","SMBUS","FILE"})`) and SPI transfers a **random 1–4-byte** payload
rather than sweeping `@Param({"1","64","4096"})`. A parameterized redesign
(one class with `@Param({"DIRECT","SMBUS","FILE"})`, an SPI `@Param({"1","64","4096"})`
sweep, and a `toggle`/`read` GPIO shape) stays an **optional refinement** once the
ported anchors are confirmed on mocks; the mirrored `bench-v3` (Phase 2) targets the
same method names so merged charts still line up.

- Bus/pin constants (`bus 97`, `bus 99` device `0x1C`, `SpiBus.BUS_6`, chip/channel 0)
  are inherited from the upstream tests — they match the mock drivers.

### 6.2 Block B — lifecycle (JMH) — **implemented**

`LifecyclePerformanceTest` in **both** `bench-v4` and `bench-v3`
(`src/main/java/com/pi4j/plugin/jmh/`, same FQCN + method names so merged charts line up),
building and shutting a Pi4J context down *inside* every `@Benchmark` (AverageTime, ms/op —
reproduces the PR #458 shape; historical: FFM ≈ 1.6 vs gpiod ≈ 0.25 ms/op on mocks → keeps
the honest "init costs more" point). Three shapes:

| Method | What it times |
|---|---|
| `testCreateShutdown`  | explicit provider + `create()` a pin + `shutdown()` (full round-trip) |
| `testExplicitContext` | `newContextBuilder().add(provider).build()` + `shutdown()` (provider add/init only) |
| `testAutoContext`     | `Pi4J.newAutoContext()` + `shutdown()` (ServiceLoader auto-discovery cost) |

**No new lane** — `org.openjdk.jmh.Main` runs every `@Benchmark` in each jar with no filter,
so these join the existing `v4-jmh` / `v3-jmh` lanes automatically. V4 uses
`FFMDigitalOutputProviderImpl` (bus 97 / bcm 5); V3 uses `GpioDDigitalOutputProviderImpl`
with `MockBoard.forceRaspberryPiForMock()` + `setGpioChipName` (same mock-chip guards as the
gpiod hot-path lane). Both compile against their pinned Pi4J and register via `-l` (6 methods
total). The V3 `testAutoContext` loads every V3 plugin on the classpath — pigpio init fails and
is swallowed on amd64 (Appendix A), which is itself part of the honest discovery cost.

`Mode.SingleShotTime` ×20 forks (cold-start distribution) stays an optional variant: the
orchestrator passes a global `-f 3` (`-f 1` under `--quick`) that overrides any class-level
`@Fork`, so a 20-fork cold-start run is a manual `org.openjdk.jmh.Main -f 20 -bm ss` invocation
rather than an orchestrated lane.

> **Remaining:** load the gpio mock → run → capture the FFM-vs-gpiod ms/op pair for slide 28.

### 6.3 Block C — latency & jitter (custom, NOT JMH)

1. **Stimulus:** mock lane — toggler flips the input via debugfs at 200 Hz (mock raises a simulated IRQ → plugin `poll()` wakes); HW lane — jumpered output pin toggled by a second process.
2. **Timestamping:** GPIO v2 events carry kernel `timestamp_ns` (CLOCK_MONOTONIC — verify once per kernel); `delta = System.nanoTime() − event.kernelTs()` in the listener. Where V3 hides the kernel ts, timestamp on the toggler side via shared-memory mailbox.
3. **Recording:** HdrHistogram (µs, 3 s.f.), 60 s measure after 30 s warmup → `.hgrm` + percentile JSON; **jHiccup agent attached** to every run; **JFR on** for GC/alloc correlation.
4. **Configs:** {v4-ffm, v3-gpiod(local native)} × {idle, GC-pressure 200 MB/s} × {G1, ZGC} × {JIT, native-image(v4)}.
5. **Warmup curve:** ring-buffer of the first 10⁴ raw latencies → call #1/#100/#10⁴ plot; native image expected flat.

### 6.4 Block D — memory

1. **Trajectory:** 1 M I2C reads, snapshot every 50 k ops: `VmRSS` + `jcmd VM.native_memory summary` diff (`-XX:NativeMemoryTracking=summary`). Modes: `--mode=fixed` (≥ 4.0.2 per-call `ofConfined`) vs `--mode=issue628` (pre-fix tag v4.0.1 dependency in a dedicated Gradle configuration) → flat line vs the ramp.
2. **B/op:** `./gradlew :bench-v4:jmh :bench-v3:jmh -PjmhProfilers=gc` → `gc.alloc.rate.norm` (expect JNA ≫ FFM).

### 6.5 Block E — warmup / AOT (custom, `bench-warmup`)

`WarmupRunner` times a pure `java.lang.foreign` downcall (libc `getpid`) from a cold JVM,
recording the first 10⁴ raw latencies (`WarmupCurve`) + steady-state HdrHistogram
(`WarmupReport`). No Pi4J, no kernel mocks — this isolates exactly the JIT ramp of the FFM
downcall stub that an AOT cache / Leyden targets, so all three variants run anywhere. The
orchestrator forks the one `warmup-v4` spec three ways:

- **`warmup-v4`** — baseline JIT (the ramp we want to flatten).
- **`warmup-v4-aot`** — JDK 25 AOT cache (JEP 483): a training run records, the measured
  fork replays the cache.
- **`warmup-v4-leyden`** — same, on a Leyden-EA JDK supplied via `--leyden-jdk` / config
  `run.leyden.jdk`; skipped with a note when no Leyden JDK is configured.

Feeds the warmup-curve slide (18) alongside the native-image lane (Block C / `latency-v4-native`),
which is the other "flatten the ramp" data point.

---

## 7. Run protocol

1. `tools/run-matrix.sh [--lane hw]` per machine — everything else is the task graph (§4.3): envCheck → mocks → lifecycle → hot path → memory → latency → mergeReports → unload.
2. Boards: 3 recorded runs on different days (thermals/SD variance); keep median, error bars from JMH CI.
3. `results/<arch>/<lane>/<timestamp>/` is append-only: JMH JSON, HTML (`jmhReport`), `.hgrm`, jHiccup logs, JFR files, RSS CSV, env manifest, `fingerprint.json` (v3 native provenance).
4. `tools/plots/plot_slides.py` renders: relative bars (slide 29), SPI size sweep (slide 30), percentile ladder + warmup curve (slide 18), RSS ramp (slide 20).
5. Optional CI: self-hosted amd64 runner (insmod needs a privileged VM), nightly `benchAll -Plane=mock` with `-Pquick` (1 fork); >10 % regression vs stored baseline fails → doubles as a Pi4J regression suite to upstream after the talk.

---

## 8. Expected outcomes → slide mapping (hypotheses to confirm or honestly refute)

| Result | Slide |
|---|---|
| GPIO r/w: FFM ≈ 0.3–0.8 µs (mocks); multi-x vs v3-gpiod (JNA/Reflection layer gone) — now measurable on all 3 arches thanks to §5.4 | 29 |
| I2C: DIRECT ≤ SMBUS ≤ FILE; vs linuxfs modest; real-bus gaps shrink | 29–30 |
| SPI: advantage melts 1 B → 4 KiB (bus-bound) | 30 |
| Lifecycle: FFM init pricier (~1.6 vs ~0.25 ms/op historical) | 28 |
| p50 stable; p99.9 owned by GC; ZGC trims tail; native image flattens warmup | 18 |
| RSS flat on `ofConfined`, ramp on `ofAuto` (#628); JNA ≫ FFM B/op | 19–20 |
| Official V3 artifacts: ARM-only; V4 runs everywhere incl. riscv64 (and making V3 run took a hand-built native — the exact cost V4 removed) | 27 banner |

---

## 9. Phased checklist

- [~] **Phase 0 (½ d).** Gradle skeleton + convention plugins **done** (Kotlin DSL,
  7 modules + `build-logic`, JDK 25 toolchain resolves locally). Run-harness now **real**
  (Phase 6): `envCheck` (governor/taskset/libgpiod-v1/sudoers, VM-graceful), `buildMocks`
  (compiles all 8 mock `.ko` via explicit `M=<dir>` kernel build — verified), `loadMocks`/
  `unloadMocks` (sudo the proven setup/clean scripts). **Remaining:** the sudoers entry is a
  per-machine setup step; live insmod happens on the target host.
- [~] **Phase 1 (1 d).** FFM JMH suite **ported into `bench-v4`** (`src/main/java`, rev.5)
  with upgraded settings; compiles against `5.0.0-SNAPSHOT` (mavenLocal) and all 8
  benchmarks register (`-l`). Runner forks `org.openjdk.jmh.Main -rf json` (no
  `me.champeau.jmh`, no `jmhReport` HTML — dropped in rev.5). SLF4J silenced
  (slf4j-simple, `defaultLogLevel=off`). **Remaining:** load mocks → run →
  confirm numbers ≈ `result.txt`.
- [~] **Phase 2 (1 d).** `bench-v3` mirror **written against released 3.0.2** (Central).
  GPIO lanes are split into **provider-isolated classes** so gpiod and linuxfs never
  share a context or the GpioDContext singleton: `GpioDInput/OutputPerformanceTest`
  (JNA, chip picked by `setGpioChipName("gpiochip0")`) and
  `LinuxFsInput/OutputPerformanceTest` (sysfs), plus `I2CFilePerformanceTest` (linuxfs
  — no `I2CImplementation` in 3.0.2, so linuxfs *is* the file lane) and
  `SPIPerformanceTest` (linuxfs); 7 benchmarks register. Plots group by interface via
  method/provider naming. **`native-v3:buildAll` cross-builds BOTH JNI wrappers for all
  three arches** — `libpi4j-gpiod.so` (links **libgpiod v1.6.5 built from source** per arch,
  v1 API) and `libpi4j-linuxfs.so` (links `-lrt`), plus a staged `libgpiod.so`; sha256
  fingerprints written; one `-Dpi4j.library.path` wired for all three. amd64/arm64/riscv64
  all verified as correct-arch ELF (cross toolchains installed on the build host).
  **Remaining:** load mocks → run; arm64 official-vs-local-native agreement check; pigpio (ARM) lane.
- [ ] **Phase 2 (1 d).** `bench-v3` mirror on 3.x artifacts; **`native-v3` build task + `pi4j.library.path` wiring**; arm64 official-vs-local-native agreement check; empirical §3.2 table.
- [~] **Phase 3 (1 d).** Block C harness **implemented**. Single-process design: a
  `GpioMockStimulus` (bench-common) flips the input line via debugfs (`gpio_mock_dbg_set`
  → `fire_edge`), publishing `t0` before the edge-raising `write()`; the Pi4J listener
  captures `t1`, and `t1 − t0` is the whole edge→poll→dispatch→listener path — **the same
  for V3 and V4**, because the public `DigitalStateChangeEvent` carries **no** kernel
  timestamp on either lane (see the new Appendix A trap). A `Lane` SPI with flavor-isolated
  impls (`src/v${v}/java`, `-Pv=3|4`: `V4Lane` ffm bus 97/bcm 3 on `accessible`; `V3Lane`
  gpiod `gpiochip0`/addr 3 on `pinctrl-mock`, MockBoard guards + local native path) keeps the
  two `pi4j-core` GAVs off one classpath. Ping-pong driver at `--rate-hz` (200) with a
  1-slot handoff (no SynchronousQueue race), `--warmup-seconds`/`--measure-seconds`, HdrHistogram
  → `.hgrm` + percentile JSON (`LatencyReport`), `WarmupCurve` (first 10⁴ raw → CSV, slide 18),
  `GcPressure` (`--gc-pressure[-mbps]`). GC label read back from the live collector; build wires
  `-Pgc=g1|zgc` (`-XX:+UseZGC`), `-Pjfr` (StartFlightRecording), optional `-PjHiccup=<jar>`.
  Both flavors compile (JDK 25); mocks-absent path degrades with a clear hint + exit 2.
  **Remaining:** load mocks + grant debugfs → live run (Phase 6, needs root); arm64/riscv64 lanes;
  native-image config folds in at Phase 5.
- [~] **Phase 4 (½ d).** Block D memory harness **implemented**. `MemoryRunner` drives 1 M
  FFM I2C `readRegister` calls, snapshotting `VmRSS` (`RssSampler`) + NMT committed
  (new `NmtSampler` → `jcmd VM.native_memory summary`, needs `-XX:NativeMemoryTracking=summary`)
  every 50 k ops → `-trajectory.csv` + summary JSON (`MemoryReport`, with rss/nmt start→end Δ).
  The repro is **version-swapped, not code-swapped**: compiled once against **4.0.2**
  (`I2CConfigBuilder.newInstance(Context)` — the 4.0.x API, *not* 5.0.0-SNAPSHOT's no-arg form),
  it runs against two isolated Central classpaths — `run` = fixed 4.0.2 (per-call
  `Arena.ofConfined` → flat), `runIssue628` = pre-fix 4.0.1 (immortal static `Arena.ofAuto`
  in `Pi4JNativeContext` → the ioctl buffers pin to a never-freed arena → RSS ramp). A dedicated
  `issue628` Gradle configuration (self-contained core+ffm+bench-common) keeps 4.0.1 off the
  4.0.2 classpath; `runMemoryBoth` produces the pair; root `benchMemory` calls it. The **B/op**
  lane (§6.4.2) was already wired: `-PjmhProfilers=gc` → `gc.alloc.rate.norm` on bench-v3/v4.
  Both lanes resolve from Central, compile (JDK 25), and degrade with a clear hint + exit 2 when
  the i2c mock is absent. **Remaining:** load i2c mock → live run to capture the flat-vs-ramp pair.
- [~] **Phase 5 (½ d).** GraalVM native-image lane **implemented & building**. `native-image`
  applies `org.graalvm.buildtools.native` 0.10.6, depends on `bench-latency` (default `-Pv=4` →
  FFM + `V4Lane` + `LatencyRunner`), and builds `latency-v4-native` — a 32 MB AOT image, GraalVM
  CE 25.0.2, ~40 s. Toolchain pinned by vendor (`JvmVendorSpec.GRAAL_VM`) so it selects graalce
  even though the outer build runs on plain JDK 25. buildArgs: `--enable-native-access` (FFM),
  `--initialize-at-run-time=com.pi4j.plugin.ffm,com.pi4j.boardinfo` (they open fds / parse /proc
  at runtime). The image **runs the whole harness end-to-end** and degrades to exit 2 at the
  hardware gate. Two gaps handled along the way: (1) the plugin isn't config-cache compatible →
  its tasks opt out (same as jmhReport); (2) oshi/JNA hardware introspection needs reflection
  metadata under native-image → `EnvManifest.captureSafe()` falls back to the JDK view (telemetry
  never aborts a bench), and the runner labels the lane `gc=native` via the imagecode property.
  **Descriptor registration:** the build reports `0 downcalls registered` — FFM stubs and the full
  oshi/reflection set come from the **tracing agent** against a live mock, wired as
  `:native-image:run -Pagent` → `metadataCopy` into `META-INF/native-image/` (seeded now with
  `native-image.properties` + a `resource-config.json` baseline). **Remaining:** run the agent on a
  mock-loaded host to capture foreign/reflect metadata → rebuild → real flat-warmup run (Phase 6).
- [~] **Phase 6 (1 d).** Run harness **implemented & validated on amd64**; recorded runs pending
  hardware. The root `benchAll` graph is now real end-to-end: `envCheck` (read-only gate, fails
  with hints; VM-graceful — degrades when cpufreq/turbo absent), `buildMocks` (out-of-tree kernel
  build of all 8 mock `.ko`, explicit `M=<dir>` so it ignores the Makefile's `$(PWD)` — compiles
  clean against the running kernel), `loadMocks`/`unloadMocks` (sudo the proven per-interface
  setup/clean scripts; the JMH lanes still self-load per trial, these pre-load the gpio+i2c mocks
  the custom Block C/D runners need), and `mergeReports` (snapshots every Block A–D output —
  JMH JSON+HTML, latency HDR/CSV, memory trajectory, native-v3 fingerprints, env manifest — into a
  timestamped `results/<arch>/<lane>/<ts>/` with a provenance `run-manifest.json`: git SHA, pi4j
  V3/V4 versions, JDK, kernel). `tools/pin-env.sh` (governor=performance, turbo off, isolcpus/AC/
  hwmon checks) + `tools/run-matrix.sh` (root→pin, `taskset -c`, `--no-daemon benchAll`) wire the
  one-line-per-machine protocol. Config-cache traps fixed: a compiled `build-logic/BenchShell.kt`
  helper + local-val capture keep every run-harness task clean (the results plugin too). **Remaining:**
  the actual recorded runs — amd64 (root, mocks) → arm64 (Pi 5 + Pi 4 pigpio) → riscv64, ×3 on boards.
- [~] **Phase 5b (½ d).** Block E warmup/AOT lane **implemented** (`bench-warmup`,
  rev.5): `WarmupRunner` (FFM `getpid` cold-start curve → `WarmupReport`), one `writeLaneSpec`
  forked three ways by the runner — `warmup-v4` (baseline), `warmup-v4-aot` (JEP-483 AOT
  cache), `warmup-v4-leyden` (Leyden EA via `--leyden-jdk`). **Remaining:** capture the AOT
  training→replay pair on a host; supply a Leyden EA JDK for the Leyden variant; fold the
  curves into slide 18 beside the native-image lane.
- [~] **Phase B (½ d).** Block B lifecycle (§6.2) **implemented**: `LifecyclePerformanceTest`
  in both `bench-v4` and `bench-v3` (`testCreateShutdown` / `testExplicitContext` /
  `testAutoContext`, ms/op), mirrored method names, compiling against their pinned Pi4J and
  registering via `-l` (6 methods). No new lane — they join `v4-jmh`/`v3-jmh` automatically.
  **Remaining:** load the gpio mock → run → capture the FFM-vs-gpiod init pair for slide 28.
- [ ] **Phase 7 (½ d).** `plot_slides.py` → final numbers into slides 18–20, 28–30; kill every "TODO/ориентир".
- [ ] **Phase 8 (opt).** Upstream the bench project + nightly mock lane to Pi4J.

---

## Appendix A. Known traps

- **The kernel edge timestamp never reaches a listener (both lanes):** the plan's §6.3.2
  hoped to read the GPIO v2 `timestamp_ns` *in the listener* and subtract it from
  `System.nanoTime()`. But V4's `FFMDigitalInput.addListener` builds
  `new DigitalStateChangeEvent<>(this, state)` — the kernel `timestampNs` the `EventWatcher`
  read (`DetectedEvent.timestampInNanos`) is **dropped** before dispatch; V3's gpiod listener
  is the same. The public `DigitalStateChangeEvent` has no timestamp field on either lane, so
  the "kernel-ts delta in the listener" is unreachable through the public API. Block C therefore
  times the **stimulus side** in a single process: publish `t0` immediately before the
  edge-raising debugfs `write()`, read `t1` first thing in the listener callback,
  `latency = t1 − t0`. This is uniform across V3/V4 and needs no shared-memory mailbox.
  The debugfs write is **in-memory** (no block layer), a sub-µs syscall, and — being the
  same `GpioMockStimulus` on both lanes — a common-mode offset that cancels in the V3-vs-V4
  delta. To keep the *absolute* mock number honest, the runner brackets each write
  (`[t0, t_afterWrite]`, direct ByteBuffer to avoid a heap-copy) and reports the write-cost
  percentiles beside the latency (`stimulusWriteMicros` in the JSON + a `-writecost.hgrm`).
- **Same-GAV conflict:** v3 and v4 `pi4j-core` never on one classpath — hence separate subprojects.
- **libgpiod v1 vs v2:** the V3 wrapper targets the **v1 API**; building against libgpiod 2.x fails/misbehaves. Pin the distro package; `envCheck` enforces it.
- **Local V3 native ≠ official:** always label; on arm64 prove equivalence against the official lib before quoting cross-arch numbers.
- **Pi4J swallows provider-init failures → misleading `ProviderNotFoundException`:**
  `DefaultRuntimeProviders.initialize()` wraps each provider's `initialize()` in
  `try { add(p) } catch (Exception e) { logger.error(...); continue; }`. So a gpiod
  chip-discovery failure (`IllegalStateException: Couldn't identify suitable gpiochip`)
  is logged at ERROR and skipped; the provider never registers and the *later*
  `create()` throws `ProviderNotFoundException [DIGITAL_INPUT]`. With SLF4J at `off`
  the real cause is invisible — hence `bench-common` runs at `error` level, and
  `bench-v3 MockBoard.requireProvider(...)` fails fast right after `build()`.
- **gpiod chip selection on mocks:** with no chip name set, `GpioDContext` picks the
  chip whose **label contains `"pinctrl"`**. The mock's default label is `accessible`,
  so `bench-v3 gpio-setup.sh` labels its first chip `pinctrl-mock`.
- **linuxfs sysfs GPIO output — Pi4J sets IRQ-edge on an output pin:** `LinuxFsDigitalOutput.initialize()`
  does `direction=OUT` then `if (edge-file exists) edge=both`; gpiolib rejects locking an
  OUTPUT line as IRQ (`gpiochip_lock_as_irq -> -EIO`). The mock exposed an IRQ (hence an
  `edge` file) on every line. Fix: added a **`disable_sim_irq`** module param to
  `gpio-mock.c` (no `to_irq`/irq-domain ⇒ `gpiod_to_irq()` fails ⇒ no `edge` file ⇒ Pi4J
  skips the write). The linuxfs lane loads it via `gpio-linuxfs-setup.sh`; the gpiod lane
  keeps IRQs (it *requests* edge events). Same ngpios/hog as the gpiod mock so the
  dynamic sysfs base — and thus pin numbers — line up.
- **linuxfs sysfs GPIO — non-root direction/value write races udev:** the deprecated
  `/sys/class/gpio/gpioN/*` is created root-owned and no gpio udev rule exists here, so a
  non-root JMH JVM writing `direction` is a coin-flip. Fix: `gpio-linuxfs-setup.sh`
  pre-exports every mock line **as root**, `udevadm settle`s, then `chmod a+rw` — the JVM
  then finds them exported and writes world-writable files (no race).
- **The mock's sysfs base is dynamic and unstable:** it registers `.base = -1`, so the
  global gpio number shifts across module configs (disabling the IRQ moved it → a
  hard-coded 511 became `EINVAL` on export). Don't hard-code: `bench-v3 LinuxFsMock.base()`
  reads `/sys/class/gpio/gpiochip*/{label,base}` (primary chip label contains "mock") and
  addresses lines as `base + offset`; `gpio-linuxfs-setup.sh` labels only that chip "…mock".
- **`rmmod: Module gpio_mock is in use` at `@TearDown`:** Pi4J 3.0.2's gpiod stack keeps
  a gpiochip fd open for the **life of the JVM** — `gpioD.shutdown()` doesn't drop it —
  so an in-trial `rmmod` (run while the JMH fork JVM is still alive) always fails, and
  waiting (`sleep`) doesn't help. The fd only closes on JVM exit. Fix: `gpio-clean.sh`
  is **best-effort** (`rmmod … || true; exit 0`, never fails the trial) and
  `gpio-setup.sh` **reloads** — the next trial is a fresh JVM, so the prior fd is gone
  and its `rmmod` succeeds (with a reuse-if-stuck fallback). Better long-term: load the
  gpio mock **once** globally (benchAll `loadMocks`) instead of per-trial.
- **Stale mock silently persists (the label never applied):** `*-setup.sh` did a bare
  `insmod` with no `rmmod` and no `set -e`. If a mock is already loaded (a crashed run
  that skipped `@TearDown`), `insmod` fails but the trailing `sleep` returns 0, so
  `BaseSetup.setup()` reports success while the **old** module/label stays — presenting
  as "no 'pinctrl' chip found" even after relabelling. Fix: `bench-v3 gpio-setup.sh`
  now `rmmod`s first (idempotent reload) and fails loudly if `insmod` really fails.
  The other `*-setup.sh` scripts have the same latent bug — harden them the same way.
- **A single `pi4j.library.path` must hold *every* V3 native:** setting it for gpiod
  also redirects the **linuxfs** loader (it stops extracting its bundled `.so` and
  looks in that dir). The 3.0.2 jars bundle natives for ARM only, so `:native-v3:build`
  compiles and stages **all three** into `libs/<arch>/`: `libpi4j-gpiod.so` (JNI
  wrapper), `libgpiod.so` (distro `.so.2` copy — `GpioD.<clinit>` `System.load`s it
  *before* the wrapper), and `libpi4j-linuxfs.so` (JNI ioctl wrapper, links `-lrt`).
  Missing any one → `UnsatisfiedLinkError: .../lib<name>.so` mid-run.
- **`@Fork(1)` lies** — 3+ forks for anything on a slide; forked JVMs inherit `taskset` affinity from the Gradle launcher (that's why pinning wraps `./gradlew`, not individual javas).
- **Gradle daemon noise:** `run-matrix.sh` runs with `--no-daemon` and warms the build first, so compilation never overlaps measurement.
- **Mock ≠ device:** mocks still cross the kernel boundary (that's the point — bridge cost), but label lanes explicitly on every chart.
- **RPi 5 vs pigpio:** DMA/BCM registers → Pi 4 lane only (and that's the DMA slide's argument).
- **riscv64 tooling:** JIT-capable JDK build mandatory (Liberica); `-prof perfnorm`/async-profiler unavailable — rely on `-prof stack` + JFR there.
- **Kernel event clock:** verify `timestamp_ns` base (CLOCK_MONOTONIC vs REALTIME changed around kernel ~5.7) once per machine; calibrate before Block C.
- **V3 gpiod refuses to run off a real Pi (mock lane):** Pi4J 3.0.2 `GpioDContext.initialize()`
  bails at `if (!BoardInfoHelper.runningOnRaspberryPi())` — which is just
  `getBoardModel() != UNKNOWN` — so on amd64 it never opens a chip. Fix without
  patching the jar: force a model via the public API,
  `BoardInfoHelper.current().setBoardModel(BoardModel.MODEL_4_B)` (see
  `bench-v3 MockBoard`, called in the gpiod `@Setup`). **Second guard right after:** with
  no chip name set, it selects the chip whose label contains `"pinctrl"`; the mock's
  default label is `accessible`, so `bench-v3/src/test/resources/gpio-setup.sh` labels
  its first mock chip `pinctrl-mock`. Both together let the gpiod JNA lane run on mocks.