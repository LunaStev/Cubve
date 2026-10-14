package com.destroystokyo.paper;

import com.destroystokyo.paper.util.VersionFetcher;
import io.github.lunastev.cubve.CubveVersionFetcher;
import net.kyori.adventure.text.Component;

/**
 * Retains the upstream entry points while routing update checks to Cubve.
 */
public class PaperVersionFetcher implements VersionFetcher {
    private final CubveVersionFetcher delegate = new CubveVersionFetcher();

    @Override
    public long getCacheTime() {
        return this.delegate.getCacheTime();
    }

    @Override
    public Component getVersionMessage() {
        return this.delegate.getVersionMessage();
    }

    public static void getUpdateStatusStartupMessage() {
        // /version checks asynchronously on demand. Startup must not depend on GitHub availability.
    }
}
