package git.chexson.chexsonsaeutils.item.custompatternprovider;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.implementations.items.IStorageComponent;
import git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternExpandableItem;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 定制样板供应器物品（阶段 2）。
 * <p>
 * 普通方块物品（无框架捕获逻辑）：放置行为为 BlockItem 默认。页数保留闭环
 * （需求 5）：挖掘/拆除掉落的物品携带 FRAME_PATTERN_PAGES 组件，放置时
 * updateCustomBlockEntityTag 读回组件写入 BE。
 * <p>
 * 存储组件注册（阶段 5b 方式 B）：实现 {@link IStorageComponent} 与
 * {@link FramePatternExpandableItem} 标记接口，使本物品可放入物质聚合器存储元件位、
 * 扩容 GUI 存储槽，并参与扩容（与框架版物品同一套共享逻辑）。
 */
public class CustomPatternProviderItem extends BlockItem implements IStorageComponent, FramePatternExpandableItem {

    /** 每页样板容量对应的字节数（"容量表示页数"语义，与 1k 存储元件同量级）。 */
    private static final int BYTES_PER_PAGE = 1024;

    public CustomPatternProviderItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * 恒 true（阶段 5b 方式 B）：无条件视为存储组件——物质聚合器存储元件位可放入，
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

    @Override
    protected boolean updateCustomBlockEntityTag(
            BlockPos pos, Level level, Player player, ItemStack stack, BlockState state) {
        // 正常放置路径：物品组件里的已解锁页数写回 BE（拆除保留闭环）
        int pages = stack.getOrDefault(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), 1);
        if (level.getBlockEntity(pos) instanceof CustomPatternProviderBlockEntity blockEntity) {
            blockEntity.setPages(pages);
        }
        return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
    }
}