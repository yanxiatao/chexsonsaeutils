package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import appeng.api.networking.IManagedGridNode;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

/**
 * 定制样板供应器的方块版与面板版共用状态壳：5 格升级库存 + 样板供应逻辑 + 已解锁页数。
 * <p>
 * 职责边界：只负责“数据与持久化”，不碰宿主类型差异——推送方向（getTargets）、
 * 网格节点生命周期回调、掉落入口签名、记忆卡设置导出等仍由两侧宿主各自实现，
 * 宿主在这里只以 {@link CustomPatternProviderLogicHost} 与保存回调的形式出现。
 * <p>
 * NBT 约定（两侧必须一致，读写顺序不可交换）：appflux 加载时 {@code upgrades} 键由
 * af_upgrades 独占（logic 与宿主 NBT 同层平铺，appflux mixin 在 logic 读写尾部写同键），
 * 因此本机升级库存整体跳过读写以保持为空壳。
 */
public final class CustomPatternProviderState {

    /** 每页样板槽数量：4 行 x 9 列，与 GUI 布局一致。 */
    public static final int PATTERN_SLOTS_PER_PAGE = 36;

    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_PAGES = "pages";
    /** 升级槽数量：方块版与面板版一致。 */
    public static final int UPGRADE_SLOTS = 5;

    private final Logger logger;
    private final CustomPatternProviderLogicHost host;
    private final IUpgradeInventory upgrades;
    private final CustomPatternProviderLogic logic;
    private int pages = 1;

    /**
     * @param logOwner   页数截断告警归属的宿主类（保持重构前的日志 category）
     * @param mainNode   宿主主网格节点，转交样板供应逻辑注册服务
     * @param host       样板供应逻辑宿主（方块实体或面板）
     * @param upgrades   宿主构造的本机升级库存（机器物品两侧不同，故由宿主注入）
     */
    public CustomPatternProviderState(Class<?> logOwner, IManagedGridNode mainNode,
            CustomPatternProviderLogicHost host, IUpgradeInventory upgrades,
            CustomPatternProviderIntegrationFactory integrationFactory) {
        this.logger = LoggerFactory.getLogger(logOwner);
        this.host = host;
        this.upgrades = upgrades;
        this.logic = new CustomPatternProviderLogic(mainNode, host,
                ChexsonsaeutilsCompatibilityConfig.maxCustomPatternPages() * PATTERN_SLOTS_PER_PAGE, integrationFactory);
    }

    public CustomPatternProviderLogic logic() {
        return logic;
    }

    /**
     * appflux 加载时 logic 被 MixinPatternProviderLogic 注入为 IUpgradeableObject
     * （af_upgrades 库存）——委托给 logic 使 Menu 升级槽与 EAP 频道卡查询指向同一库存；
     * 未加载 appflux 时回落本机 5 槽库存。
     * 副作用：appflux 加载时本机库存成死库存（本项目不注册升级卡，仅感应卡受影响，
     * 可接受；NBT 读写已跳过，见 writeNbt/readNbt）。
     */
    public IUpgradeInventory upgrades() {
        return logic instanceof IUpgradeableObject uo ? uo.getUpgrades() : upgrades;
    }

    /** 写盘：本机升级库存（appflux 时跳过）→ logic → 页数，顺序与重构前两侧一致。 */
    public void writeNbt(CompoundTag data, HolderLookup.Provider registries) {
        if (!ModList.get().isLoaded("appflux")) {
            upgrades.writeToNBT(data, NBT_UPGRADES, registries);
        }
        logic.writeToNBT(data, registries);
        data.putInt(NBT_PAGES, pages);
    }

    /** 读盘：与 writeNbt 对称；旧存档无 pages 键时 getInt 为 0，clamp 到 1。 */
    public void readNbt(CompoundTag data, HolderLookup.Provider registries) {
        if (!ModList.get().isLoaded("appflux")) {
            upgrades.readFromNBT(data, NBT_UPGRADES, registries);
        }
        logic.readFromNBT(data, registries);
        this.pages = clampPages(data.getInt(NBT_PAGES));
    }

    /** 掉落：样板库存/返回库存/sendList + 本机升级库存。 */
    public void addDrops(List<ItemStack> drops) {
        logic.addDrops(drops);
        for (int slot = 0; slot < upgrades.size(); slot++) {
            var stack = upgrades.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
    }

    /** 清空内容：logic 与本机升级库存（AE2 UpgradeablePart 惯例，拆除不残留升级卡）。 */
    public void clearContent() {
        logic.clearContent();
        upgrades.clear();
    }

    public int pages() {
        return pages;
    }

    /** 设置已解锁页数并触发保存（加载路径请用 {@link #readNbt} 或 {@link #initPages}）。 */
    public void setPages(int pages) {
        this.pages = clampPages(pages);
        host.saveChanges();
    }

    /**
     * 放置读回注入页数：直接赋值不触发保存——放置流程中宿主可能尚未挂到世界上，
     * 保存回调此时会过早写盘甚至空指针。
     */
    public void initPages(int pages) {
        this.pages = clampPages(pages);
    }

    /**
     * 页数收敛到 [1, maxCustomPatternPages()]；发生截断时输出告警日志（读盘与设置共用）。
     */
    private int clampPages(int rawPages) {
        int max = ChexsonsaeutilsCompatibilityConfig.maxCustomPatternPages();
        int clamped = Math.max(1, Math.min(rawPages, max));
        if (clamped != rawPages) {
            logger.warn("样板页数截断：old={}, new={}, maxPages={}", rawPages, clamped, max);
        }
        return clamped;
    }
}
