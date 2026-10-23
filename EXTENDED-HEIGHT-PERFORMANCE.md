# Extended-height building and pregeneration — 2026-10-23

## Exact workload

The public [Extended World Height 4.0 (1024) data pack](https://modrinth.com/datapack/extended-wold-height-1024/version/9FO9wrCO)
sets the tested Overworld to **[-1024, 1024)**: 2,048 blocks / 128 sections,
not merely a 1,024-block-tall column above the normal bottom. The exact downloaded
ZIP is used unchanged. Its SHA-256 is
`b0e961cad43e515bc094af9db28c6aca0e7499cf09b88c403378327136532e08`.

The real plugin is [WorldEdit 7.4.6](https://modrinth.com/plugin/worldedit/version/YFnqiUlL)
(`7.4.6+7648-00664b0`), SHA-256
`e0ce67d870ad1fef8a40dd9d7926360caf3bb44722438545d6b20b55f82a0c91`.
Its v26_3 native adapter calls the real `LevelChunk.setBlockState` path, including
heightmap updates. Tests do not substitute native `/fill` for WorldEdit.

No production world, server installation or credentials are used. Disposable
servers bind to loopback, use seed 8142026, one generation worker, a fixed 2 GiB
heap and zero connected players. They install only WorldEdit and the separate
test probe. The actual data-pack height is checked by the probe on both boots.

## Downward heightmap searches

Removing the highest contributing block can search downward through long air
gaps. `Heightmap.update` now skips a completely air-filled section at a section
boundary. Partial sections and nonempty sections retain the original per-block
scan. If the heightmap predicate accepts AIR, the original scan also remains
in use; this matters for custom block-tag definitions.

The actual-method JMH fixture restores each map's top and measures removal
against real ProtoChunk section storage. It performs 256 columns per invocation,
normalizing to one restore/remove pair. There are no timed mocks, world loads,
block writes, lighting or network operations in this fixture.

| Workload | Baseline ns/pair | Candidate ns/pair | Less method time |
| --- | ---: | ---: | ---: |
| Normal range, Y=319 down to floor Y=64 | 1,349.98 ±223.57 | 439.85 ±31.99 | 67.42% |
| Extended range, Y=1000 down to floor Y=64 | 4,254.92 ±593.05 | 636.76 ±34.23 | 85.03% |
| Extended range, Y=1000 down to floor Y=-1000 | 11,205.52 ±3,863.43 | 1,165.15 ±218.93 | 89.60% |
| Extended dense immediate-hit control | 29.44 ±4.32 | 31.75 ±1.85 | Intervals overlap |

Errors are JMH 99.9% confidence estimates. The gap-case intervals do not overlap;
the dense control establishes no difference. These are method measurements,
not WorldEdit-wide or server TPS percentages. Twelve parameterized regression
invocations cover all six heightmap predicates, randomized mixed blocks/fluids,
negative and unaligned bottoms, empty and occupied neighbour sections, complete
removal, and an air-accepting predicate against an independent column scan.

## Empty section allocation

Each section has a ShortList for randomly ticking block positions. Its hash
index formerly existed even when the section never had any ticking blocks.
The index is now allocated on the first insertion or positive capacity hint.
Empty remove/clear calls do not allocate it. Once allocated it is retained for
reuse, preserving existing dense-list behavior, tail-swap removal and ordering.

`EmptyChunkBenchmark` constructs and returns actual fresh ProtoChunks, including
all section palettes, tracking structures and light arrays. Registry construction
is outside timing. JMH allocation covers the entire empty-chunk constructor,
not a full generated chunk, a disk load or total resident server heap.

| Range / sections | Baseline B/chunk | Candidate B/chunk | Allocation reduction |
| --- | ---: | ---: | ---: |
| Normal, 24 sections | 15,832 | 13,144 | 16.98% / 2,688 B |
| Extended, 128 sections | 77,400 | 63,064 | 18.52% / 14,336 B |

Empty-chunk construction measured 2.399 ±0.234 → 2.098 ±0.092 microseconds for
24 sections and 13.533 ±1.605 → 10.712 ±0.513 microseconds for 128 sections.
These confidence intervals do not overlap. Generated terrain contains some
sections with ticking blocks, which do need their index. Do not extrapolate
the empty-chunk byte saving into an identical whole-server heap percentage.

The first lazy-index trial increased the hot remove/add pair from 28.348 ±0.626
to 29.687 ±0.701 ns. The final implementation keeps a local index reference
on its hot path and initializes through a cold branch; a subsequent same-harness
control measured 28.959 ±0.910 ns/pair. Its interval overlaps the baseline,
so no hot-list time improvement or regression is established for the retained
implementation. The empty-chunk constructor is unchanged between these two
lazy-index versions; the byte measurements above exercise that constructor.

Two dedicated ShortList tests exercise 30,000 deterministic random operations,
duplicates, tail swaps, capacity hints, clearing/reuse, all 4,096 section-local
positions and signed-short boundary values. Existing checked/unchecked section
write and palette/copy/packet regressions also remain required.

## Actual WorldEdit comparison and persistence

Each case removes a 16×16 stone plane through an actual WorldEdit EditSession,
including session construction, native edits and session close. The plane is
restored outside timing, with ten warm-ups and twenty retained samples per
height. Case order and input are identical. These are individual shared-host
fixture medians, not a production TPS or latency-distribution claim.

| Plane Y | Baseline median ms | Retained median ms | Observation |
| --- | ---: | ---: | --- |
| 319 | 6.619 | 2.824 | 57.33% lower observed median |
| 1000 | 16.278 | 2.252 | 86.16% lower observed median |
| -900 | 0.907 | 0.874 | Small difference; no established improvement |

Do not compare heights against one another: JIT warm-up and the different gap
lengths affect each case. Edit-session allocation varies between cases and
compiled code; no general WorldEdit allocation/GC reduction is claimed. The
confirmed allocation finding is the empty-chunk constructor comparison above.

Both retained runs used the same probe, data-pack ZIP and WorldEdit JAR. Full
block/biome section containers, all saved heightmaps and chest contents in the
edited chunk had the identical SHA-256 fingerprint before and after restart,
and between baseline and retained JAR:
`dbd85ea1d973f0da64aca0914d0417385abdbc2659ef7a10a274cde243ec8d1f`.
Actual full-block chest copying, undo/redo, an enclosed water block at Y=-901,
a sea lantern at Y=1002, adjacent block light 14, independent heightmap scans
and decoding every block/biome cell from all 128 packet sections passed.

The native pregenerator completed nine new distant chunks, paused/resumed and
cancelled correctly, then processed an already edited chunk with its contents
preserved. All nine distant chunks were present with FULL status after saving
and after restart. The final `check createPaperclipJar`, all 25 new Java test
invocations and all 69 Python maintenance/measurement-integrity tests passed.
The validated development JAR SHA-256 is
`6c9439554922fe71a6f973f817eab7762d2645bb7eecc4c36c53c3001a2b9430`.

Two failed preliminary attempts are excluded and recorded in `comparison.json`.
One encountered a native global chunk-debug dump race while generation changed
its neighbour graph; the probe now checks only the target chunks' FULL status,
ENTITY_TICKING promotion and pending postprocessing directly before saving.
The same revised probe was rerun on both JARs. A second attempt exposed an
incorrect admission counter in the initial pregenerator: the total holder count
included generation dependencies. The retained code uses the actual full-chunk
count, with a regression exercising a large ProtoChunk count and small full
count. Failed fixtures saved/stopped normally and are not reused.

## Resource-limited native pregeneration

From the console or a Cubve owner account:

```text
pregen start world 0 0 512
pregen status
pregen pause
pregen resume
pregen cancel
```

The centre and radius are block coordinates. The job covers the square of
intersecting chunks; the example spans 65×65 chunks. An optional last argument
sets the maximum request rate from 1 to 4 chunks/second; the default is 1.
There is one job per server, at most one pending request, and at most 1,000,000
requested chunks per job. The area must fit inside the current world border.
Only console/RCON and UUID-based Cubve owners can control the command, including
through `execute`; ordinary OP/wildcard permissions cannot bypass ownership.

The implementation uses Moonrise's existing asynchronous chunk scheduler at
LOW priority. It does not join a generation future on the server thread or
retain a list of all target coordinates/chunks. Coordinates are generated from
a cursor in constant space. Temporary scheduler tickets are released through
the scheduler callback's existing `finally` block. Completed chunks follow
normal unload/save policy; existing terrain is loaded rather than regenerated.

Admission stops when the current tick has already used 35 ms, the average tick
time is at least 40 ms, used JVM heap reaches 85% of maximum, or the world's
loaded full-chunk count exceeds its start count by more than 256. These are
best-effort admission checks: an already running request and its neighbour
dependencies can finish, and one requested chunk can involve several generation
tasks/workers. This is not a hard CPU, heap or tick-latency guarantee.

Pause/cancel stop new requests; an existing request drains normally. A new job
cannot replace an active or draining job. Active generation keeps an otherwise
empty server awake. Pausing permits normal empty-server sleeping after pending
work drains. Progress is in memory and does not automatically resume after a
server restart. A completed job means requested chunks reached FULL, with normal
postprocessing promotion and saving still applicable. It does not imply an
explicit disk flush or keep those chunks permanently loaded.

Pregeneration moves terrain generation work ahead of later visits. It does not
remove disk reads, packet transfer, client rendering or WorldEdit's synchronous
editing cost. Nine coordinator tests cover request/rate bounds, no catch-up
bursts, negative-coordinate coverage, pause/resume, cancellation, duplicate
callbacks and failures. Live tests additionally require saved generated chunks
and unchanged edited contents when visiting an already generated chunk.

## Measurement method and reproduction

JMH 1.37, OpenJDK 25.0.4.1, Ryzen 5 2600X shared host, one benchmark thread,
two JVM forks, three 1-second warm-ups and five 1-second measurements per fork,
fixed 1 GiB heap, identical agent/native-access/JOML flags and GC profiler.
Builds, Java tests and live-server measurements are run sequentially. A label
only names output: source implementations must be changed explicitly for A/B.
Benchmarks and test probes are excluded from production artifacts.

```sh
./gradlew :paper-server:benchmark -PcubveBenchmarks \
  '-PcubveBenchmarkFilter=.*HeightmapRemovalBenchmark.*' \
  -PcubveBenchmarkLabel=height-current
./gradlew :paper-server:benchmark -PcubveBenchmarks \
  '-PcubveBenchmarkFilter=.*(EmptyChunk|TickingList)Benchmark.*' \
  -PcubveBenchmarkLabel=memory-current
./gradlew check createPaperclipJar
```

The actual WorldEdit runner and its measurement boundaries are documented in
[scripts/fixtures/height-probe/README.md](scripts/fixtures/height-probe/README.md).
Its saved-world and packet checks cover one edited chunk, with surrounding
chunks held through native generation postprocessing. They do not prove arbitrary
plugin compatibility or client rendering performance.

Raw JMH inputs/results and validation provenance are retained under
[benchmarks/results/2026-10-23/extended-height](benchmarks/results/2026-10-23/extended-height).
