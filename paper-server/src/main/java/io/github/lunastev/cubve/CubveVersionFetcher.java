package io.github.lunastev.cubve;

import com.destroystokyo.paper.util.VersionFetcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.papermc.paper.ServerBuildInfo;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class CubveVersionFetcher implements VersionFetcher {
    public static final String PROJECT_URL = "https://github.com/LunaStev/Cubve";
    private static final String DOWNLOAD_URL = PROJECT_URL + "/releases";
    private static final Pattern RELEASE_TAG = Pattern.compile("cubve-([0-9A-Za-z][0-9A-Za-z._-]{0,63})-([1-9][0-9]*)");

    @Override
    public long getCacheTime() {
        return 720000;
    }

    @Override
    public Component getVersionMessage() {
        final ServerBuildInfo build = ServerBuildInfo.buildInfo();
        if (build.buildNumber().isEmpty() || build.gitCommit().isEmpty()) {
            return Component.text("Cubve development build. Update checks are available for released builds.", NamedTextColor.GRAY);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(
                "https://api.github.com/repos/LunaStev/Cubve/releases/latest"
            ).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Cubve/" + build.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE));
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return unavailable();
            }
            try (final InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                return describeRelease(new Gson().fromJson(reader, JsonObject.class), build.minecraftVersionId(), build.buildNumber().orElseThrow());
            }
        } catch (final IOException | RuntimeException exception) {
            return unavailable();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static Component describeRelease(final JsonObject release, final String minecraftVersion, final int patch) {
        if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) {
            return unavailable();
        }
        final var tag = RELEASE_TAG.matcher(release.get("tag_name").getAsString());
        if (!tag.matches()) {
            return unavailable();
        }
        final String latestMinecraft = tag.group(1);
        final int latestPatch = Integer.parseInt(tag.group(2));
        if (!latestMinecraft.equals(minecraftVersion)) {
            return Component.text("The latest Cubve release targets Minecraft " + latestMinecraft
                + "; this server runs Minecraft " + minecraftVersion + ". ", NamedTextColor.YELLOW).append(downloadLink());
        }
        if (latestPatch > patch) {
            return Component.text("Cubve patch " + latestPatch + " is available (running patch " + patch + "). ",
                NamedTextColor.YELLOW).append(downloadLink());
        }
        if (latestPatch < patch) {
            return Component.text("This build is newer than the latest published Cubve patch.", NamedTextColor.GRAY);
        }
        return Component.text("You are running the latest Cubve release for Minecraft " + minecraftVersion
            + " (patch " + patch + ").", NamedTextColor.GREEN);
    }

    private static Component unavailable() {
        return Component.text("Could not check Cubve updates. ", NamedTextColor.YELLOW).append(downloadLink());
    }

    private static Component downloadLink() {
        return Component.text("View Cubve releases", NamedTextColor.GOLD).clickEvent(ClickEvent.openUrl(DOWNLOAD_URL));
    }
}
