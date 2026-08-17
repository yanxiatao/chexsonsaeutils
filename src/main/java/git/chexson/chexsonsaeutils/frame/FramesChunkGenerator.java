package git.chexson.chexsonsaeutils.frame;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * 私有维度（chexsonsaeutils:frames）的空世界区块生成器。
 * <p>
 * 动机：私有维度只用于真实运行被包裹的机器，不需要任何自然地形。
 * 本生成器不生成任何方块（全空气），生物群系固定为 plains，
 * 结构参考 AE2 的 SpatialStorageChunkGenerator，但去掉了方块填充。
 * <p>
 * 注意：本生成器仅由 MinecraftServerMixin 直接构造，不参与数据包反序列化，
 * 因此 codec() 直接失败（Fail Fast），不提供序列化路径。
 */
public class FramesChunkGenerator extends ChunkGenerator {

    /** 私有维度最低 Y。与 dimension_type/frames.json 的 min_y 一致。 */
    public static final int MIN_Y = 0;

    /** 私有维度总高度。与 dimension_type/frames.json 的 height 一致。 */
    public static final int HEIGHT = 256;

    /** 全空气竖直采样，供特征生成等逻辑查询。 */
    private final NoiseColumn columnSample;

    public FramesChunkGenerator(HolderGetter<Biome> biomeRegistry) {
        super(new FixedBiomeSource(biomeRegistry.getOrThrow(Biomes.PLAINS)));
        BlockState[] columnSample = new BlockState[HEIGHT];
        Arrays.fill(columnSample, Blocks.AIR.defaultBlockState());
        this.columnSample = new NoiseColumn(MIN_Y, columnSample);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        throw new UnsupportedOperationException(
                "FramesChunkGenerator 仅由 MinecraftServerMixin 直接构造，不参与数据包反序列化");
    }

    @Override
    public int getGenDepth() {
        return HEIGHT;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public void buildSurface(WorldGenRegion worldGenRegion, StructureManager structureManager,
            RandomState randomState, ChunkAccess chunk) {
        // 空世界：不生成任何方块，区块保持默认空气。
    }

    @Override
    public int getSeaLevel() {
        return MIN_Y;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Types type, LevelHeightAccessor levelHeightAccessor,
            RandomState randomState) {
        return MIN_Y;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor levelHeightAccessor,
            RandomState randomState) {
        return columnSample;
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
    }

    @Override
    public void applyCarvers(WorldGenRegion worldGenRegion, long seed, RandomState randomState,
            BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunkAccess,
            Carving carving) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }
}