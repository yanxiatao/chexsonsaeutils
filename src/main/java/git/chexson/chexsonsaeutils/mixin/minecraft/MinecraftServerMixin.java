package git.chexson.chexsonsaeutils.mixin.minecraft;

import java.util.Map;
import java.util.concurrent.Executor;

import com.google.common.collect.ImmutableList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import git.chexson.chexsonsaeutils.frame.FrameDimension;
import git.chexson.chexsonsaeutils.frame.FramesChunkGenerator;

/**
 * 服务端启动时注入私有维度 chexsonsaeutils:frames。
 * <p>
 * 动机：私有维度必须在每个世界（无论世界预设）都可用，因此仿照 AE2 的
 * spatial 维度做法，在 MinecraftServer.createLevels 末尾手工创建 ServerLevel。
 * 客户端只在进入该维度时收到 dimension type 引用，不会收到世界生成设置。
 */
@Mixin(value = MinecraftServer.class, remap = false)
public abstract class MinecraftServerMixin {

    @Shadow
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Shadow
    protected WorldData worldData;

    @Shadow
    protected Executor executor;

    @Shadow
    protected LevelStorageSource.LevelStorageAccess storageSource;

    @Shadow
    protected LayeredRegistryAccess<RegistryLayer> registries;

    @SuppressWarnings("ConstantConditions")
    @Inject(method = "createLevels", at = @At(value = "TAIL"))
    public void chexsonsaeutils$injectFrameLevel(ChunkProgressListener chunkProgressListener, CallbackInfo ci) {
        var registryHolder = registries.compositeAccess();

        var levelStem = new LevelStem(
                registryHolder.lookupOrThrow(Registries.DIMENSION_TYPE)
                        .getOrThrow(FrameDimension.DIMENSION_TYPE_KEY),
                new FramesChunkGenerator(registryHolder.lookupOrThrow(Registries.BIOME)));

        long seed = BiomeManager.obfuscateSeed(this.worldData.worldGenOptions().seed());

        var serverLevelData = this.worldData.overworldData();
        var derivedLevelData = new DerivedLevelData(this.worldData, serverLevelData);
        var level = new ServerLevel(
                (MinecraftServer) (Object) this,
                this.executor,
                this.storageSource,
                derivedLevelData,
                FrameDimension.WORLD_KEY,
                levelStem,
                chunkProgressListener,
                false /* debug */,
                seed,
                ImmutableList.of(),
                false,
                null);
        // 私有维度不注册世界边界（玩家不能自由移动）。
        this.levels.put(FrameDimension.WORLD_KEY, level);
        // 模拟 Forge 的世界加载事件，触发强制加载重挂等监听。
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }
}