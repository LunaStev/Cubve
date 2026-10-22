# Cubve owners

`/op` remains the Minecraft operator command. Cubve adds a separate, persistent owner identity above ordinary operators. Owners are identified by UUID in `owners.json`, independently of `ops.json`; an OP permission level of 4 or a wildcard permission does not make someone an owner.

## Setup

Use the server console (or authenticated RCON), without the leading slash:

```text
owner add YourOnlinePlayerName
owner list
owner remove 11111111-1111-4111-8111-111111111111
```

`add` accepts an online player's exact name or a complete UUID. Use the UUID reported by `owner list` for removal, including offline owners. Names are descriptive only: renaming a Minecraft account does not change its authority. No player, including an existing owner, can execute owner-management commands. Console access is the recovery path even when the last owner is removed.

The initial owner list is empty. Existing operators retain their OP status but lose the owner-only controls below immediately; designate your owner from the console after upgrading. Adding/removing an online owner refreshes their permissions and command list. Ownership survives saving and restart. Removal falls back to the player's existing entry in `ops.json`, if any; it does not erase a separately granted OP.

## Capabilities

| Operation | Ordinary player | Ordinary OP | Owner | Console / RCON |
| --- | --- | --- | --- | --- |
| Ordinary gameplay and permitted commands | Existing rules | Existing OP capabilities | Full OP capabilities | Yes |
| Native ban/kick of ordinary players | Existing permission rules | Yes | Yes | Yes |
| Ban/kick an owner | No | No | No | Remove ownership first |
| Grant/revoke ordinary OP | No | No | Yes | Yes |
| Stop/restart/reload and protected controls | No | No | Yes | Yes |
| Add/remove/list owners | No | No | No | Yes |

Owner-only native commands are `stop`, `op`, `deop`, `reload`, `save-off`, `save-on`, `datapack` and `schedule`. The inherited `restart`, `spigot reload`, `paper reload` and `bukkit:reload` controls also require ownership. Other permissions still apply; OP gameplay commands and diagnostics remain available. Restricting datapacks and scheduling prevents an ordinary OP from activating functions that later execute with the server's authority. This is a bounded command policy, not a promise that every disruptive action available to an operator is blocked.

Native permission checks and execution checks enforce these restrictions independently of the existing vanilla/Bukkit permission fallback. Namespaced commands and ordinary aliases use the same handlers. Native command-source copies retain the original caller through `execute`, source changes and immediate function execution; selecting an owner as the execution entity cannot acquire ownership. Native console-originated unattended commands retain console authority. Legacy wrappers presented with an anonymous sender remain denied. Command blocks do not acquire owner status.

Native ban/kick validates all targets before modifying any of them: if a selector includes an owner, the entire action is rejected. An IP ban sharing an online owner's address is rejected before writing or disconnecting anyone. Native forced transfer cannot be used by an ordinary OP to evict an owner. An owner can still transfer normally under owner/console authority. Owners bypass native UUID/IP login bans, including IP bans recorded while the owner was offline; other players' bans remain effective. Whitelist/operator behavior and other login checks remain in place.

The Bukkit profile-ban, online-owner IP-ban and player-kick APIs also reject protected targets, so ordinary plugin wrappers around these APIs cannot bypass protection. These rejected API operations throw `IllegalArgumentException`; plugins should handle that result. Disconnects caused by network errors, protocol validation, duplicate logins, server shutdown or a plugin using internal server APIs are outside this moderation policy. Ownership is not combat, teleport or general world-edit immunity.

## Storage and trust

Changes replace `owners.json` atomically and update in-memory authority only after saving succeeds. A failed write grants/revokes nothing. An unreadable or malformed existing owner file stops startup instead of silently clearing ownership. Back up this file with the rest of the server configuration; edit it only while the server is stopped. No real owner account is selected automatically by the build.

Ownership relies on the UUID identity authenticated by the server. It adds no authentication to an offline-mode server. Use authenticated player identities, including correctly configured authenticated proxy forwarding when applicable. Console/RCON credentials, installed plugins and installed datapacks remain trusted: code running inside the server can directly invoke privileged APIs, and plugin commands deliberately executing as console carry console authority. This feature separates player/OP authority; it does not sandbox plugin code.

On 2026-10-22, all 40 new ownership regression cases passed, alongside the full Gradle checks and 64 Python maintenance tests. They cover UUID persistence, corrupt files, failed writes, vanilla level-4/wildcard bypass, proxies, source/entity copies, native moderation/control handlers, shared IPs, whitelist verification, login bans and Bukkit moderation APIs.

Two boots of a disposable loopback server each passed 38 probe assertions with the whitelist enabled. The console added/removed/re-added the fixture owner, the restart retained ownership, and the second run actually stopped the server through an owner command source. The final executable exactly matches the tested JAR. Runtime senders are synthetic, with zero connected players; actual authentication, client command trees, third-party moderation plugins and proxy forwarding were not tested. See [the fixture](scripts/fixtures/owner-probe/README.md) and [validation provenance](validation/results/2026-10-22/owners.json).
