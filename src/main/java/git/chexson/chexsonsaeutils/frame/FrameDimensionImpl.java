package git.chexson.chexsonsaeutils.frame;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * {@link FrameDimension} 的实现。
 * <p>
 * 动机：私有维度由 MinecraftServerMixin 在服务端启动时注入，本实现负责从服务端
 * 取回该维度实例并做身份判断。无状态，单例即可。
 */
public final class FrameDimensionImpl implements FrameDimension {

    private static final FrameDimensionImpl INSTANCE = new FrameDimensionImpl();

    private FrameDimensionImpl() {
    }

    /**
     * 获取单例。
     *
     * @return 唯一实例
     */
    public static FrameDimensionImpl instance() {
        return INSTANCE;
    }

    @Override
    public ServerLevel getLevel(MinecraftServer server) {
        ServerLevel level = server.getLevel(WORLD_KEY);
        if (level == null) {
            throw new IllegalStateException(
                    "私有维度 " + WORLD_KEY.location() + " 尚未创建（createLevels 未执行或注入失败）");
        }
        return level;
    }

    @Override
    public boolean isFrameLevel(ServerLevel level) {
        return level.dimension() == WORLD_KEY;
    }
}