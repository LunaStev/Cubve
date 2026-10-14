# Cubve development

## Local setup

Use JDK 25 and the checked-in Gradle wrapper. Keep `origin` pointed at Cubve; add Paper as the read-only upstream:

```sh
git remote add upstream https://github.com/PaperMC/Paper.git
git remote set-url --push upstream DISABLED
git fetch upstream
./gradlew applyPatches
./gradlew check createPaperclipJar
./gradlew runDevServer
```

The development server uses `run/`. Review Minecraft's EULA yourself before accepting it. Optional `test-plugin` and `paper-generator` projects are enabled through their ignored `*.settings.gradle.kts` files.

Keep inherited Bukkit and Paper API packages compatible. Put Cubve-specific implementation in `io.github.lunastev.cubve`. Preserve technical upstream names and attribution; product identity, support links, builds and commands belong to Cubve.

## Minecraft patches

API and server implementation files under `src/main/java` are edited directly. Minecraft sources under `paper-server/src/minecraft` are generated and ignored by the outer repository.

For a per-file Minecraft change:

```sh
./gradlew fixupSourcePatches
./gradlew rebuildPatches
```

Commit the resulting tracked patches, not only generated sources. See the inherited [CONTRIBUTING.md](CONTRIBUTING.md) for patch mechanics; its Paper contribution process does not define Cubve's own development process.

## Upstream policy

`build-data/upstream.properties` records the full Paper base. `build-data/upstream-fixes.txt` records individually reviewed same-version fixes.

- Minecraft version upgrades take the complete Paper base for that version, including its dependency and API changes.
- Between upgrades, select security fixes, world/item data-loss fixes and critical crash fixes only. Include required dependency commits explicitly, in order.
- Inspect the patch, affected versions and dependencies. A commit title or label alone does not establish that a fix is security-related.
- Do not use GitHub Sync Fork to indiscriminately merge same-version changes under this policy.

Start from a clean, committed checkout and create an update branch:

```sh
git switch -c update/paper-fixes
scripts/update-upstream.sh fixes security <reviewed-SHA> [dependency-or-fix-SHA ...]
# Other allowed categories: data-loss, crash

# For a new Minecraft release, use a separate branch:
scripts/update-upstream.sh version <Paper-ref>
```

Put dependencies first. The script checks Paper ancestry and Minecraft version, records provenance with `cherry-pick -x`, and never pushes. It stops on conflicts for manual resolution. After resolving a cherry-pick, record the selected SHAs in `upstream-fixes.txt`; after resolving a version merge, update the base/version in `upstream.properties`. Commit those records before validation.

Validate with `python3 -m unittest discover -s scripts/tests -v`, `applyPatches`, `check`, `createPaperclipJar`, then server startup, plugin compatibility, world loading/saving and restart against a disposable world copy. Review version-specific Java requirements when upgrading.

The manually dispatched **Update Paper upstream** workflow applies the same policy, validates the candidate and opens a PR in Cubve. It does not merge it. GitHub Actions must be allowed to create pull requests in this repository. Its token may not trigger a second workflow, so validation runs before opening the PR.

## CI and releases

**Build Cubve** verifies branch pushes, pull requests and manual validation runs. The build job applies patches, runs Python maintenance/release tests and Gradle checks, then packages the runnable Paperclip JAR. A separate smoke job verifies its checksum and executes that same artifact with `--version`; this does not accept the EULA or start a world.

After both jobs succeed, a push to `master` in `LunaStev/Cubve` can publish a GitHub release. Only that job has `contents: write`. PRs, other branches and workflow_dispatch never publish. Runs on a branch are serialized without canceling a running build; GitHub can replace an older pending run with a newer one. A commit superseded on master before publication is skipped. No source version-bump commits or Maven publication are performed.

Numbering uses `cubve-<Minecraft>-<patch>` tags, not Actions run numbers. With no tags for a Minecraft version, the first patch is 1; otherwise the next patch is the numeric maximum plus 1. Failed validation creates no tag. A rerun for an already-tagged commit reuses its patch. Once verification passes, the publisher creates a draft, uploads `cubve-<Minecraft>-<patch>.jar` and `SHA256SUMS`, checks GitHub's asset digests, then publishes. An interrupted upload leaves a draft/tag to resume by rerunning the original push workflow. Published assets are never replaced. Resume interrupted drafts before advancing master if you need to preserve an unbroken release sequence.

CI sets `BUILD_NUMBER` to the planned patch and `BUILD_STARTED_AT` to the source commit's timestamp. The release filename, server manifest and `/version` identity use the same patch. Without a build number the runtime remains a development build with no remote update check. Published builds query the latest GitHub release, report patch updates for the same Minecraft version, and distinguish a newer Minecraft version.

Release notes follow [Wave's change-list presentation](https://github.com/wavefnd/Wave/releases): `What's Changed`, change titles, authors, commit links and a full comparison link. They use first-parent commits since the previous reachable Cubve tag, including across a Minecraft version change. The initial comparison starts at `build-data/release-base.properties` to exclude inherited Paper history. An optional `release-notes/cubve-<Minecraft>-<patch>.md` supplies reviewed highlights for that release. Do not add unsupported performance claims to highlights.

```sh
# Read-only planning; this does not create a tag or publish:
python3 scripts/release.py plan --release --output /tmp/cubve-release-plan.json
# The complete release automation remains in GitHub Actions after a master push.
```

Workflow sequencing and draft/asset operations follow the official [concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency), [release API](https://docs.github.com/en/rest/releases/releases) and [asset API](https://docs.github.com/en/rest/releases/assets) contracts. GitHub Actions must be enabled on the fork and allowed to use the declared actions and write repository contents for the publication job. No separate Paper credentials or release token is required.

## Optimization baseline

Before changing behavior, record the commit, JDK/JVM flags, hardware, world copy, plugin list, player/load scenario and warm-up period. Use the bundled spark profiler to find the bottleneck. Compare MSPT distributions, allocation/GC, CPU and chunk throughput under the same workload. `/status` is a quick diagnostic, not a benchmark.

Validate event/plugin behavior, world persistence and restart alongside performance. Introduce configuration only when administrators need a meaningful choice; preserve current behavior by default for experimental changes. Keep measured changes and their regression coverage documented in [PERFORMANCE.md](PERFORMANCE.md).


## Isolated survival profiling

The runner creates a fresh seeded survival world in `run/benchmarks/`, binds to loopback on an available port and installs no plugins. It measures an empty server, forced loading of 16 new chunks, 1,024 connected hoppers and 256 stationary cows with AI disabled. It then saves and restarts the world and checks a saved hopper block. It never overwrites an existing fixture.

After personally accepting Minecraft's EULA:

```sh
python3 scripts/run-survival-benchmark.py --accept-eula
# Also check hopper transfers, redstone, replacement, composters, minecarts and saved item counts:
python3 scripts/run-survival-benchmark.py --accept-eula --validate-hoppers
# Preparation without starting a server or accepting the EULA:
python3 scripts/run-survival-benchmark.py --prepare-only
```

Use `--java /path/to/java`, `--jar /path/to/cubve.jar`, `--seed`, `--seconds` and `--warmup` to keep comparisons consistent. Defaults are JDK 25, seed 8142026, 30 seconds per phase, 15 seconds warm-up, and a fixed 2 GiB JVM heap. Run without simultaneous builds or benchmarks.

The runner enables local JFR and `-Dcubve.profile.ticks=true`. The Cubve tick event records `tickServer` work time; it excludes waiting between ticks and tasks processed outside that method. The hook is disabled by default. JFR, console logs, phases and `summary.json` stay in the ignored fixture directory; nothing is uploaded. CPU stack counts are sampled, inclusive counts, not percentages of total elapsed time. Chunk-stage event counts are not completed-chunk throughput.

These loads provide repeatable diagnostics with no connected players. They do not cover movement, combat, active mob AI, networking or a populated production world. Forced chunk loading via a console command can synchronously wait for generation. See [SURVIVAL-PERFORMANCE.md](SURVIVAL-PERFORMANCE.md) for the measured baseline and its limits.

## Optional API microbenchmarks

JMH is enabled only by `-PcubveBenchmarks`; it is not included in production JARs or normal CI builds. These benchmarks exercise actual CraftInventory and ItemMeta implementations with the existing registry test fixtures, without starting a Minecraft world.

```sh
./gradlew :paper-server:benchmark -PcubveBenchmarks -PcubveBenchmarkLabel=current
# Run only inventory measurements:
./gradlew :paper-server:benchmark -PcubveBenchmarks -PcubveBenchmarkLabel=inventory -PcubveBenchmarkFilter='.*InventoryBenchmark.*'
```

Results are written to `paper-server/build/reports/benchmarks/<label>.json`. A label names the output only; it does not select a baseline implementation. Compare the pre-change and post-change sources using the same harness, JDK, JVM flags, host and input. The task uses two JVM forks, three warm-up iterations, five measurement iterations and JMH's GC profiler. Do not run builds or other benchmarks concurrently with a measurement.
