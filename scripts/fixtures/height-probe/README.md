# Disposable extended-height / WorldEdit probe

This plugin is only for isolated validation, never the production server artifact.
It requires WorldEdit 7.4.6 and the unchanged Extended World Height 4.0 (1024)
data pack. Both dependencies are public Modrinth downloads; record their exact
checksums. No production server paths, worlds or credentials are needed.

Compile `HeightProbe.java` against the mapped Cubve server, API, WorldEdit and
the server runtime dependencies using JDK 25. Package the compiled class and
`plugin.yml` at the JAR root. A JDK with a compiler module but no `javac` binary
can use `java -m jdk.compiler/com.sun.tools.javac.Main`. The same probe JAR must
be used on both sides of the comparison.

Run `scripts/run-extended-height-validation.py --help` for required paths.
With the Minecraft EULA already accepted for a disposable test server:

```sh
python3 scripts/run-extended-height-validation.py --accept-eula \
  --jar /path/to/cubve.jar --probe-jar /path/to/height-probe.jar \
  --datapack '/path/to/Extended World Height 4.0 (1024).zip' \
  --worldedit /path/to/worldedit-bukkit-7.4.6.jar --label candidate --pregen \
  --compare-summary /path/to/baseline/summary.json
```

The runner creates a new loopback world and never overwrites an existing fixture.
The normal world seed is 8142026, with one generation worker and a fixed 2 GiB
heap. It installs the exact dependency files, warms each 256-cell WorldEdit
plane-removal scenario ten times, and records twenty samples at Y=319, Y=1000
and Y=-900. Session creation, actual native adapter edits and session close
are timed; setup/reset, logging, validation and asynchronous follow-up work are
outside that timing. Per-thread allocated-byte counters observe the server
thread only. No mock or replacement WorldEdit algorithm is timed.

Regressions use actual WorldEdit full-block copying, undo and redo of a chest
with items, an enclosed water block near the expanded bottom, and a high light
source. Every active heightmap column is compared with an independent full
column scan. The actual chunk packet's 128 sections are decoded and all block
and biome cells compared. This does not simulate client rendering or a network
player, and the packet check disables Anti-Xray modification.

Forced target chunks must reach ENTITY_TICKING before their first save. Tests
verify saved blocks/biomes/heightmaps across a clean restart and optionally
compare the same saved chunk against a baseline. Saved palette encodings are
compared exactly; this fixture does not claim a general semantic equivalence
test across arbitrary palette reorderings. `--pregen` also exercises native
pause/resume/cancel, creates nine distant chunks and checks they were saved,
then pregenerates an already edited chunk and checks its contents survived.
Normal saving remains responsible for persistence after pregeneration completes.
