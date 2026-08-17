package git.chexson.chexsonsaeutils.frame;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 框架样板供应器私有维度（chexsonsaeutils:frames）的统一身份入口。
 * <p>
 * 动机：框架样板供应器包裹机器时，原机器会被搬到 mod 专属私有维度真实运行，
 * 框架 BE 在主世界跨维度映射操作它。本接口定义该维度的身份（维度 key、
 * dimension type key）与获取维度实例的入口，供维度注入 mixin、强制加载服务
 * 与后续搬移阶段共用，避免各模块各自硬编码维度 ID。
 */
public interface FrameDimension {

    /**
     * 私有维度 ID：chexsonsaeutils:frames。
     * 服务端启动时由 MinecraftServerMixin 在 createLevels 末尾手工创建该维度。
     */
    ResourceKey<Level> WORLD_KEY = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "frames"));

    /**
     * 私有维度使用的 DimensionType 注册 key。
     * 对应数据包 JSON：data/chexsonsaeutils/dimension_type/frames.json。
     */
    ResourceKey<DimensionType> DIMENSION_TYPE_KEY = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "frames"));

    /**
     * 获取私有维度实例。
     *
     * @param server 服务端实例
     * @return 私有维度 ServerLevel
     * @throws IllegalStateException 维度尚未创建（createLevels 未执行或注入失败）
     */
    ServerLevel getLevel(MinecraftServer server);

    /**
     * 判断给定维度是否为私有维度。
     *
     * @param level 待判断维度
     * @return true 表示该维度是 chexsonsaeutils:frames
     */
    boolean isFrameLevel(ServerLevel level);
}