package com.pi4j.bench.common;

/// Zero-dependency ANSI styling for the bench console — one helper shared by the
/// orchestrator and every measurement lane so the whole run reads as one colour scheme.
///
/// Colour is auto-detected once at class-init: on when stdout is a real terminal
/// ([java.io.Console#isTerminal], JDK 22+) and neither `NO_COLOR` nor a `dumb` `TERM`
/// vetoes it. `BENCH_COLOR=always|never` (or `1`/`0`) forces the decision — handy when
/// piping into `less -R` or capturing a plain log. When disabled every helper returns its
/// input verbatim and the symbols fall back to ASCII, so redirected output stays clean.
///
/// The forked lanes inherit this process's stdio (and env), so the same detection and the
/// same `BENCH_COLOR` override apply uniformly across the runner and its children.
public final class Ansi {

    private Ansi() {}

    /// True when styling should be emitted — computed once, honoured by every helper.
    public static final boolean ENABLED = detect();

    // Raw SGR codes (kept private; callers go through the semantic helpers below).
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String GRAY = "\u001B[90m";
    private static final String BRIGHT_GREEN = "\u001B[92m";
    private static final String BRIGHT_RED = "\u001B[91m";

    // Status glyphs — Unicode when styled, ASCII when not (log-safe).
    public static final String CHECK = ENABLED ? "✔" : "OK";   // ✔
    public static final String CROSS = ENABLED ? "✖" : "X";    // ✖
    public static final String WARN_SIGN = ENABLED ? "⚠" : "!";// ⚠
    public static final String ARROW = ENABLED ? "→" : "->";   // →
    public static final String BULLET = ENABLED ? "•" : "*";   // •
    public static final String GEAR = ENABLED ? "▸" : ">";     // ▸

    private static String wrap(String code, String s) {
        return ENABLED ? code + s + RESET : s;
    }

    public static String bold(String s)    { return wrap(BOLD, s); }
    public static String dim(String s)      { return wrap(DIM, s); }
    public static String red(String s)      { return wrap(BRIGHT_RED, s); }
    public static String green(String s)    { return wrap(BRIGHT_GREEN, s); }
    public static String yellow(String s)   { return wrap(YELLOW, s); }
    public static String blue(String s)     { return wrap(BLUE, s); }
    public static String magenta(String s)  { return wrap(MAGENTA, s); }
    public static String cyan(String s)     { return wrap(CYAN, s); }
    public static String gray(String s)     { return wrap(GRAY, s); }

    /// Bold cyan — section headings and the lane name in a lane banner.
    public static String heading(String s)  { return ENABLED ? BOLD + CYAN + s + RESET : s; }
    /// Bold — a highlighted metric value inside an otherwise plain line.
    public static String value(String s)    { return wrap(BOLD, s); }
    /// Bold green — a success headline.
    public static String okText(String s)   { return ENABLED ? BOLD + GREEN + s + RESET : s; }
    /// Bold red — a failure headline.
    public static String errText(String s)  { return ENABLED ? BOLD + RED + s + RESET : s; }

    /// `✔ <msg>` in green — a completed step.
    public static String ok(String msg)     { return green(CHECK) + " " + msg; }
    /// `⚠ <msg>` in yellow — a non-fatal warning.
    public static String warn(String msg)   { return yellow(WARN_SIGN + " " + msg); }
    /// `✖ <msg>` in red — a failure.
    public static String fail(String msg)   { return red(CROSS + " " + msg); }

    /// A full-width rule the width of a lane banner (dim).
    public static String rule() {
        return dim("─".repeat(72)); // ─
    }

    /// A boxed, coloured section banner:
    /// ```
    /// ── title ────────────────────────────────────────────────────────────
    /// ```
    public static String banner(String title) {
        var plain = "── " + title + " ";
        var pad = Math.max(0, 72 - plain.length());
        var line = "── " + heading(title) + " " + dim("─".repeat(pad));
        return ENABLED ? line : plain + "-".repeat(pad);
    }

    private static boolean detect() {
        var override = env("BENCH_COLOR");
        if (override != null) {
            if (override.equalsIgnoreCase("always") || override.equals("1")) return true;
            if (override.equalsIgnoreCase("never") || override.equals("0")) return false;
        }
        if (env("NO_COLOR") != null) return false;
        if ("dumb".equals(env("TERM"))) return false;
        var console = System.console();
        return console != null && console.isTerminal();
    }

    private static String env(String key) {
        var v = System.getenv(key);
        return (v == null || v.isBlank()) ? null : v;
    }
}
