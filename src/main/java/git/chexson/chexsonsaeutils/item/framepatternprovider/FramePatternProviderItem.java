package git.chexson.chexsonsaeutils.item.framepatternprovider;

import git.chexson.chexsonsaeutils.block.framepatternprovider.FramePatternProviderBlock;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 框架样板供应器物品。
 * <p>
 * 右键已有方块（非空气、非本框架方块、可破坏）时捕获目标方块：替换为框架方块，
 * 原方块 BlockState 与原 BE NBT 存入框架 BE，原方块掉落物不产生，本物品被消耗。
 * 右键框架方块时：潜行交给方块处理拆框架，否则走正常放置。
 * <p>
 * 页数保留闭环（需求 5）：拆框架掉落的物品携带 FRAME_PATTERN_PAGES 组件，
 * 捕获（useOn）与正常放置（setBlockEntityData）路径都从组件读回页数写入 BE。
 */
public class FramePatternProviderItem extends BlockItem {

    public FramePatternProviderItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState targetState = level.getBlockState(pos);
        if (targetState.getBlock() instanceof FramePatternProviderBlock) {
            // 目标是框架方块：潜行时返回 PASS 交给方块处理拆框架，否则正常放置
            if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            return super.useOn(context);
        }
        if (FramePatternProviderBlock.canCapture(level, pos, targetState)) {
            if (!level.isClientSide()) {
                int pages = context.getItemInHand()
                        .getOrDefault(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), 1);
                FramePatternProviderBlockEntity.captureBlock(level, pos, pages);
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.useOn(context);
    }

    @Override
    protected boolean updateCustomBlockEntityTag(
            BlockPos pos, Level level, net.minecraft.world.entity.player.Player player, ItemStack stack, BlockState state) {
        // 正常放置路径：物品组件里的已解锁页数写回 BE（拆除保留闭环）
        int pages = stack.getOrDefault(ChexsonsaeutilsContent.FRAME_PATTERN_PAGES.get(), 1);
        if (level.getBlockEntity(pos) instanceof FramePatternProviderBlockEntity blockEntity) {
            blockEntity.setPages(pages);
        }
        return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
    }
}