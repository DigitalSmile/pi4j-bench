package com.pi4j.bench.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Block C stimulus: flips a mock GPIO **input** line through the mock driver's
/// debugfs hook (`/sys/kernel/debug/gpio-mock/<label>/line<N>`). Writing a new
/// value there changes what a `get` returns *and* raises a simulated edge IRQ, so
/// a Pi4J listener blocked in `poll()` on the line's request fd wakes up — exactly
/// the edge→listener path we measure (see `bench-v3/src/test/native/gpio/gpio-mock.c`,
/// `gpio_mock_dbg_set` → `gpio_mock_fire_edge`).
///
/// The write() syscall is what fires the edge *synchronously* inside the kernel, so
/// the caller must publish its start timestamp (`t0`) **before** calling [#write],
/// then read `System.nanoTime()` in the listener callback (`t1`); `t1 - t0` is the
/// full edge→poll→dispatch→listener latency, measured identically for V3 and V4.
///
/// Zero Pi4J deps — pure file I/O, shared by both flavor lanes.
///
/// Note: `/sys/kernel/debug` is root-only by default; a non-root runner must be
/// granted access (run under `tools/pin-env.sh`/sudo, or `chmod` the node). Use
/// [#available] to fail fast with a clear hint instead of mid-run `EACCES`.
public final class GpioMockStimulus implements AutoCloseable {

    /// The `%llu\n` scalar attribute takes at most a 64-bit decimal plus newline.
    private static final byte[] HIGH = "1\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LOW = "0\n".getBytes(StandardCharsets.US_ASCII);

    private final Path node;
    private final FileChannel channel;
    // DIRECT buffers, reused across the whole run: a heap buffer would force
    // FileChannel.write to copy into a pooled temp direct buffer on every call
    // (extra memcpy + jitter) — the stimulus is on the measured path, so we skip it.
    private final ByteBuffer high = direct(HIGH);
    private final ByteBuffer low = direct(LOW);

    private GpioMockStimulus(Path node, FileChannel channel) {
        this.node = node;
        this.channel = channel;
    }

    private static ByteBuffer direct(byte[] bytes) {
        return ByteBuffer.allocateDirect(bytes.length).put(bytes);
    }

    /// Resolves the debugfs node for `chipLabel`/`lineOffset` without opening it —
    /// use to probe [#available] before deciding to run.
    public static Path nodeFor(String chipLabel, int lineOffset) {
        return Path.of("/sys/kernel/debug/gpio-mock", chipLabel, "line" + lineOffset);
    }

    /// True when the debugfs node exists and is writable by this process.
    public static boolean available(String chipLabel, int lineOffset) {
        var node = nodeFor(chipLabel, lineOffset);
        return Files.isWritable(node);
    }

    /// Opens the debugfs node for the whole measurement run.
    /// @throws IOException if the mock isn't loaded or debugfs isn't accessible
    public static GpioMockStimulus open(String chipLabel, int lineOffset) throws IOException {
        var node = nodeFor(chipLabel, lineOffset);
        var channel = FileChannel.open(node, StandardOpenOption.WRITE);
        return new GpioMockStimulus(node, channel);
    }

    /// Drives the line HIGH/LOW, firing a rising/falling edge on change. The kernel
    /// `.set` attribute callback runs inside this write(), so the edge is raised
    /// before the call returns.
    ///
    /// The node is a `DEFINE_DEBUGFS_ATTRIBUTE` scalar, whose write path
    /// (`simple_attr_write`) ignores the file offset and never sets `FMODE_PWRITE`.
    /// A *positional* write is therefore rejected with `ESPIPE` ("Illegal seek"), so
    /// we issue a plain sequential `write()`; the kernel re-parses the value and
    /// re-runs `.set` on every call regardless of where the cursor has advanced to.
    public void write(boolean high) {
        var buf = (high ? this.high : this.low).clear();
        try {
            // A 2-byte write to a debugfs `simple_attr` node is atomic and never
            // short-writes, so a single write suffices; assert it anyway.
            int n = channel.write(buf);
            if (buf.hasRemaining()) {
                throw new IOException("short debugfs write (" + n + " bytes) on " + node);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("debugfs write failed on " + node, e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
