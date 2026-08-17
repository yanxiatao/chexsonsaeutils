package git.chexson.chexsonsaeutils.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link FrameStorage} 的实现。
 * <p>
 * 动机：把强制加载（FrameTicketController）与持久化（FrameStorageSavedData）
 * 组合成单一 API。无状态，单例即可（状态都在 SavedData 里）。
 */
public final class FrameStorageImpl implements FrameStorage {

    private static final FrameStorageImpl INSTANCE = new FrameStorageImpl();

    private FrameStorageImpl() {
    }

    /**
     * 获取单例。
     *
     * @return 唯一实例
     */
    public static FrameStorageImpl instance() {
        return INSTANCE;
    }

    @Override
    public boolean forceload(ServerLevel frameLevel, BlockPos owner, int chunkX, int chunkZ, boolean add) {
        boolean changed = FrameTicketController.instance().forceChunk(frameLevel, owner, chunkX, chunkZ, add);
        FrameStorageSavedData savedData = FrameStorageSavedData.get(frameLevel);
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        if (add) {
            savedData.addForceloadRecord(owner, chunk);
        } else {
            savedData.removeForceloadRecord(owner, chunk);
        }
        return changed;
    }

    @Override
    public void putFramePosition(ServerLevel frameLevel, UUID frameId, BlockPos position) {
        FrameStorageSavedData.get(frameLevel).putFramePosition(frameId, position);
    }

    @Override
    public @Nullable BlockPos getFramePosition(ServerLevel frameLevel, UUID frameId) {
        return FrameStorageSavedData.get(frameLevel).getFramePosition(frameId);
    }

    @Override
    public void removeFramePosition(ServerLevel frameLevel, UUID frameId) {
        FrameStorageSavedData.get(frameLevel).removeFramePosition(frameId);
    }

    @Override
    public BlockPos allocateNextPosition(ServerLevel frameLevel) {
        return FrameStorageSavedData.get(frameLevel).allocateNextPosition();
    }

    @Override
    public void reinstateForceloads(ServerLevel frameLevel) {
        Map<BlockPos, Set<Long>> records = FrameStorageSavedData.get(frameLevel).snapshotForceloadRecords();
        records.forEach((owner, chunks) -> chunks.forEach(chunk -> {
            ChunkPos chunkPos = new ChunkPos(chunk);
            FrameTicketController.instance().forceChunk(frameLevel, owner, chunkPos.x, chunkPos.z, true);
        }));
    }
}