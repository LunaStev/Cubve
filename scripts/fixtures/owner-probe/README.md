# Disposable ownership fixture

Build `OwnerProbe.java` and `plugin.yml` as a separate plugin with JDK 25 and the
current mapped server runtime classpath, using the compiler/jar module technique
described in `../building-probe/README.md` when necessary. Never install it on a
production server. The plugin is not part of the production artifact or normal
test plugin.

`scripts/run-owner-validation.py` prepares a new loopback world, grants the known
fixture UUID from the console, runs the probe, restarts and repeats the checks.
The second run stops the actual server through an owner command source. It also
checks failed owner removal of unknown UUIDs and actual removal/re-addition from
the console before the first run. Every invocation creates a distinct directory.

The probe uses synthetic Bukkit Player identities with level-four native and
wildcard Bukkit permissions, the real `CommandSourceStack`, real command
dispatcher/execution callbacks, actual ban/OP lists and actual owner storage.
It checks native/namespaced/execute/source-copy control denial, ordinary OP
moderation, owner controls, legacy controls, protected login and Bukkit bans.
JUnit separately checks native live-target handlers, IPs, mixed selectors and
both Bukkit kick entry points with mocked players/connections. This fixture has
zero connected players and does not test authentication, client command trees,
third-party moderation plugins or proxy forwarding. Real network disconnects
are outside its scope.

```sh
python3 scripts/run-owner-validation.py --accept-eula --jar /path/to/cubve.jar --probe-jar /path/to/owner-probe.jar
```
