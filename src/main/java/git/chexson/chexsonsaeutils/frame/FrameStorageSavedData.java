package git.chexson.chexsonsaeutils.frame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 私有维度（chexsonsaeutils:frames）的 SavedData。
 * <p>
 * 动机：私有维度由 MinecraftServerMixin 每次服务端启动时重建，维度内机器的
 * 位置映射、强制加载归属与坐标分配游标必须跨重启持久化，否则重启后机器
 * 位置丢失、强制加载无法恢复。本类存储于私有维度自己的 data 目录。
 * <p>
 * 内容：
 * <ul>
 *   <li>frameId(UUID) → 私有维度 BlockPos 映射表（搬移阶段填充，本阶段提供骨架）</li>
 *   <li>强制加载记录：owner(主世界 BlockPos) → 私有维度区块集合（启动时重挂）</li>
 *   <li>坐标分配游标：线性分配下一个可用坐标（间距 ≥ 3 格）</li>
 * </ul>
 */
public final class FrameStorageSavedData extends SavedData {

    private static final String DATA_NAME = "chexsonsaeutils_frames";
    private static final String FRAME_POSITIONS_KEY = "frame_positions";
    private static final String FORCELOAD_RECORDS_KEY = "forceload_records";
    private static final String NEXT_ALLOCATION_INDEX_KEY = "next_allocation_index";

    /** 坐标分配间距（格）。 */
    private static final int POSITION_SPACING = 3;

    /** 机器默认放置 Y。维度高度 0..256，取中间值避免边界问题。 */
    private static final int DEFAULT_Y = 64;

    private static final Factory<FrameStorageSavedData> FACTORY =
            new Factory<>(FrameStorageSavedData::new, FrameStorageSavedData::read);

    /** frameId → 私有维度 BlockPos。 */
    private final Map<UUID, BlockPos> framePositions = new LinkedHashMap<>();

    /** owner(主世界 BlockPos) → 私有维度强制加载的区块（ChunkPos.toLong()）。 */
    private final Map<BlockPos, Set<Long>> forceloadRecords = new LinkedHashMap<>();

    /** 坐标分配游标：下一个待分配的位置索引。 */
    private int nextAllocationIndex;

    public FrameStorageSavedData() {
    }

    private FrameStorageSavedData(CompoundTag tag, HolderLookup.Provider provider) {
        if (tag == null) {
            return;
        }
        if (tag.contains(FRAME_POSITIONS_KEY, Tag.TAG_LIST)) {
            ListTag list = tag.getList(FRAME_POSITIONS_KEY, Tag.TAG_COMPOUND);
            for (Tag entry : list) {
                CompoundTag compound = (CompoundTag) entry;
                UUID frameId = NbtUtils.loadUUID(compound.get("frame_id"));
                NbtUtils.readBlockPos(compound, "position").ifPresent(position -> framePositions.put(frameId, position));
            }
        }
        if (tag.contains(FORCELOAD_RECORDS_KEY, Tag.TAG_LIST)) {
            ListTag list = tag.getList(FORCELOAD_RECORDS_KEY, Tag.TAG_COMPOUND);
            for (Tag entry : list) {
                CompoundTag compound = (CompoundTag) entry;
                NbtUtils.readBlockPos(compound, "owner").ifPresent(owner -> {
                    long[] chunks = compound.getLongArray("chunks");
                    Set<Long> chunkSet = new LinkedHashSet<>();
                    for (long chunk : chunks) {
                        chunkSet.add(chunk);
                    }
                    forceloadRecords.put(owner, chunkSet);
                });
            }
        }
        nextAllocationIndex = tag.getInt(NEXT_ALLOCATION_INDEX_KEY);
    }

    /**
     * 获取私有维度的 SavedData（不存在则创建）。
     *
     * @param level 私有维度
     * @return SavedData 实例
     */
    public static FrameStorageSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ---------- frameId → 私有维度位置映射（搬移阶段填充） ----------

    /**
     * 登记 frameId 对应的私有维度位置。
     *
     * @param frameId  框架唯一 ID
     * @param position 私有维度位置
     */
    public void putFramePosition(UUID frameId, BlockPos position) {
        if (frameId == null || position == null) {
            return;
        }
        framePositions.put(frameId, position);
        setDirty();
    }

    /**
     * 查询 frameId 对应的私有维度位置。
     *
     * @param frameId 框架唯一 ID
     * @return 私有维度位置；无记录时返回 null
     */
    public @Nullable BlockPos getFramePosition(UUID frameId) {
        if (frameId == null) {
            return null;
        }
        return framePositions.get(frameId);
    }

    /**
     * 移除 frameId 的映射（机器被取出时调用）。
     *
     * @param frameId 框架唯一 ID
     */
    public void removeFramePosition(UUID frameId) {
        if (frameId == null) {
            return;
        }
        if (framePositions.remove(frameId) != null) {
            setDirty();
        }
    }

    // ---------- 坐标分配器 ----------

    /**
     * 分配下一个可用坐标（线性分配，间距 ≥ 3 格）。
     * 分配结果由调用方通过 putFramePosition 登记占用。
     *
     * @return 私有维度可用坐标
     */
    public BlockPos allocateNextPosition() {
        while (true) {
            BlockPos candidate = positionForIndex(nextAllocationIndex);
            nextAllocationIndex++;
            if (!framePositions.containsValue(candidate)) {
                setDirty();
                return candidate;
            }
        }
    }

    private BlockPos positionForIndex(int index) {
        return new BlockPos(index * POSITION_SPACING, DEFAULT_Y, 0);
    }

    // ---------- 强制加载记录 ----------

    /**
     * 判断 owner 是否仍有强制加载记录。
     *
     * @param owner 归属者（主世界框架 BE 的位置）
     * @return true 表示有记录
     */
    public boolean hasForceloadRecord(BlockPos owner) {
        return owner != null && forceloadRecords.containsKey(owner);
    }

    /**
     * 追加一条强制加载记录。
     *
     * @param owner 归属者（主世界框架 BE 的位置）
     * @param chunk 私有维度区块
     */
    public void addForceloadRecord(BlockPos owner, ChunkPos chunk) {
        if (owner == null || chunk == null) {
            return;
        }
        forceloadRecords.computeIfAbsent(owner, key -> new LinkedHashSet<>()).add(chunk.toLong());
        setDirty();
    }

    /**
     * 移除一条强制加载记录。
     *
     * @param owner 归属者（主世界框架 BE 的位置）
     * @param chunk 私有维度区块
     */
    public void removeForceloadRecord(BlockPos owner, ChunkPos chunk) {
        if (owner == null || chunk == null) {
            return;
        }
        Set<Long> chunks = forceloadRecords.get(owner);
        if (chunks != null && chunks.remove(chunk.toLong())) {
            if (chunks.isEmpty()) {
                forceloadRecords.remove(owner);
            }
            setDirty();
        }
    }

    /**
     * 全部强制加载记录快照（owner → 区块集合），供启动重挂遍历。
     *
     * @return 不可变快照
     */
    public Map<BlockPos, Set<Long>> snapshotForceloadRecords() {
        return Map.copyOf(forceloadRecords);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag framePositionTags = new ListTag();
        framePositions.forEach((frameId, position) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("frame_id", NbtUtils.createUUID(frameId));
            entry.put("position", NbtUtils.writeBlockPos(position));
            framePositionTags.add(entry);
        });
        tag.put(FRAME_POSITIONS_KEY, framePositionTags);

        ListTag forceloadRecordTags = new ListTag();
        forceloadRecords.forEach((owner, chunks) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("owner", NbtUtils.writeBlockPos(owner));
            entry.putLongArray("chunks", chunks.stream().mapToLong(Long::longValue).toArray());
            forceloadRecordTags.add(entry);
        });
        tag.put(FORCELOAD_RECORDS_KEY, forceloadRecordTags);

        tag.putInt(NEXT_ALLOCATION_INDEX_KEY, nextAllocationIndex);
        return tag;
    }

    private static FrameStorageSavedData read(CompoundTag tag, HolderLookup.Provider provider) {
        return new FrameStorageSavedData(tag, provider);
    }
}