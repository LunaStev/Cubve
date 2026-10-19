## Highlights

- Backport the reviewed Paper fix for custom decorated-pot update serialization and add regression coverage.
- Skip redundant section bookkeeping after an unchanged palette write. Same-state stone, water and leaves writes take 17.8–23.9% less method time in the measured microbenchmarks.
- Build the native clone source-clearing list only for MOVE. Together with the section change, this avoids the extra copy allocation observed in the section-only control; combined clone allocation remains near the original baseline.
- Verify block counters, palette growth, serialization, native clone modes, overlapping moves, block-entity contents, pending ticks and saved-world equality after restart.

These measurements do not establish a server-wide TPS improvement or resolve the reported production spikes. See [the measurement report](https://github.com/LunaStev/Cubve/blob/cubve-26.3-4/BUILDING-PERFORMANCE.md) for scope, raw results and test conditions.
