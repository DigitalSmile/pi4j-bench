package com.pi4j.plugin.jmh;

import com.pi4j.boardinfo.definition.BoardModel;
import com.pi4j.boardinfo.util.BoardInfoHelper;
import com.pi4j.context.Context;
import com.pi4j.provider.Provider;

/// On a non-Pi host (the amd64 mock lane) `BoardInfoHelper` detects
/// `BoardModel.UNKNOWN`, so Pi4J 3.0.2's `GpioDContext.initialize()` short-circuits
/// at `if (!runningOnRaspberryPi())` and never opens a gpiochip — every gpiod line
/// open then fails. We force a concrete Pi model through the **public** BoardInfo
/// API (no reflection, no jar patching) so the gpiod (JNA→libgpiod) lane can run
/// against the mock chip. On real hardware this is a no-op (the real model is
/// already detected). Pi 4B chosen deliberately: pre-RP1, closest to the mock.
final class MockBoard {

    private MockBoard() {}

    static void forceRaspberryPiForMock() {
        if (!BoardInfoHelper.runningOnRaspberryPi()) {
            BoardInfoHelper.current().setBoardModel(BoardModel.MODEL_4_B);
        }
    }

    /// Fail fast with the real cause. Pi4J's provider loop catches any provider
    /// `initialize()` failure, logs it at ERROR, and *continues* — so a gpiod chip
    /// discovery failure silently drops the provider and only surfaces much later
    /// as a confusing `ProviderNotFoundException` at `create()`. Check right after
    /// `build()` and point at the actual problem instead.
    static void requireProvider(Context ctx, Class<? extends Provider> type, String hint) {
        if (!ctx.providers().exists(type)) {
            throw new IllegalStateException(
                type.getSimpleName() + " failed to initialize and was skipped by Pi4J. " + hint +
                " Re-run with -Dorg.slf4j.simpleLogger.defaultLogLevel=error to see the underlying cause.");
        }
    }
}
