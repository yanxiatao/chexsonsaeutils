package git.chexson.chexsonsaeutils.frame;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
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
 * 注意：本生成器仅由 MinecraftServerMixin 直接构造，不参与数据包反序列化；
 * 但 codec() 必须返回合法编解码器——ChunkMap 读取任何区块时都经
 * getTypeNameForDataFixer() 调用 codec()，抛异常会导致本维度所有区块
 * 磁盘读取失败（UnsupportedOperationException 刷屏）。
 * 与 AE2 SpatialStorageChunkGenerator 一致，CODEC 注册到 CHUNK_GENERATOR
 * 注册表（见 Chexsonsaeutils 构造器 RegisterEvent），保证 DFU 上下文完整。
 */
public class FramesChunkGenerator extends ChunkGenerator {

    /** 私有维度最低 Y。与 dimension_type/frames.json 的 min_y 一致。 */
    public static final int MIN_Y = 0;

    /** 私有维度总高度。与 dimension_type/frames.json 的 height 一致。 */
    public static final int HEIGHT = 256;

    /** 区块生成器注册表 ID（照 AE2 SpatialStorageDimensionIds.CHUNK_GENERATOR_ID）。 */
    public static final ResourceLocation CHUNK_GENERATOR_ID =
            ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "frames");

    /**
     * 区块类型编解码器（供 ChunkMap 读取区块时的 datafixer 路径使用）。
     * <p>
     * RegistryOps.retrieveGetter(Registries.BIOME) 从动态注册表重建 HolderGetter——
     * 反序列化出的 biome holder 必须与注册表内实例相同（内部用 identity map
     * 做 Object->ID 查找），照 AE2 SpatialStorageChunkGenerator 的 CODEC 实现。
     */
    public static final MapCodec<FramesChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(
                    RegistryOps.retrieveGetter(Registries.BIOME))
            .apply(instance, instance.stable(FramesChunkGenerator::new)));

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
        return CODEC;
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