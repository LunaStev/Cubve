# Building-server diagnostics — 2026-10-19

## Finding and scope

Cubve has building-related optimization candidates, but the reported production spikes cannot be attributed yet. The user confirmed that WorldEdit is installed; its version, adapter, production world, logs and profiler recording were not available locally. The initial October 19 investigation ran native commands on an isolated creative server and inspected shared server paths. It did not run WorldEdit or FAWE and was not a before/after optimization comparison. No production code or default settings were changed during that diagnostic pass. The October 20 follow-up below retains the unchanged-section write optimization together with a native clone allocation guard. The combination improves the measured section method and keeps native clone allocation near the original level; it does not establish an original-to-final clone speedup or allocation reduction.

The diagnostic reproduced long ticks triggered by synchronous block edits, fresh-chunk requests, and explicit synchronous disk flushing. These paths need separate analysis, including their follow-up work. WorldEdit being installed does not establish that it caused an observed production spike.

## Local observations

Source: `f6c452416ae68923870fde69cda40b95312ad04b`, including the reviewed decorated-pot crash backport. Runnable JAR SHA-256: `7995360f455cfe4283ec59b48cd96553eb2abd2dae7a18c094f260be5ca1693b`.

Environment: Ryzen 5 2600X / 12 logical CPUs, Linux 7.2.11, OpenJDK 25.0.4.1, fixed 2 GiB heap, local JFR profile configuration, `-Dcubve.profile.ticks=true`. New normal world, seed `8142026`, creative/peaceful, time/weather advancement disabled, view distance 6 / simulation distance 4, loopback listener, zero players and installed plugins. Existing EULA authorization covered this disposable server. No builds or other agent benchmarks ran concurrently; this is still a shared development host, not an isolated production machine.

| Scenario | Observations | Longest `tickServer` work time in each window |
| --- | --- | ---: |
| Idle | One 10-second window, 202 ticks | 0.77 ms |
| Fill 32,768 loaded cells, glass/stone alternation | Five command windows, one >50 ms tick each | 57.47–64.29 ms |
| Repeat the same glass fill without changes | Three windows; the first is substantially slower than later ones | 12.25–49.42 ms |
| Clone 32,768 cells into loaded destination chunks | Five command windows, one >50 ms tick each | 51.73–64.53 ms |
| Force-load four requested fresh chunks | Three different locations, with dependency generation | 377.37–595.52 ms |
| First `save-all flush` | Includes the initial world and recently generated areas, as well as edited chunks | 1,752.39 ms |
| Later `save-all flush` commands | Two additional dirty-edit/flush windows | 641.05 / 846.93 ms |

These are observed long-tick durations, not command-only CPU times or estimates of production throughput. Each edit window includes command feedback, a console barrier, one second of follow-up time and another barrier. Console round trips also include queueing and tick scheduling; they must not be substituted for pure execution time. Fresh-chunk locations do not perform identical terrain work, and stage-event counts are not completed-chunk throughput. Command return does not guarantee that all postprocessing and lighting work has quiesced.

JFR sampled stacks supported the separation:

- Changed fill windows contained `FillCommand.fillBlocks`, `BlockInput.place`, `Level.setBlock` and `CollectingNeighborUpdater` paths. There were no GC pauses or chunk-generation stage events in these windows.
- Clone windows contained `CloneCommands.clone`, block writes and neighbor updates, again without GC pauses or generation stage events.
- Fresh-chunk windows contained synchronous chunk-loading and chunk-system work. The second long tick overlapped one approximately 46.36 ms `jdk.GCPhasePause`; the other two had no overlapping pause. GC alone does not explain the approximately 595.52 ms second tick.
- All three explicit flush windows sampled `UnixFileDispatcherImpl.force0 → FileChannel.force → RegionFile.flush → MoonriseRegionFileIO.flushRegionStorages` on the main thread. They had no GC pause. This identifies a disk-flush wait path in this test, not the storage hardware or cause of the user's production spikes.

The first flush includes more unsaved world data than later flushes. Do not combine these into equal-work repetitions or label them “32K block save time.” In particular, **explicit `save-all flush` is not normal incremental autosaving**.

After clean saving and restart, native commands checked the complete source/destination volumes for equality. A separate region-NBT read after shutdown verified all **65,536 expected stone cells** across both volumes. This is a persistence smoke check for these simple blocks, not WorldEdit undo, block-entity, redstone, lighting or client compatibility validation.

Compact evidence: [building-diagnostic.json](benchmarks/results/2026-10-19/building-diagnostic.json). Local raw evidence is in `run/benchmarks/building-20261019T145217Z-d874dd/`: `profile.jfr`, `restart.jfr`, console logs, `events.jsonl`, phase timestamps, a copy of the one-off Python fixture and Java streaming event reader, and the disposable world. No profiler recording was uploaded.

## Paths to investigate in the production trace

| Priority | Code evidence | Interpretation / next action |
| --- | --- | --- |
| 1 | `CraftWorld.getChunkAt` asks for a full chunk; `ServerChunkCache.syncLoad` schedules load/generation and waits through `managedBlock` / `join`. | An already generated building world can still incur synchronous disk loading. Identify the plugin caller and whether targets are loaded before considering a loading strategy. |
| 1 | `FillCommand.fillBlocks` walks the complete volume synchronously and performs another neighbor-update pass. `CloneCommands.clone` writes temporary barriers, final states and block entities in multiple passes. | Bulk block work can exceed the 50 ms tick budget even without mobs or plugins. A WorldEdit adapter may use a different path; inspect its actual frames rather than treating native-command measurements as its throughput. |
| 2 | `NewChunkHolder.saveChunk` creates `SerializableChunkData.copyOf` and runs the synchronous-save hook before queuing asynchronous serialization/write work. `ChunkHolderManager.autoSave` limits saved chunk count, not elapsed time. | Asynchronous disk writing does not remove all main-thread save cost. Match spikes to serialization, save hooks, region flushes, backup commands and any plugin-triggered saves. Current default is up to 24 autosaved chunks per tick with the inherited interval. |
| 2 | Allocation/GC can occur alongside loading or editing; WorldEdit history was not present in this fixture. | Match actual top-level `GCPhasePause` intervals to spike timestamps. Existing `ReadSurvivalJfr.gcMs` reports the whole GC event, which is not necessarily stop-the-world pause time. Do not change heap/GC flags based only on a high used-heap number. |
| 3 | `ServerLevel.sendBlockUpdated` compares collision shapes and visits navigating mobs when pathfinding updates are enabled. | Builds with active mobs may amplify the cost. Disabling this globally changes mob response to edited geometry; profile before changing behavior. |

Repeated block-change packets already use section-level deduplication/batching in `ChunkHolder`, and lighting work uses a changed-position set in `StarLightInterface`. A generic “one packet per block” diagnosis would be incorrect. No connected clients were present here, so packet fanout, network congestion and client rendering remain unmeasured.

## Narrow optimization candidates

- `CloneCommands.clone` builds a linked list of every selected source position even in NORMAL/FORCE mode; only MOVE reads it. Avoiding the unused list in copy modes is a contained allocation candidate. It affects native `/clone`; it is not evidence of a WorldEdit improvement. Validate normal/force/overlapping move, block-entity data, scheduled ticks, physics and persistence, and compare allocation/time before retaining it.
- `LevelChunkSection.setBlockState` updates air/fluid/random-tick counters even if the previous state equals the requested state. An early return after the palette mutation might skip redundant accounting while retaining its locking behavior. It needs counter/fluid/ticking and save regressions and an actual same-state workload comparison.
- `CraftBlock.setType` constructs a Bukkit BlockData object and immediately extracts its underlying state. A direct validated material-state mapping may reduce allocation for that API. Native commands and WorldEdit native adapters can bypass it, so measure the real caller first.

No candidate was applied during the initial diagnostic pass. The subsequent measured changes are described below. Skipping physics/lighting, changing command atomicity, disabling saves or changing world-generation threading is not justified by these results.

## Follow-up: unchanged section writes — 2026-10-20

`LevelChunkSection.setBlockState` now returns when the previous state is the identical requested state, **after** the existing checked/unchecked palette mutation. This retains palette locking, resizing and Anti-Xray behavior. It skips only the counter decrements/increments that would cancel each other; the existing Moonrise callback already returns on identical states. Changed-state writes retain their original bookkeeping.

The opt-in `ChunkSectionWriteBenchmark` invokes the actual implementation, visits all 4,096 existing cells per invocation and consumes each previous-state return. `@OperationsPerInvocation(4096)` normalizes the result to **one block write**, not a whole section. Trial setup is outside measurement, with no invocation setup or timed mocks. Same-state cases keep their contents; changed/mixed requests alternate each pass so the controls do not become accidental no-ops.

| Workload | Baseline ns/write | Candidate ns/write | Interpretation |
| --- | ---: | ---: | --- |
| Same stone state | 17.221 ±1.169 | 13.873 ±0.791 | 19.44% less measured method time |
| Same water state | 18.659 ±0.526 | 14.198 ±1.406 | 23.91% less measured method time |
| Same leaves state | 17.774 ±0.581 | 14.603 ±1.403 | 17.84% less measured method time |
| Every cell changes stone/glass | 17.302 ±1.425 | 17.588 ±0.794 | Overlapping confidence intervals; no established difference |
| One quarter changes, three quarters unchanged | 17.597 ±0.695 | 16.692 ±0.728 | Overlapping confidence intervals; no established difference |

Errors are JMH 99.9% confidence estimates. The three same-state intervals do not overlap. Both versions are effectively allocation-free on this path; tiny normalized GC-profiler floor values must not be advertised as allocation or GC savings. These measurements cover a hot section with a small palette and checked writes. They exclude chunk lookup, heightmaps, physics, lighting, networking and WorldEdit, and establish no server-wide TPS/MSPT improvement.

Method: JMH 1.37, OpenJDK 25.0.4.1, same Ryzen 5 2600X host, one thread, two forks, three one-second warm-up iterations and five one-second measurements per fork, fixed 1 GiB heap, identical Mockito-agent/native-access/JOML options and GC profiler. No builds or other agent benchmarks ran alongside measurements.

Ten actual-method regression invocations passed on both original and optimized code: checked/unchecked identity and changed writes, air/fluid/ticking/collision counts, every block/fluid cell, unique ticking positions and ordering, more than 256 palette states, actual `copy()` independence, and actual packet serialization/read identity preservation. Client/copy tests preserve their pre-existing tracking state instead of imposing server-section invariants on them.

Raw JMH inputs/results: [baseline](benchmarks/results/2026-10-20/section-write-baseline.json), [candidate](benchmarks/results/2026-10-20/section-write-candidate.json). Source hashes, comparison data and validation provenance: [building-comparison.json](benchmarks/results/2026-10-20/building-comparison.json).

```sh
./gradlew :paper-server:benchmark -PcubveBenchmarks -PcubveBenchmarkFilter='.*ChunkSectionWriteBenchmark.*' -PcubveBenchmarkLabel=section-write-current
# A label names output only; use original and optimized sources for an A/B comparison.
```

## Follow-up: section and native clone combination — 2026-10-20

The clone change guards only the three `clearBlocksList.addFirst/addLast` calls with `mode == MOVE`. No block-write, physics or ordering code changes. Original B contains neither change; section-only contains the section guard with the original clone implementation; C1 and C2 run the same combined JAR containing both changes. Runtime class hashes and bytecode distinguish these implementations and confirm that the test namespace invokes the real `CloneCommands` handler.

The separate `CloneCommandProbe` fixture measured actual synchronous native command dispatch on the server thread using `ThreadMXBean.getThreadAllocatedBytes` and `nanoTime`. Setup, destination resets, logging, source/callback construction and validation were outside measurement. Each invocation had to return exactly one successful native callback with the expected block count. No synthetic clone algorithm or timed mock was used. Per scenario, ten warm-up and twenty measured invocations ran in disposable frozen creative worlds, with a fixed 2 GiB heap and one generation worker, zero players, and only the test probe plugin. JFR was off; normal bundled-server behavior was otherwise retained.

| Scenario | Original B median bytes | Section-only median bytes | Combined C1 median bytes | Combined C2 median bytes |
| --- | ---: | ---: | ---: | ---: |
| Normal copy, 16,384 cells | 23,360,068 | 23,748,328 | 23,355,060 | 23,358,472 |
| Normal copy, 32,768 cells | 46,748,040 | 47,531,932 | 46,738,748 | 46,744,640 |
| Force copy, 32,768 cells | 46,784,044 | 47,538,168 | 46,753,264 | 46,753,024 |
| Strict copy, 32,768 cells | 7,932,368 | 8,714,824 | 7,948,804 | 7,935,760 |
| MOVE control, 16,384 cells | 45,512,344 | 45,493,004 | 45,540,804 | 45,502,232 |

Original B versus C1 alone did not establish a clone improvement: allocation sample ranges overlapped in every scenario. The section-only follow-up exposed an allocation increase in copy modes. Adding the clone guard to that section implementation reduced the copy medians by approximately **23.38–24.21 bytes per selected cell** in both combined runs. Relative to section-only, this is 1.64–1.67% less allocation for normal/force copies and 8.79–8.94% less for strict copies. Both combined runs' measured allocation ranges are wholly below section-only in all four copy scenarios; MOVE control ranges overlap, with median differences below 0.11%.

The two combined runs reproduce this incremental allocation difference with the same probe, JVM configuration, workload definitions and persisted results. They keep copy allocation near Original B: C2 copy medians differ by less than 0.07% from Original B, and their sample ranges overlap. **Both changes are retained as a tested combination. There is no established original-to-combined clone allocation reduction.** The implementation-level cause of the section-only allocation increase and its interaction with the clone guard is not established; do not attribute them to a particular compiler mechanism. Timing varies substantially across runs and in the MOVE control, so no clone CPU, server-wide TPS or production-spike improvement is claimed.

Original B, section-only and both combined runs passed native NORMAL/masked/filtered/force, overlapping MOVE in both directions, solid/non-solid ordering, overlapping block-entity moves with chest items/sign text/pot contents, copied pending torch ticks with preserved delay/priority, and clean saving/restart. Independent region decoding checked all 65,536 expected stone cells and the regression cells/NBT before and after restart. Their decoded persistence results are identical. Pending ticks were kept frozen; full redstone execution after unfreezing, lighting completion, player traffic and WorldEdit/FAWE behavior were not measured. Final build and validation provenance for the retained combination is recorded in [building-comparison.json](benchmarks/results/2026-10-20/building-comparison.json).

Final validation reapplied all 941 source patches, then passed `./gradlew check createPaperclipJar` in a separate invocation. The matching successful candidate test cache was reused; reports contain ten section-write, three decorated-pot and four terrain-fixture regression cases without failures. The Python suite passed all 64 tests. Every extracted server-JAR entry and every Paperclip entry matches the combined JAR tested in C1/C2; only the outer ZIP representation differs. Test/probe/benchmark classes are absent from the production server JAR. The local rebuilt executable is `run/benchmarks/building-optimization-20261020/retained.jar`, SHA-256 `54d7cd6ca1217ec99978f6b107916f6dea187ca5b14b4e2bc38728d3d542f941`.

The original A run predates the added non-solid/block-entity overlap regression scenarios; its numeric workload definitions and probe JAR are the same, but use Original B for matched persistence comparison. Original A/B allocation and timing variation is a further reason not to claim a clone improvement relative to the original implementation. Raw evidence: [original A](benchmarks/results/2026-10-20/clone-baseline-a.json), [original B](benchmarks/results/2026-10-20/clone-baseline-b.json), [section-only](benchmarks/results/2026-10-20/clone-section-only.json), [combined C1](benchmarks/results/2026-10-20/clone-combined-trial.json), [combined C2](benchmarks/results/2026-10-20/clone-combined-repeat.json).

The probe source and manifest live in `scripts/fixtures/building-probe/`; `scripts/run-clone-benchmark.py` creates unique loopback fixtures and never overwrites an existing world. Build the probe separately as described in its README and use exactly the same probe JAR for both versions:

```sh
python3 scripts/run-clone-benchmark.py --accept-eula --jar /path/to/original.jar --probe-jar /path/to/clone-probe.jar --label original
python3 scripts/run-clone-benchmark.py --accept-eula --jar /path/to/candidate.jar --probe-jar /path/to/clone-probe.jar --label candidate --compare-summary /path/to/original-fixture/summary.json
```

## Capture the next production spike

Cubve already bundles spark (`spark-paper:1.10.191` in this build). From an operator account, run:

```text
/spark tickmonitor --threshold-tick 100
/spark profiler start --timeout 600 --save-to-file
```

Continue normal building until a spike occurs. If it occurs before timeout, stop and retain the recording locally:

```text
/spark profiler stop --save-to-file
```

The timeout also saves locally because of the start option. Keep the output file path reported in chat/console, `latest.log` from the same period, the approximate spike time, the actual WorldEdit/FAWE version, launch flags, server version and whether editing, movement, backup or saving was happening. Run `/spark tickmonitor` again when finished to disable its messages. If a profiler is already active, inspect `/spark profiler info` before replacing that capture.

Start with a full recording so that waiting, queued tasks and normal-versus-slow periods can be compared. A second focused capture can use `--only-ticks-over 100`, as described in the [official lag-spike guide](https://spark.lucko.me/docs/guides/Finding-lag-spikes). Local saving and command options are documented in the [spark command reference](https://spark.lucko.me/docs/Command-Usage); both the start and stop local-save flags were also checked in the bundled JAR.

Existing Cubve per-tick JFR events measure `tickServer` only. `runAllTasksAtTickStart` executes before it; some scheduled/chunk work executes between ticks. The event is created at measurement end, so its timestamp is an end/commit time, not the work start. spark's start hook also follows the initial queued-task pass, although its tick-end duration starts at `currentTickStart`. A low average `/status` MSPT or an empty slow-tick sample does not rule out these other paths. The inherited `/paper syncloadinfo` is not a reliable substitute here: this checkout has no call sites feeding its `logSyncLoad` method.

The next optimization should follow the production trace: block editing/history, synchronous loading, snapshot/serialization, explicit flushing, or actual GC pauses. If the visible hitch has no matching long server tick, investigate client frame time and network latency separately.
