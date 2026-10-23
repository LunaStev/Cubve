## Highlights

- Skip complete empty sections during downward heightmap searches. With Extended World Height 4.0 (1024) and WorldEdit 7.4.6, removing a 16×16 plane at Y=1000 measured 16.278 → 2.252 ms median in the isolated fixture.
- Allocate section ticking indexes only when needed. Empty 128-section chunk construction allocates 14,336 fewer bytes (18.52%); this is not a whole-server heap percentage.
- Add native `/pregen start <world> <center-x> <center-z> <radius-blocks> [chunks-per-second]` with status, pause, resume and cancel. Owners and console/RCON can generate gradually using low-priority asynchronous requests, with one pending request and load-based admission checks.
- Verify actual WorldEdit copy, undo/redo, heightmaps, lighting, chunk packets and unchanged saved block/biome data after restart. Include regression tests and reproducible measurement fixtures.

See [the measurement report](https://github.com/LunaStev/Cubve/blob/cubve-26.3-6/EXTENDED-HEIGHT-PERFORMANCE.md) for test conditions and scope. These results do not establish a server-wide TPS improvement or a hard CPU/memory limit for pregeneration.
