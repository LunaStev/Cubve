# Chunk generation measurement — 2026-10-14–15

The retained Aquifer pressure-cache change reduces allocated bytes per fresh-chunk benchmark fixture by **46.2–57.5%** across three measured Overworld coordinates. Fill-time confidence intervals overlap, so these measurements establish an allocation reduction, not a CPU-time, TPS or exploration-throughput improvement. Under the final controlled single-worker fixture, all 400 saved full chunks across two seeds matched the baseline, including 80 generated after restart. Repeating the unchanged baseline JAR also matched every chunk under both seeds.

## Change and scope

`Aquifer.NoiseBasedAquifer.computeSubstance` formerly created a `MutableDouble` for pressure-noise caching on each block that reached the pressure-comparison path. The retained change stores the cached value in one primitive `double` field on the Aquifer instance and resets it to `Double.NaN` at the same point where the temporary holder was created. The production source change is confined to Aquifer; the measured section-loop trial was restored.

The three pressure comparisons retain their order, arithmetic and lazy noise sampling. `Double.isNaN` remains the cache test, including the existing behavior of resampling when the noise sampler itself returns NaN. Each NoiseChunk owns its Aquifer, and the native terrain and carving paths use it sequentially. This relies on the existing native usage; it does not add support for concurrent or reentrant use of one Aquifer by custom NMS samplers.

The change does not alter seeds, noise parameters, float interpolation, generation scheduling, section writes or fluid-update decisions. Existing Moonrise density-buffer and Perlin optimizations remain in place. Moving between point and volume sampling, or skipping density branches, was excluded because their floating-point evaluation and cache behavior require separate equivalence analysis.

## Environment and method

- Minecraft 26.3; pre-change Cubve commit `0df19dfb2f13d18476df43d5ca3aee55bcf4e06a` and published Cubve 26.3-2 as the live baseline.
- JMH seed `8142026`; live seeds `8142026` and `-8675309`. AMD Ryzen 5 2600X, Linux 7.2.9, OpenJDK 25.0.4.1, JMH 1.37.
- Shared development host. Background load varied and the machine was not otherwise isolated, so elapsed-time results are diagnostic. Build, Java-test and generation sessions were sequenced, but small script-maintenance checks also occurred during the investigation. Reproduction should exclude competing builds and benchmarks.
- JMH: one benchmark thread, two isolated JVM forks, three 1-second warm-up iterations and five 1-second measurement iterations per fork; `-Xms1g -Xmx1g` and the GC profiler. Registry-fixture agent/native-access settings match between implementations.
- Live fixture: fixed 2 GiB heap, loopback listener, no connected players or installed plugins, frozen ticks. JFR was disabled. The final comparison explicitly uses one generation worker in the temporary fixture only; production worker defaults remain unchanged.

`ChunkFillBenchmark` invokes the actual private `NoiseBasedChunkGenerator.doFill` method through a MethodHandle. It uses real Minecraft registries, noise sampling, Aquifer calculations, section palettes, both world-generation heightmaps and fluid postprocessing. Registry and RandomState initialization happen outside measurement. Every invocation creates a fresh ProtoChunk and NoiseChunk so previous writes cannot turn later fills into no-op overwrites.

JMH's reported time covers `doFill` only. Its GC profiler also observes invocation setup and teardown, so **bytes/op cover the fresh fixture as a whole**, including ProtoChunk and NoiseChunk creation; they are not allocations made solely inside `doFill`. Surface rules, carvers, biome population, structure blending, lighting, scheduling and serialization are outside this microbenchmark.

## Allocation result

| Chunk coordinate | Baseline bytes/op | Primitive-cache bytes/op | Reduction |
| --- | ---: | ---: | ---: |
| `(0, 0)` | 106,267.90 ±119.13 | 57,145.88 ±119.28 | 46.22% |
| `(64, -32)` | 165,438.56 ±23.76 | 70,334.89 ±22.30 | 57.49% |
| `(-65, 33)` | 131,597.36 ±27.26 | 65,436.58 ±26.40 | 50.28% |

The allocation intervals are clearly separated in all three cases. The number of blocks that reach the pressure path depends on local terrain, which explains the different saving per coordinate. This is not a claim that every chunk, generation stage or full server workload allocates 46–57% less memory.

| Chunk coordinate | Baseline fill time | Primitive-cache fill time |
| --- | ---: | ---: |
| `(0, 0)` | 6.62 ±0.94 ms | 6.52 ±1.21 ms |
| `(64, -32)` | 7.24 ±0.43 ms | 7.33 ±0.70 ms |
| `(-65, 33)` | 6.42 ±0.32 ms | 6.38 ±0.26 ms |

Errors are JMH's 99.9% confidence estimates. All time intervals overlap. There is no demonstrated fill-time improvement or regression in these runs, and reduced allocation alone does not establish lower long-term GC pauses.

Raw results: [baseline](benchmarks/results/2026-10-14/chunk-generation/chunk-fill-baseline.json), [primitive cache](benchmarks/results/2026-10-14/chunk-generation/chunk-fill-aquifer-primitive.json), [environment](benchmarks/results/2026-10-14/chunk-generation/environment.json).

## Other measured candidates

| Candidate | Result and decision |
| --- | --- |
| Fill 16-height section spans while preserving Z → X → descending Y order | Reduced explicit section lookups but produced overlapping time intervals at all three coordinates and no consistent allocation saving. Restored the original fill loop. |
| Reuse one MutableDouble per Aquifer | Demonstrated a similar allocation reduction, but retained an unnecessary object reference and provided no distinct time benefit. Superseded by the primitive-cache candidate. |

The section trial measured 6.66 ±0.77, 7.32 ±0.49 and 6.30 ±0.23 ms at the same three coordinates. The holder trial measured 6.63 ±1.69, 7.48 ±0.42 and 6.36 ±0.25 ms. All overlap the corresponding baseline intervals. These experiments are preserved for review rather than shipped together with the primitive-cache change.

Raw trials: [section spans](benchmarks/results/2026-10-14/chunk-generation/chunk-fill-section-candidate.json), [reused holder](benchmarks/results/2026-10-14/chunk-generation/chunk-fill-aquifer-holder.json).

## Terrain and persistence checks

The fill fixture records 18 unique golden hashes from the unmodified production implementation: three coordinates in each of Overworld, Nether and End, plus those coordinates under three additional clamped Overworld height ranges. The extra ranges are `[-63, 317)`, `[-32, 160)` and `[0, 128)`, covering negative Y and partial section boundaries. The standard `[-64, 320)` case is repeated in the height test and reuses its original golden hash.

Each fill hash includes every block-state cell, `OCEAN_FLOOR_WG`, `WORLD_SURFACE_WG` and the fluid-postprocessing offsets. Four Java fixture tests verify fresh empty state, repeatability, baseline equivalence and clamped-height behavior. This fixture validates filling only; the full-server run below also covers Overworld biome population, surfaces, carvers and structures. Full-server Nether and End generation are outside that live comparison.

The live runner creates a new isolated normal world and forces four separate 8×8 areas, after warming up with two other 8×8 areas. Measured force-load tickets remain in place through generation postprocessing and the first completed save. Outside the timed batches, the runner polls `paper debug chunks` for the exact requested coordinates and requires every target to reach `ENTITY_TICKING` before its first flushed save. That status follows the callback that completes generation postprocessing; a FULL chunk alone is insufficient. The readiness wait is bounded at 120 seconds. The runner then flushes the save, checks that the saved targets have no pending postprocessing, removes its force-load tickets, verifies the native query reports zero, saves that removal and stops cleanly. It preserves a complete copy of the closed world before fingerprinting and restarting the same world. After freezing ticks, verifying no forced tickets were restored and reloading the original areas, it generates another fresh 8×8 area, applies the same readiness/save guards and stops again. The comparison covers **320 requested full chunks**, including the 64 generated after restart.

Saved-terrain hashing decodes all block-state and biome cells rather than comparing palette encoding or raw region files. It includes heightmaps, structures, generated block entities and loot seeds, status, chunk coordinates, blending and retrogen/upgrade data. Compound keys and structure-reference order are normalized. LastUpdate, InhabitedTime, lighting, entities, scheduled ticks, live-server PostProcessing, DataVersion, loading-only `keepPacked` markers and Paper persistent data are outside this comparison. The fill golden hashes separately cover fluid-postprocessing decisions.

The reader handles Minecraft 26.3 heterogeneous-list wrappers by unwrapping each list element exactly once. It requires all 24 normal Overworld terrain sections, Y=-4 through 19, with both block and biome containers, and rejects missing or duplicated sections. The runner has 38 Python regression tests covering this format, NBT/Anvil reading, modified UTF-8, compression, negative coordinates, modern dimension-directory selection, semantic palette hashing, metadata exclusions, matching worker and completion policies, native readiness and ticket-removal sequencing, restart comparisons, fresh-fixture isolation, console completion barriers, EULA handling and process cleanup.

## Repeated live controls

The first runs used the automatic worker policy, which selected three workers on this host. A candidate comparison found feature differences in 77 of 320 chunks. Repeating the **exact unchanged baseline JAR** under that policy also produced differences in 76 of 320 chunks. These were real decoded block differences rather than palette ordering or NBT wrappers; observed examples included snow, leaves, kelp and feature-placed stones/ores. The inspected candidate comparison retained matching heightmaps, biomes, structures and block entities.

This establishes that the uncontrolled baseline was not repeatable in this workload. It does not identify every cause of those differences or prove that each candidate difference came from scheduling. A plausible code-level explanation is the FEATURES stage: it writes into neighboring chunks, and features inspect existing blocks before placing or replacing them. Moonrise serializes overlapping write regions, while the order in which ready feature tasks enter that queue can depend on completion of neighboring TERRAIN tasks. Feature RNGs are still reseeded from the world seed, origin, feature index and step. The production scheduler and seeds were not changed for this optimization.

Those default-worker runs are excluded from the optimization comparison. Their world persistence checks remain useful, but their throughput differences cannot establish a gain. The fixture now explicitly sets `chunk-system.worker-threads: 1` in its own temporary configuration, records the policy and requires matching policies when comparing reports. A repeated baseline first confirmed strict same-seed equivalence under that control; the actual candidate was then compared against it. A single worker is a tested control for this fixture, not a general guarantee that every native generation workload is deterministic.

| Single-worker live run | Requested measured chunks | Requested chunks/s | Save/restart | Same-seed comparison |
| --- | ---: | ---: | --- | --- |
| Cubve 26.3-2 baseline | 256 | 16.68 | Passed; another 64 chunks generated after restart | Reference: 320 hashes |
| Exact baseline JAR repeated | 256 | 16.47 | Passed; another 64 chunks generated after restart | 320/320 identical |
| Actual primitive-cache JAR | 256 | 18.52 | Passed; another 64 chunks generated after restart | 320/320 identical |

The two identical-JAR baselines spent 15.35 and 15.55 seconds generating the 256 requested chunks; the candidate spent 13.83 seconds. These are individual runs on a shared host, without enough repetitions to estimate a throughput distribution. The higher observed candidate rate is a diagnostic result and does not establish a CPU, TPS or exploration-throughput improvement. The confirmed performance finding remains the JMH allocation reduction.

Baseline JAR SHA-256: `7b4ce7080c155f3f8272e1479fd97deceb6d6b40c929e164bfb032ca2c6aa4f3`. Validated candidate JAR SHA-256: `b44c15585f7e904992fbb9ffbfb9e3b90653d9409d72804d8d47f9aa96e18630`.

The first baseline attempt was discarded after a fixture region-directory mismatch. The server was stopped, the reader was corrected and covered by regression tests, and the successful baseline was generated in a new world. That failed attempt is excluded from performance judgments.

An additional negative-seed baseline also exposed a snapshot-readiness issue in the unchanged server: water became bubble columns and some fluid states changed after restart because generation postprocessing was still pending at the original snapshot. Keeping force-load tickets and adding a short fixed delay did not reliably complete promotion before saving. Repeated flushed saves and polling saved offsets alone also timed out: clearing `LevelChunk.postProcessGeneration`'s offsets does not itself mark an already-saved chunk dirty, so a first save made before late promotion can leave stale offsets on disk.

The native readiness guard exposed a separate restart-control issue. Minecraft restores saved FORCED tickets before the startup completion message, while the in-memory tick-freeze state resets on restart. World and fluid ticks can run before the console processes `tick freeze`, allowing otherwise valid saved water states to evolve before the comparison reload. The fixture now removes its forced tickets after the completed save, verifies the native query reports zero, and flushes that removal before stopping. On restart, it freezes ticks before manually loading the distant target areas. It records this policy and rejects comparisons with a different fixture policy.

The corrected fixture checks the requested chunks' `ENTITY_TICKING` state **before the first save**, verifies empty saved postprocessing offsets, removes the test tickets before shutdown and checks the closed-world snapshots again. Missing, malformed or pending postprocessing fails the run rather than being ignored by the terrain comparison. The preserved pre-restart world allows failed comparisons to be inspected without later reloads overwriting that evidence. These changes correct test conditions; production saving, generation scheduling and postprocessing code were not changed. Aquifer remains the only retained production optimization.

`applyPatches` and the full `check createPaperclipJar` completed successfully with the retained patch. The unchanged 18 golden fill hashes passed, as did all 64 script tests: 38 chunk-fixture tests and the 26 existing script tests. Completed native fixture processes saved, stopped and exited with status 0.

Raw controlled results: [baseline](benchmarks/results/2026-10-14/chunk-generation/live-baseline.json), [exact baseline repeated](benchmarks/results/2026-10-14/chunk-generation/live-baseline-repeat.json), [actual candidate](benchmarks/results/2026-10-14/chunk-generation/live-candidate.json). Full worlds and logs remain in their ignored `run/benchmarks/` fixtures. Uncontrolled evidence is retained separately: [same-JAR control differences](benchmarks/results/2026-10-14/chunk-generation/default-worker-control.json), [default-worker repeated baseline](benchmarks/results/2026-10-14/chunk-generation/live-default-baseline-control.json), [default-worker candidate](benchmarks/results/2026-10-14/chunk-generation/live-default-candidate-uncontrolled.json). The raw directory uses the UTC date namespace `2026-10-14`; the work continued into October 15 in Asia/Seoul.

The additional negative-seed control uses seed `-8675309`, four 4×4 measured areas and a fifth 4×4 area generated after restart, for 80 compared chunks. The exact baseline repeat and the actual candidate both matched all 80 hashes, and all three runs passed save/restart with completed postprocessing. Together with the first seed, the final comparison covers 400 chunks; 80 were newly generated after restart.

Across both seeds, 320 chunks were generated before restart and retained identical saved-terrain hashes after reloading. The other 80 chunks were generated after restart, saved, and compared between the baseline, its repeat and the candidate.

| Additional control | Baseline | Candidate | Same-seed/save-restart comparison |
| --- | --- | --- | --- |
| Seed `-8675309`, single worker, 80 chunks | 9.63 requested chunks/s; repeat 9.80 | 10.51 requested chunks/s | 80/80 identical for repeat and candidate; all save/restart checks passed |

Raw negative-seed results: [baseline](benchmarks/results/2026-10-14/chunk-generation/live-seed2-baseline.json), [exact baseline repeated](benchmarks/results/2026-10-14/chunk-generation/live-seed2-baseline-repeat.json), [actual candidate](benchmarks/results/2026-10-14/chunk-generation/live-seed2-candidate.json). Earlier single-worker snapshots are retained as `live-serial-initial-*.json`; they passed their original comparisons but predate the final readiness and restart controls and are excluded from the final table.

## Reproduction and limits

```sh
./gradlew :paper-server:benchmark -PcubveBenchmarks \
  -PcubveBenchmarkFilter='.*ChunkFillBenchmark.*' \
  -PcubveBenchmarkLabel=chunk-fill-current

./gradlew :paper-server:test \
  --tests 'io.github.lunastev.cubve.benchmark.ChunkFillBenchmarkFixtureTest'
python3 -m unittest discover -s scripts/tests
./gradlew check createPaperclipJar

# After personally accepting Minecraft's EULA, run each JAR in a fresh fixture:
python3 scripts/run-chunk-benchmark.py --accept-eula --worker-threads 1 \
  --jar /path/to/baseline.jar --label baseline \
  --seed 8142026 --batches 4 --batch-width 8 --warmup-batches 2
python3 scripts/run-chunk-benchmark.py --accept-eula --worker-threads 1 \
  --jar /path/to/candidate.jar --label candidate \
  --seed 8142026 --batches 4 --batch-width 8 --warmup-batches 2 \
  --compare-summary /path/to/baseline-fixture/summary.json
```

Use the same harness, seed, coordinates, worker policy, JDK, heap and host for both versions. Repeat the baseline JAR first to establish that the full-terrain comparison is repeatable under those controls. `--worker-threads -1` selects the automatic policy for separate diagnostic runs; its results must not be compared with the single-worker table. A JMH label only names its output; it does not select a source implementation. JMH is opt-in and its harness/test dependencies are excluded from production JARs. The live runner always creates a unique fixture directory and never overwrites an existing world. `--jfr` adds local generation profiling when needed; compare runs with identical instrumentation.

Console `forceload` synchronously waits for full chunk generation. Frozen ticks reduce unrelated gameplay variation but do not simulate connected-player exploration, networking or ordinary ticking. Generation also visits neighbor dependency chunks; they are excluded from the requested-chunk throughput divisor and terrain comparison. The reported count is not the total number of generation-stage events or all generated neighbors.

The completed live equivalence comparison covers two seeds and the listed requested chunks, in addition to the fill golden inputs. Before claiming a server-wide CPU, TPS or exploration gain, repeat longer matched runs on a quiet host, add actual players and examine allocation/GC and tick-time distributions. The measured justification for the retained primitive-cache change is the reduced fresh-fixture allocation with unchanged terrain under the verified controls.
