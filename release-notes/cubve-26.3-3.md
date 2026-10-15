## Highlights

- Remove per-block temporary pressure-cache objects from Aquifer calculations during chunk generation.
- Reduce allocated bytes by 46.2–57.5% in three fresh-chunk fill benchmark fixtures; generation-time and TPS improvements are not established.
- Verify 400 same-seed terrain hashes across two seeds with a controlled single-worker fixture, including save/restart checks on 320 chunks and 80 newly generated after restart.
- Add 18 baseline fill hashes covering Overworld, Nether, End and partial height ranges, plus reproducible generation benchmarks and persistence checks.

See [the measurement report](https://github.com/LunaStev/Cubve/blob/cubve-26.3-3/CHUNK-PERFORMANCE.md) for scope, raw results and test conditions.
