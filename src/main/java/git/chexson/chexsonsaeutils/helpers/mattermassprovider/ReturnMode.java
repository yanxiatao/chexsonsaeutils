package git.chexson.chexsonsaeutils.helpers.mattermassprovider;

/**
 * 物质团供应器产物返回目标模式。
 * <p>
 * NETWORK：物质团 insert 回网格存储（合成链接经 CraftingServiceStorage 自动认领）。
 * PLAYER：物质团直接发送给放置者玩家背包，交付成功后立即向合成服务上报完成。
 */
public enum ReturnMode {
    NETWORK,
    PLAYER
}
