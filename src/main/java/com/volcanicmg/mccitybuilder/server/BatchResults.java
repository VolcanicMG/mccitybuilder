package com.volcanicmg.mccitybuilder.server;

import java.util.function.Consumer;

/**
 * Where a {@link InstantBuild.BatchResult} goes on the receiving side: the client registers its handler at start-up,
 * so this common class needs no client imports (and a dedicated server never sets one).
 */
public final class BatchResults {
    private static Consumer<InstantBuild.BatchResult> handler = r -> {
    };

    private BatchResults() {
    }

    public static void handler(Consumer<InstantBuild.BatchResult> h) {
        handler = h;
    }

    static void accept(InstantBuild.BatchResult r) {
        handler.accept(r);
    }
}
