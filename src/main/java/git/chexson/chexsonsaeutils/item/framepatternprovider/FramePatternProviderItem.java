package git.chexson.chexsonsaeutils.item.framepatternprovider;

import git.chexson.chexsonsaeutils.block.framepatternprovider.FramePatternProviderBlock;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
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
                FramePatternProviderBlockEntity.captureBlock(level, pos);
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.useOn(context);
    }
}