package git.chexson.chexsonsaeutils.parts.custompatternprovider;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.implementations.items.IStorageComponent;
import appeng.items.parts.PartItem;
import git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternExpandableItem;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 定制样板供应器面板物品（阶段 3 I1 修复）。
 * <p>
 * 泛型 PartItem 不实现 {@link FramePatternExpandableItem}，扩容 GUI 存储槽
 * （{@link git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeHost}
 * 槽 0 的 instanceof 过滤）拒绝放入，面板页数恒 1、翻页按钮恒隐藏。本类实现
 * {@link IStorageComponent} 与 {@link FramePatternExpandableItem} 标记接口
 * （与方块版
 * {@link git.chexson.chexsonsaeutils.item.custompatternprovider.CustomPatternProviderItem}
 * 同一套共享扩容逻辑），使面板物品可放入扩容 GUI 存储槽并参与扩容。
 * <p>
 * 页数闭环：放置读回与拆除写回不在此类实现——AE2 19.2.17 的 PartItem 只有无参
 * createPart()（无 createPart(ItemStack)），放置时 PartPlacement 以放置物品的组件
 * 映射调用 part.importSettings(SettingsFrom.DISMANTLE_ITEM, ...)，拆除时
 * IPart.addPartDrop 默认实现经 exportSettings(SettingsFrom.DISMANTLE_ITEM) 生成
 * 掉落栈组件——读回/写回均在 {@link CustomPatternProviderPart} 的
 * importSettings/exportSettings DISMANTLE_ITEM 分支完成（见该类的 initPagesFromStack）。
 */
public class CustomPatternProviderPartItem extends PartItem<CustomPatternProviderPart>
        implements IStorageComponent, FramePatternExpandableItem {

    /** 每页样板容量对应的字节数（"容量表示页数"语义，与方块版 BYTES_PER_PAGE 一致）。 */
    private static final int BYTES_PER_PAGE = 1024;

    public CustomPatternProviderPartItem(Item.Properties properties) {
        super(properties, CustomPatternProviderPart.class, CustomPatternProviderPart::new);
    }

    /**
     * 恒 true（与方块版一致）：无条件视为存储组件——物质聚合器存储元件位可放入，
     * TRASH 槽反向排除天然防误吞（见 {@link appeng.menu.slot.RestrictedInputSlot.PlacableItemType#TRASH}）。
     */
    @Override
    public boolean isStorageComponent(ItemStack stack) {
        return true;
    }

    /**
     * @return 页数 x 1024 字节（clamp 最小值 1024，避免能量容量为 0）；
     * 无组件时按默认 1 页处理（物品尚未扩容）。
     */
    @Override
    public int getBytes(ItemStack stack) {
        int pages = Math.max(1, stack.getOrDefault(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), 1));
        return pages * BYTES_PER_PAGE;
    }
}