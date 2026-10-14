# Cubve

Cubve is an independent Minecraft server based on Paper, with its own built-in commands, branding and planned optimizations. It currently targets Minecraft **26.3** and **Java 25**.

Paper and Bukkit API packages are retained for plugin compatibility. Cubve identifies itself as `cubve:cubve` and declares compatibility with Paper. Plugins using server internals still need testing against the target Minecraft version.

## Build

Clone the repository with Git and use JDK 25:

```sh
./gradlew applyPatches
./gradlew check createPaperclipJar
```

Download the runnable server from [Cubve releases](https://github.com/LunaStev/Cubve/releases). Release files use `cubve-<minecraft-version>-<patch>.jar`, for example `cubve-26.3-1.jar`; each release also includes `SHA256SUMS`.

```sh
java -jar cubve-26.3-1.jar --nogui
```

On a push to Cubve's `master`, CI applies patches, runs the maintenance/release tests and Gradle checks, then executes the exact distribution JAR with `--version`. Only after those jobs pass does it publish a release. Patches increment within each Minecraft version and start at 1 for a new version. PRs, other branches and manually dispatched validation builds do not publish.

Local development builds remain in `paper-server/build/libs/`. CI development artifacts are available from [Actions](https://github.com/LunaStev/Cubve/actions).

## Built-in commands

| Command | Purpose | Permission |
| --- | --- | --- |
| `/version` | Cubve version and published-release update status; plugin versions remain supported | `bukkit.command.version` |
| `/status` | TPS, MSPT, players, worlds and heap usage | `cubve.command.status` (operators) |
| `/server` | Operating system and JVM heap usage / maximum | `cubve.command.server` (everyone) |

The existing `/paper` administration commands remain available. All commands above are registered by the server itself.

## Server list

The multiplayer server list displays `???` instead of the online/max player count. Cubve omits the entire player section from modern status responses, including the hover sample of names and UUIDs. Legacy pings report unknown counts (`-1`) instead of real counts. This is built into the server and remains enforced after plugin ping listeners; MOTD, icon, version and cancellation still work normally.

The actual connection limit continues to use `max-players` in `server.properties`. Operator commands such as `/status` still show the real count. This hides server-list information only; it does not hide players from connected clients or separately enabled Query/RCON interfaces.

## Development and upstream

See [DEVELOPMENT.md](DEVELOPMENT.md) for local development, patch maintenance, the selective upstream policy and optimization validation.

Measured API optimizations are documented in [PERFORMANCE.md](PERFORMANCE.md). The plugin-free survival profiling baseline and its reproducible test runner are described in [SURVIVAL-PERFORMANCE.md](SURVIVAL-PERFORMANCE.md).

Cubve accepts complete Paper bases when upgrading Minecraft. Between upgrades, only reviewed security fixes, data-loss fixes and critical crash fixes are selected. General upstream changes are not automatically merged.

## API and publishing

The API publication is `io.github.lunastev.cubve:cubve-api`. No public Cubve Maven repository is configured yet. Local publication is available with `./gradlew :paper-api:publishToMavenLocal`.

An external Maven destination must be explicitly supplied with `-PcubveMavenUrl=<url>` and the Gradle credentials `cubveReleasesUsername` / `cubveReleasesPassword`.

## Credits and license

Cubve builds on [Paper](https://github.com/PaperMC/Paper), Bukkit, CraftBukkit, Spigot and their contributors. Original attribution and licenses are preserved. See [LICENSE.md](LICENSE.md) and [licenses/](licenses/).
