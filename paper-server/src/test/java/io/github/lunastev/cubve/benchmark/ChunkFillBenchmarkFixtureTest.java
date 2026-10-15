package io.github.lunastev.cubve.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/** The opt-in JMH source set already includes test output; none of this ships in the server. */
@AllFeatures
public class ChunkFillBenchmarkFixtureTest {
    private static final long SEED = 8142026L;
    private static final ChunkPos[] POSITIONS = {
        new ChunkPos(0, 0), new ChunkPos(64, -32), new ChunkPos(-65, 33)
    };
    // Captured from the production implementation before any chunk-fill optimization, with the
    // same fixture and Minecraft 26.3 registries. Updating these is a worldgen behavior change.
    private static final Map<String, String> BASELINE_DIGESTS = Map.ofEntries(
        Map.entry("overworld/0/0", "decb926d918951ef08ce9d63cce8a30fcab2576f8c6a9c7682f624b82e8a5e14"),
        Map.entry("overworld/64/-32", "92fec86bf6f6d7d8a6d91e4ac982622662bce272e5585b40742b26cc574acaaa"),
        Map.entry("overworld/-65/33", "0cdc4f06583ee0446a0fffcbff392c38e55a81ac57e692ee6e0ad5e8a3683d25"),
        Map.entry("nether/0/0", "12dd0ff2a8bcecc8bc48b3af22569ed4076c1c74e92706aa876bb0a04fb42514"),
        Map.entry("nether/64/-32", "fe35f22c76b95eae99aa33c88fc5a1adeb41e8d85bb02f96b7101c1936f90c14"),
        Map.entry("nether/-65/33", "3407b89812413057d526d95a898c56463a17e391870643ed8acb866fbd40cecf"),
        Map.entry("end/0/0", "0d21d478b13c786aeb9fac84cc8d4645d545fad08b59c682ea4022616ee2ead6"),
        Map.entry("end/64/-32", "a8fddb176d38077abdbcc856b99054779d7d7b1fad6a8cdcd7541ed65130e876"),
        Map.entry("end/-65/33", "a33cd34d06acf3504d16f63787f0c3875385d916cc2b51394cdfaa2a0a0b6586")
    );
    private static final Map<String, String> CLAMPED_BASELINE_DIGESTS = Map.ofEntries(
        Map.entry("0/0/-63/380", "68e6c932b96ed28777850cd6e07c367a380f0d9087104685707967bfcd4cc9c2"),
        Map.entry("64/-32/-63/380", "6c28463fd6b58bd2f11d71a6b2b8a324e4b72bd1b0c72f627ae33afe25015ef3"),
        Map.entry("-65/33/-63/380", "4b2a3a995977dcf134260c2e6afca32ef68c498dba3a061006d715fe807029c6"),
        Map.entry("0/0/-32/192", "e148412ce45a9aca3d0475333717c879817683accdfc650ded377f7d80541f69"),
        Map.entry("64/-32/-32/192", "74df1a44e8a3cf01e08f63a21f6acd3dc19a965042603d0f441a38ff01ed0b45"),
        Map.entry("-65/33/-32/192", "1fec53d2e08038f795c5885e741d420add6bb12ac721673b0494efd23c836b1a"),
        Map.entry("0/0/0/128", "1e60d644bc7df227b174454cfb68fed440130f4d6a258ac14c1866cedc50f516"),
        Map.entry("64/-32/0/128", "4222117a0be1021d44a44f075c20e12150294963afd9a589b468330806922519"),
        Map.entry("-65/33/0/128", "821408c12ebcfaeb60fad8692cedfbf9559e45bc1ece3428708a757e31e204d3")
    );

    @Test
    void freshChunkStartsEmptyAndFillWritesRealBlocksAndHeightmaps() throws Throwable {
        Fixture fixture = new Fixture("overworld", SEED);
        try (PreparedChunk prepared = fixture.prepare(POSITIONS[0])) {
            assertEquals(-64, prepared.chunk().getMinY());
            assertEquals(384, prepared.chunk().getHeight());
            for (LevelChunkSection section : prepared.chunk().getSections()) {
                assertTrue(section.hasOnlyAir(), "Each invocation must start with empty palettes");
            }
            assertTrue(prepared.chunk().getHeightmaps().isEmpty());
            fixture.fill(prepared);
            assertFalse(prepared.chunk().getSection(0).hasOnlyAir());
            assertEquals(2, prepared.chunk().getHeightmaps().size());
            assertTrue(prepared.chunk().getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)
                .getFirstAvailable(0, 0) > prepared.chunk().getMinY());
        }
    }

    @Test
    void everyCellHeightmapAndPostprocessingIsRepeatableAcrossCoordinatesAndDimensions() throws Throwable {
        for (String dimension : new String[]{"overworld", "nether", "end"}) {
            Fixture fixture = new Fixture(dimension, SEED);
            for (ChunkPos position : POSITIONS) {
                String first;
                try (PreparedChunk prepared = fixture.prepare(position)) {
                    fixture.fill(prepared);
                    first = fingerprint(prepared.chunk());
                }
                try (PreparedChunk prepared = fixture.prepare(position)) {
                    fixture.fill(prepared);
                    assertEquals(first, fingerprint(prepared.chunk()), "Fresh repeated fill: " + dimension + " " + position);
                }
                // Capture this line from the unmodified baseline and the candidate test logs.
                // The digest includes every block cell, both WG maps and fluid update offsets.
                System.out.printf("CUBVE_CHUNK_FILL_DIGEST %s %d %d %d %s%n",
                    dimension, SEED, position.x(), position.z(), first);
            }
        }
    }

    @Test
    void everyCellHeightmapAndPostprocessingMatchesUnmodifiedProductionBaseline() throws Throwable {
        for (String dimension : new String[]{"overworld", "nether", "end"}) {
            Fixture fixture = new Fixture(dimension, SEED);
            for (ChunkPos position : POSITIONS) {
                String key = dimension + "/" + position.x() + "/" + position.z();
                try (PreparedChunk prepared = fixture.prepare(position)) {
                    fixture.fill(prepared);
                    assertEquals(BASELINE_DIGESTS.get(key), fingerprint(prepared.chunk()), "Minecraft 26.3 baseline: " + key);
                }
            }
        }
    }

    @Test
    void clampedAndPartialSectionsAreRepeatableAtNegativeAndPositiveCoordinates() throws Throwable {
        for (int[] bounds : new int[][]{{-64, 384}, {-63, 380}, {-32, 192}, {0, 128}}) {
            LevelHeightAccessor height = LevelHeightAccessor.create(bounds[0], bounds[1]);
            Fixture fixture = new Fixture("overworld", SEED, height);
            for (ChunkPos position : POSITIONS) {
                String first;
                try (PreparedChunk prepared = fixture.prepare(position)) {
                    assertEquals(bounds[0], prepared.noise().volume().minBlockY());
                    assertEquals(bounds[0] + bounds[1] - 1, prepared.noise().volume().maxBlockY());
                    fixture.fill(prepared);
                    assertPartialSectionRemainderIsAir(prepared);
                    first = fingerprint(prepared.chunk());
                    String baseline = bounds[0] == -64 && bounds[1] == 384
                        ? BASELINE_DIGESTS.get("overworld/" + position.x() + "/" + position.z())
                        : CLAMPED_BASELINE_DIGESTS.get(position.x() + "/" + position.z() + "/" + bounds[0] + "/" + bounds[1]);
                    assertEquals(baseline, first, "Minecraft 26.3 clamped baseline: " + position + " " + bounds[0] + "/" + bounds[1]);
                }
                try (PreparedChunk prepared = fixture.prepare(position)) {
                    fixture.fill(prepared);
                    assertEquals(first, fingerprint(prepared.chunk()), "Clamped fresh fill: " + height + " " + position);
                }
                System.out.printf("CUBVE_CHUNK_FILL_HEIGHT_DIGEST overworld %d %d %d %d %d %s%n",
                    SEED, position.x(), position.z(), bounds[0], bounds[1], first);
            }
        }
    }

    private static void assertPartialSectionRemainderIsAir(PreparedChunk prepared) {
        ProtoChunk chunk = prepared.chunk();
        DensityVolume volume = prepared.noise().volume();
        for (int sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex) * 16;
            LevelChunkSection section = chunk.getSection(sectionIndex);
            for (int y = 0; y < 16; y++) {
                int blockY = sectionY + y;
                if (blockY < volume.minBlockY() || blockY > volume.maxBlockY()) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            assertTrue(section.getBlockState(x, y, z).isAir(), "Fill must respect the clamped vertical bounds");
                        }
                    }
                }
            }
        }
    }

    /** A registry-backed, unmocked generator, with all expensive world-independent setup reused. */
    public static final class Fixture {
        private static final MethodHandle DO_FILL;
        private static final MethodHandle CREATE_FLUID_PICKER;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(NoiseBasedChunkGenerator.class, MethodHandles.lookup());
                DO_FILL = lookup.findVirtual(NoiseBasedChunkGenerator.class, "doFill",
                    MethodType.methodType(void.class, NoiseChunk.class, ChunkAccess.class));
                CREATE_FLUID_PICKER = lookup.findStatic(NoiseBasedChunkGenerator.class, "createFluidPicker",
                    MethodType.methodType(Aquifer.FluidPicker.class, NoiseGeneratorSettings.class));
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private final NoiseBasedChunkGenerator generator;
        private final NoiseGeneratorSettings settings;
        private final RandomState randomState;
        private final Aquifer.FluidPicker fluidPicker;
        private final PalettedContainerFactory containers;
        private final LevelHeightAccessor heightAccessor;

        public Fixture(String dimension, long seed) throws Throwable {
            this(dimension, seed, null);
        }

        public Fixture(String dimension, long seed, @Nullable LevelHeightAccessor heightAccessor) throws Throwable {
            RegistryAccess registries = RegistryHelper.registryAccess();
            var settingsKey = switch (dimension) {
                case "overworld" -> NoiseGeneratorSettings.OVERWORLD;
                case "nether" -> NoiseGeneratorSettings.NETHER;
                case "end" -> NoiseGeneratorSettings.END;
                default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
            };
            var settingsHolder = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
            this.settings = settingsHolder.value();
            BiomeSource biomes = switch (dimension) {
                case "overworld" -> MultiNoiseBiomeSource.createFromPreset(registries
                    .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
                case "nether" -> MultiNoiseBiomeSource.createFromPreset(registries
                    .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
                case "end" -> TheEndBiomeSource.create(registries.lookupOrThrow(Registries.BIOME));
                default -> throw new AssertionError(dimension);
            };
            this.generator = new NoiseBasedChunkGenerator(biomes, settingsHolder);
            this.randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, this.settings);
            this.fluidPicker = (Aquifer.FluidPicker) CREATE_FLUID_PICKER.invokeExact(this.settings);
            this.containers = PalettedContainerFactory.create(registries);
            var noiseSettings = this.settings.noiseSettings();
            this.heightAccessor = heightAccessor == null
                ? LevelHeightAccessor.create(noiseSettings.minY(), noiseSettings.height()) : heightAccessor;
        }

        public PreparedChunk prepare(ChunkPos position) {
            ProtoChunk chunk = new ProtoChunk(position, UpgradeData.EMPTY, this.heightAccessor, this.containers, null);
            var noiseSettings = this.settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
            if (noiseSettings.height() <= 0) {
                throw new IllegalArgumentException("This fill-only fixture requires a positive clamped noise height");
            }
            DensityVolume volume = new DensityVolume(16, noiseSettings.height(), 16,
                position.getMinBlockX(), noiseSettings.minY(), position.getMinBlockZ());
            NoiseChunk noise = new NoiseChunk(this.randomState, Beardifier.EMPTY, this.settings,
                this.fluidPicker, Blender.empty(), volume);
            // Match buildTerrain's section lock lifetime while leaving locking out of doFill time.
            int bottom = chunk.getSectionIndex(volume.minBlockY());
            int top = chunk.getSectionIndex(volume.maxBlockY());
            for (int sectionIndex = top; sectionIndex >= bottom; sectionIndex--) {
                chunk.getSection(sectionIndex).acquire();
            }
            return new PreparedChunk(chunk, noise);
        }

        public void fill(PreparedChunk prepared) throws Throwable {
            DO_FILL.invokeExact(this.generator, prepared.noise(), (ChunkAccess) prepared.chunk());
        }
    }

    public record PreparedChunk(ProtoChunk chunk, NoiseChunk noise) implements AutoCloseable {
        @Override
        public void close() {
            try {
                DensityVolume volume = this.noise.volume();
                int bottom = this.chunk.getSectionIndex(volume.minBlockY());
                int top = this.chunk.getSectionIndex(volume.maxBlockY());
                for (int sectionIndex = top; sectionIndex >= bottom; sectionIndex--) {
                    this.chunk.getSection(sectionIndex).release();
                }
            } finally {
                this.noise.close();
            }
        }
    }

    public static String fingerprint(ProtoChunk chunk) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        writeInt(digest, chunk.getMinY());
        writeInt(digest, chunk.getHeight());
        for (LevelChunkSection section : chunk.getSections()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        writeInt(digest, Block.getId(section.getBlockState(x, y, z)));
                    }
                }
            }
        }
        for (Heightmap.Types type : new Heightmap.Types[]{Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG}) {
            long[] data = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
            writeInt(digest, data.length);
            for (long value : data) {
                writeInt(digest, (int) (value >>> 32));
                writeInt(digest, (int) value);
            }
        }
        ShortList[] processing = chunk.getPostProcessing();
        writeInt(digest, processing.length);
        for (ShortList offsets : processing) {
            writeInt(digest, offsets == null ? -1 : offsets.size());
            if (offsets != null) {
                for (int i = 0; i < offsets.size(); i++) {
                    writeInt(digest, offsets.getShort(i));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
