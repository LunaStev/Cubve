package io.github.lunastev.cubve;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Normal
class CubveVersionFetcherTest {
    @Test
    void comparesPublishedPatchNumbers() {
        final JsonObject response = new JsonObject();
        response.addProperty("tag_name", "cubve-26.3-4");
        response.addProperty("draft", false);
        response.addProperty("prerelease", false);
        final String message = PlainTextComponentSerializer.plainText().serialize(CubveVersionFetcher.describeRelease(response, "26.3", 1));
        assertTrue(message.contains("patch 4 is available"));
        assertTrue(message.contains("running patch 1"));
    }

    @Test
    void differentMinecraftVersionsDoNotCompareResetPatchNumbers() {
        final JsonObject response = new JsonObject();
        response.addProperty("tag_name", "cubve-26.4-1");
        response.addProperty("draft", false);
        response.addProperty("prerelease", false);
        final String message = PlainTextComponentSerializer.plainText().serialize(CubveVersionFetcher.describeRelease(response, "26.3", 20));
        assertTrue(message.contains("targets Minecraft 26.4"));
        assertFalse(message.contains("latest Cubve release for Minecraft 26.3"));
    }

    @Test
    void equalPatchIsCurrentButDraftIsNotAnUpdate() {
        final JsonObject response = new JsonObject();
        response.addProperty("tag_name", "cubve-26.3-4");
        response.addProperty("draft", false);
        response.addProperty("prerelease", false);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(CubveVersionFetcher.describeRelease(response, "26.3", 4)).contains("latest Cubve release"));
        response.addProperty("draft", true);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(CubveVersionFetcher.describeRelease(response, "26.3", 1)).contains("Could not check"));
    }

    @Test
    void ownBrandRetainsPaperCompatibilityWithoutClaimingFolia() {
        final ServerBuildInfo info = new ServerBuildInfoImpl(Key.key("cubve", "cubve"), "Cubve", "26.3", "26.3",
            OptionalInt.empty(), Instant.EPOCH, Optional.empty(), Optional.empty());
        assertTrue(info.isBrandCompatible(Key.key("cubve", "cubve")));
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID));
        assertFalse(info.isBrandCompatible(Key.key("papermc", "folia")));
    }
}
