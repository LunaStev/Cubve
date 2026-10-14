# Initial optimization measurements — 2026-10-14

## Scope

This pass inspected plugin-facing inventory operations, biome writes, item metadata, entity queries and event dispatch in the current Paper 26.3 base. Source inspection identifies candidates, not the dominant bottleneck of a live server. No representative production world, plugin workload or spark trace was supplied, so the measured results below are API-level findings rather than a claim about overall TPS or MSPT.

Existing Cubve setup changes were retained. The base commit is `dfa0a06cf`; the performance changes were measured in the working tree. Source hashes and raw JMH results are in [benchmarks/results/2026-10-14](benchmarks/results/2026-10-14).

## Candidate decisions

| Path | Source evidence and decision |
| --- | --- |
| `CraftInventory.addItem` | When no partial stack is found, partial-stack and empty-slot searches each convert the same storage contents into an array and Bukkit mirrors. Reuse the array until the next write and remove an unnecessary clone used only for a read-only similarity check. Applied after actual implementation benchmarks. |
| `CraftWorld.setBiome(x, z, biome)` | The loop visits every block Y even though biome storage uses 4-block vertical cells. Write each affected cell once. Applied with tests comparing the full set of affected cells, including negative and unaligned heights. |
| `CraftMetaItem.getItemFlags` | Tried caching enum values and skipping the scan when tooltip data is absent. Allocation improved for flags present, but timing did not; the empty case regressed. Reverted the candidate. |
| `CraftWorld.getNearbyEntities` / entity ray tracing | Intermediate entity collections and Bukkit conversions may matter for plugins issuing frequent spatial queries. Moving predicates into the underlying query changes callback timing and iteration behavior; deferred until a real workload and equivalent behavior tests are available. |
| `PaperEventManager.callEvent` | Registered listeners already use the baked HandlerList array. Listener execution and call frequency need profiling; replacing the existing loop without evidence is not justified. |
| Moonrise chunk/entity paths | Potentially high impact under chunk generation, movement or entity loads, but already extensively optimized and coupled to world persistence. No speculative threading, save, event or activation-range changes were applied. |

## Inventory measurement

The benchmark calls the actual `CraftInventoryCustom.addItem` implementation on a 54-slot inventory filled with 64 cobblestone in every slot, attempting to insert 64 diamonds. This rejection workload is stable: it changes neither the inventory nor the input amount. Returned leftovers are consumed by JMH. No Mockito calls occur in the timed operation; existing test fixtures only initialize registries outside measurement.

| Metric | Baseline | Final | Reduction |
| --- | ---: | ---: | ---: |
| Mean time per call | 2,400.32 ns | 1,278.46 ns | 46.74% |
| JMH time error (99.9% confidence) | ±185.69 ns | ±149.58 ns | — |
| Allocated bytes per call | 8,540.02 B | 4,304.01 B | 49.60% |

The two time confidence intervals do not overlap in this run. The allocation reduction is also supported by a deterministic regression test verifying one storage snapshot per iteration rather than two when searching for an empty slot. These numbers are specific to the stated workload. Vanilla pickup code, all inventory sizes, successful insertion mixes and overall server TPS were not benchmarked.

The final implementation refreshes its snapshot after each mutation, retains storage boundaries for player inventories, and uses the virtual `setItem` path so packet/update behavior remains intact. Custom overrides of `firstEmpty()` are detected once per inventory class with ClassValue and retain their existing slot selection behavior. No inventory data is cached across calls.

## Biome-column work reduction

For the standard `[-64, 320)` vertical range, the former 2D biome setter dispatched 384 writes to the 3D setter. The new loop dispatches 96, covering the same 96 biome cells: **75% fewer writes, chunk checks/lookups, holder conversions and dirty-mark calls on this path**. Negative and partially covered height boundaries are included. Empty/inverted ranges preserve the former no-op behavior.

This is a verified operation-count reduction, not an elapsed-time benchmark of a live chunk or a server-wide speedup. The 3D biome API is unchanged, and the unloaded-chunk check and persistence dirty marking remain in the existing setter.

## Rejected item-flag trial

| Case | Baseline time | Candidate time | Baseline allocation | Candidate allocation |
| --- | ---: | ---: | ---: | ---: |
| No tooltip flags | 6.44 ±0.45 ns | 8.08 ±0.66 ns | 32 B | 32 B |
| Attributes hidden | 402.06 ±26.68 ns | 437.92 ±60.06 ns | 304 B | 248 B |

The common empty case already benefits from JIT optimization and showed no allocation gain. The attributes case did not demonstrate a time improvement despite fewer bytes allocated. The production ItemMeta implementation was restored to its original source. The harness remains available for future comparisons.

## Method and reproduction

- AMD Ryzen 5 2600X, x86_64, 12 logical CPUs, shared development host.
- OpenJDK 25.0.4.1, JMH 1.37, one benchmark thread, two isolated JVM forks.
- Three 1-second warm-up iterations and five 1-second measurement iterations per fork.
- Identical `-Xms1g -Xmx1g` flags, Mockito agent for registry fixtures, native access and `joml.nounsafe` settings in both versions; JMH GC profiler.
- Compilation and regression tests were not run concurrently with timed measurements.
- JMH and test fixtures are opt-in and are excluded from production artifacts.

```sh
./gradlew :paper-server:benchmark -PcubveBenchmarks -PcubveBenchmarkLabel=current
./gradlew :paper-server:benchmark -PcubveBenchmarks -PcubveBenchmarkLabel=inventory -PcubveBenchmarkFilter='.*InventoryBenchmark.*'
./gradlew check createPaperclipJar
```

A label names the output only. To reproduce a baseline comparison, run the same harness against pre-change inventory sources and then against final sources with identical inputs and JVM settings. Raw files contain the per-fork samples, profiler data and JVM flags.

## Regression coverage

Inventory tests cover full inventories and leftover identity, oversized insertion and input amount mutation, partial-stack priority, custom stack limits, distinct metadata, sequential inputs, overridden empty-slot selection and player equipment exclusion. Biome tests compare affected cell sets across eight height ranges. Command tests execute `/server` and verify that it emits only operating-system and JVM-heap information, while preserving permissions.

Final `./gradlew check createPaperclipJar` completed successfully. The 7 inventory regression cases, 8 biome boundary cases and 3 command tests all passed; existing API, server and Checkstyle checks also passed. The subsequent [plugin-free survival baseline](SURVIVAL-PERFORMANCE.md) validated basic generated-world saving/restart and recorded local JFR samples. A representative production workload and repeated before/after live measurements are still needed before claiming a server-wide improvement.

## Hopper block-container lookup follow-up

The survival trace identified `HopperBlockEntity.getContainerAt` among hopper samples. Its block-container helper checked the loaded-chunk map even for air, stone and other blocks that implement neither `WorldlyContainerHolder` nor block-entity storage. Those blocks cannot provide a block inventory. The helper now checks this eligibility first and returns null immediately for them. The caller still performs its existing entity-container search, with the existing occluding-block configuration.

There is no inventory cache across calls or ticks. Eligible block containers retain the loaded-chunk guard, virtual block-entity lookup, chest handling and search-event paths. Item-transfer code, cooldowns and configuration defaults are unchanged.

The opt-in `HopperLookupBenchmark` invokes the actual private helper through a constant MethodHandle. It rotates 32 positions in a real server concurrent loaded-chunk map, using real AIR/STONE states. Constructor-free, uninstrumented ServerLevel and ServerChunkCache fixtures initialize only the fields needed by this path; chunk values are non-null sentinels that are never accessed. This isolates lookup cost without timing Mockito, world construction or transfer code. It is not a complete world simulation.

| Block / hopper-can-load-chunks | Baseline | Candidate | Result |
| --- | ---: | ---: | --- |
| AIR / false (default) | 5.093 ±0.322ns | 2.227 ±0.102ns | 56.28% less helper time |
| STONE / false (default) | 4.837 ±0.177ns | 2.192 ±0.038ns | 54.68% less helper time |
| AIR / true | 2.316 ±0.102ns | 2.229 ±0.070ns | Overlapping confidence intervals |
| STONE / true | 2.307 ±0.085ns | 2.234 ±0.204ns | Overlapping confidence intervals |

Errors are JMH 99.9% confidence estimates, with the same two-fork, three-warm-up/five-measurement configuration as the inventory benchmarks. Both paths are effectively allocation-free. The default-setting helper improvement is clear in these runs, but the absolute saving is only about 3ns per lookup. A server-wide MSPT/TPS improvement has not been established; other hopper work is much larger.

Raw data and source hashes: [hopper-lookup-baseline.json](benchmarks/results/2026-10-14/hopper-lookup-baseline.json), [hopper-lookup-candidate.json](benchmarks/results/2026-10-14/hopper-lookup-candidate.json), [environment](benchmarks/results/2026-10-14/hopper-lookup-environment.json).

```sh
./gradlew :paper-server:benchmark -PcubveBenchmarks -PcubveBenchmarkLabel=hopper-current -PcubveBenchmarkFilter='.*HopperLookupBenchmark.*'
# With prior EULA acceptance, also validate native transfers after the load phases:
python3 scripts/run-survival-benchmark.py --accept-eula --validate-hoppers
```

Regression cases cover ordinary non-container blocks, loaded/unloaded real containers, chunk loading enabled, replacement block entities, composter-style containers without block entities, and minecart fallback with both occluding-block settings. Live validation results are recorded in [SURVIVAL-PERFORMANCE.md](SURVIVAL-PERFORMANCE.md).

The retained patch passed reapplication and `check createPaperclipJar`. All nine lookup regression cases and ten Python tests passed. A fresh plugin-free server verified double-chest transfers across a chunk boundary, redstone lock/resume, destination replacement, composter output, minecart collection and item-count conservation across clean saving/restart. The follow-up 1,024-hopper window recorded a 0.658ms mean and 1.276ms p99; this single window is validation data, not a measured server-wide improvement percentage.
