package git.chexson.chexsonsaeutils.network.crafting;

/** 当前确认菜单的分片入口，让网络处理无需依赖 AE2 菜单实现。 */
public interface WidePlanChunkTarget {
    /** 将当前容器收到的分片交给其独占的账本接收状态。 */
    void receiveWidePlanChunk(long revision, int index, int count, String data);
}
