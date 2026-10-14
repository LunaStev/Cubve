# Plugin-free survival baseline — 2026-10-14

This is a diagnostic baseline for Cubve's current working tree, including the previously measured inventory and biome API changes. It is not a before/after server optimization comparison. No production world or plugins were needed: the runner generated a new normal survival world with seed `8142026` after explicit EULA acceptance.

## Environment and workload

- Minecraft 26.3, Paper base `cc7ee9aebdfc9e4acc3ef8f38c35944872fd1f87`, Cubve base commit `dfa0a06cf50e3bcea82488ef85970fbab75efc9c` plus working-tree changes.
- AMD Ryzen 5 2600X, 12 logical CPUs, Linux 7.2.9, OpenJDK 25.0.4.1. Shared development host; no simultaneous builds or benchmarks during measurement.
- Fixed 2 GiB JVM heap, default server configuration, view distance 6, simulation distance 4. Empty-server pausing disabled, daylight and weather advancement disabled.
- Loopback-only listener, no connected players, zero installed plugins. The inherited bundled spark profiler remains present. This run used local JFR without uploading data.
- 16 forced chunks at the fixture site. New-chunk phase forces a separate 4×4 chunk area at block coordinates 4096–4159; generation also visits dependency chunks.
- Hopper phase: a 32×32 grid at Y=120, facing east, each initially containing one stack of 64 cobblestone. Items move during warm-up and measurement; this is not a constant-throughput item source.
- Entity phase: remove the hopper grid and summon 256 persistent cows with `NoAI` and `NoGravity`. Normal entity ticking still runs; pathfinding and ordinary active mob AI are not exercised.
- 15-second warm-up before steady-state phases, approximately 30 seconds per phase. Scenarios run sequentially in one world/JVM, so results include evolving world state and JIT warm-up.

Raw summary and provenance: [survival-baseline.json](benchmarks/results/2026-10-14/survival-baseline.json). Tested runnable JAR SHA-256: `74adbf2dcbcc475001707a8306b6185debee04f6878b290fffb88b57224c26af`.

## Tick work time

| Scenario | Recorded ticks | Mean | p95 | p99 | Maximum | Ticks >50ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Empty server | 606 | 0.284ms | 0.467ms | 0.933ms | 1.757ms | 0 |
| Force-load 16 new chunks | 611 | 2.213ms | 1.801ms | 4.633ms | 856.783ms | 1 |
| 1,024 hoppers | 606 | 0.823ms | 1.166ms | 1.830ms | 18.484ms | 0 |
| 256 stationary cows | 606 | 1.432ms | 2.113ms | 2.836ms | 5.085ms | 0 |

The opt-in `cubve.ServerTick` JFR event records the existing `tickServer` work duration. It excludes inter-tick waiting and tasks processed outside that method; do not equate it with every profiler's MSPT definition. The profiling event is disabled by default through `cubve.profile.ticks`. Instrumentation is included in the measured run.

The new-chunk phase starts before the console command, which synchronously waits for loading. The 857ms outlier is part of that forced-loading operation. It is not evidence that normal player exploration routinely causes this stall. The lower p95 than mean is explained by this one outlier. There were 1,700 chunk-generation **stage** events, not 1,700 finished chunks. No GC events occurred within the four measurement windows; this does not establish low allocation or long-term GC behavior.

## Observed candidate paths

JFR execution samples count inclusive stack frames, so counts overlap and must not be summed as CPU percentages. Each window has only 21–74 main-thread samples; this is sufficient to locate broad paths, not rank tiny methods confidently.

| Load | Evidence | Candidate for further measurement |
| --- | --- | --- |
| New chunks | 370 execution samples across all threads; `ChunkUpgradeGenericStatusTask.run` appears in 298 and `NoiseBasedChunkGenerator.buildTerrain` in 220. | Terrain/noise/surface generation dominates worker samples. Compare generation cost separately from synchronous loading waits before changing the chunk scheduler. |
| Hoppers | 45 main-thread samples; `Level.tickBlockEntities` appears in 37, `HopperBlockEntity.tryMoveItems` in 22, and `getContainerAt` in 10. | Repeated source/destination container lookup and item-transfer checks. This is a useful contained next comparison; cache invalidation, inventory events and item conservation need validation. |
| Stationary entities | 74 main-thread samples; `EntityTickList.forEach` appears in 54, `Mob.tick` in 45 and `LivingEntity.tick` in 44. | Normal entity bookkeeping and base ticking. NoAI still runs these paths; it does not justify removing ticks or changing activation behavior for real mobs. |

At the time of this baseline, no new gameplay optimization had been applied from these samples alone. Hopper container lookup was selected for a focused follow-up; its microbenchmark results and retained early-return change are documented in [PERFORMANCE.md](PERFORMANCE.md). Chunk generation deserves a separate sustained exploration workload before larger architectural changes.

## Correctness and reproduction

`applyPatches` and `check createPaperclipJar` passed with the profiling patch. The disabled-by-default profiling regression test passed, as did the existing optimization and command checks. All eight Python script tests passed.

The live server accepted every fixture command, saved and stopped cleanly, restarted the same world, and successfully checked a saved hopper block at `(0, 120, 0)`. The restarted `/server` command emitted only OS and JVM heap information. This validates basic world/block persistence and command execution, not exhaustive entity/item data equivalence. Both temporary server processes stopped after the run.

```sh
./gradlew check createPaperclipJar
# After personally accepting Minecraft's EULA:
python3 scripts/run-survival-benchmark.py --accept-eula --seed 8142026 --seconds 30 --warmup 15
```

The runner creates a unique directory under `run/benchmarks/` and keeps the world, console logs, JFR and JSON there. This run's local fixture is `run/benchmarks/survival-20261014T063228Z-cf7047`. Worlds and JFR files are ignored by Git; the compact summary is retained in `benchmarks/results/`.

Repeat comparisons on the same hardware/JDK with no competing builds. For a survival-server performance claim, add connected-player exploration, movement, active mob AI, combat, longer saves and allocation/GC observation. The current baseline makes none of those claims.

## Hopper follow-up validation

The retained change skips the loaded-chunk map probe only for blocks that cannot expose a block container. It introduces no inventory cache. The actual helper microbenchmark showed a roughly 55% reduction for AIR/STONE under the default chunk-loading setting, saving about 3ns per lookup. See [PERFORMANCE.md](PERFORMANCE.md) for the measurement scope and controls.

The updated server passed `applyPatches`, `check createPaperclipJar`, nine hopper lookup regression cases and ten Python script tests. An initial live validation exposed ANSI color codes in native SNBT output; the parser was corrected and covered by a regression test. Console barriers also wait for actual server chat output, ignoring input echo. The successful fresh-world rerun used the same seed, heap, warm-up and phase durations as the original baseline.

| Follow-up scenario | Mean | p95 | p99 | Maximum |
| --- | ---: | ---: | ---: | ---: |
| Empty server | 0.235ms | 0.352ms | 0.556ms | 1.172ms |
| Force-load 16 new chunks | 2.406ms | 2.837ms | 5.072ms | 781.927ms |
| 1,024 hoppers | 0.658ms | 0.887ms | 1.276ms | 1.897ms |
| 256 stationary cows | 1.284ms | 1.677ms | 1.859ms | 2.265ms |

These are single diagnostic windows. Their differences from the original table do not establish a causal server-wide gain; the proven saving is the isolated helper path. No GC events occurred in the measured windows.

After measurement, native console commands verified:

- Transfer across a chunk boundary into a correctly formed double chest: 58 cobblestone in the hopper and 6 in the chest halves, total 64.
- Redstone locking preserved those counts; removing power resumed transfer, producing 52 + 12 = 64.
- Replacing the destination with a barrel was observed on subsequent transfers. A fresh 64-item input produced 59 + 5 = 64.
- A ready composter supplied one bone meal to its hopper.
- A chest minecart supplied cobblestone to a hopper, leaving 58 + 6 = 64.
- After clean saving and restart, the hopper/barrel pair still contained 48 + 16 = 64.

The game is briefly frozen while reading multiple inventories to prevent transfer between reads. All checks run outside the performance windows, without an installed test plugin. Native item counts use the fixture's plain item stacks. This does not exercise plugin event handlers or arbitrary nested item components.

Raw results: [survival-hopper-candidate.json](benchmarks/results/2026-10-14/survival-hopper-candidate.json). Tested JAR SHA-256: `72d8b89a440b543ec19e37e412745f53d57f5b53d7528024df223d1cf30f61c5`. World, logs and JFR remain locally in the ignored `run/benchmarks/survival-20261014T070427Z-2edfe4/` directory. Both server processes stopped cleanly.

```sh
python3 scripts/run-survival-benchmark.py --accept-eula --seconds 30 --warmup 15 --validate-hoppers
```
