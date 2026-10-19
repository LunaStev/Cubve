# Disposable native clone probe

This fixture is a separate benchmark-only plugin, not part of the production
server or normal test plugin. Compile `CloneCommandProbe.java` with JDK 25 and
the server runtime classpath (including the mapped server, Bukkit/CraftBukkit,
Brigadier and their dependencies), then package its classes and `plugin.yml`
at the JAR root. Use the exact same probe JAR for baseline and candidate runs.

If the JDK has its compiler module but no `bin/javac` executable, invoke the same
compiler with `java -m jdk.compiler/com.sun.tools.javac.Main`, followed by normal
`javac` arguments (`-cp` for the server runtime classpath and `-d` for an isolated
class output directory). Use JDK 25's `java` for both compilation and the server.

Install only in a disposable, loopback test server. Prepare and load the source
and destination chunks before probing. Setup/reset commands, warm-up, lighting
quiescence, verification, saving and restart belong to the external runner.

The tracked runner provides native scenarios and saved-world regression checks:

```sh
python3 scripts/run-clone-benchmark.py --accept-eula --jar /path/to/baseline.jar --probe-jar /path/to/clone-probe.jar --label baseline
python3 scripts/run-clone-benchmark.py --accept-eula --jar /path/to/candidate.jar --probe-jar /path/to/clone-probe.jar --label candidate --compare-summary /path/to/baseline/summary.json
```

It defaults to ten warm-up invocations and twenty retained measurements per
scenario, one probe invocation per setup/reset. Repeat the baseline to check
measurement stability before interpreting candidate deltas. The frozen pending
redstone-tick fixture verifies copying/persistence; it does not exercise full
redstone behavior after ticking resumes.

From the console:

```text
cubvecloneprobe baseline-normal-001 1 0 120 0 31 151 31 64 120 0 replace normal
cubvecloneprobe baseline-force-001 1 0 120 0 31 151 31 64 120 0 replace force
cubvecloneprobe baseline-strict-001 1 0 120 0 31 151 31 64 120 0 strict replace normal
```

The command schedules one primary-thread task, leaving any outer command
execution context before calling the actual native command dispatcher. A native
result callback must report exactly one successful synchronous completion with a
positive affected-block count. Parse/execution failure or deferred work rejects
the measurement. The actual `CloneCommands` code, parsing, dispatch and ordinary
command bookkeeping are included; the fixture never mirrors the clone algorithm.

Each `CUBVE_CLONE_PROBE` line contains a JSON object with label, zero-based
iteration, dispatch/success, native result, allocated bytes on the server thread,
and elapsed nanoseconds. `CUBVE_CLONE_PROBE_DONE` marks completion. Treat
`CUBVE_CLONE_PROBE_ERROR` as a failed run, even if command acceptance returned true.
Result logging, source/callback construction and result arrays occur outside
measurement. Thread-allocation counter calls and timing calls have a small
constant overhead shared by both versions. Other-thread allocations and work
after command completion, including queued lighting and packet transmission,
are excluded. Samples are diagnostic measurements, not production TPS claims.

Labels must match `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`; use unique labels per
invocation. Repetitions can be 1 to 1,000. Repetitions do not reset the world;
prefer one repetition with externally matched setup when mode changes the
source, the source overlaps the destination, or side effects alter the fixture.
Warm up each scenario before recording samples and retain all raw samples.

Required regression scenarios for the unused-MOVE-list optimization: NORMAL and
FORCE, masked and filtered blocks, strict mode, overlapping MOVE in both offset
directions, solid/non-solid ordering, block-entity contents/components, scheduled
ticks, physics, and saved/restarted world equality. Test unavailable/unloaded
targets and overlap rejection separately; rejected commands are not valid timing
samples. Full-volume block/NBT checks must be independent of command return.
