package git.chexson.chexsonsaeutils.block.debug;

import appeng.block.AEBaseEntityBlock;
import git.chexson.chexsonsaeutils.blockentity.debug.AutoItemGenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class AutoItemGenBlock extends AEBaseEntityBlock<AutoItemGenBlockEntity> {

    public AutoItemGenBlock() {
        super(metalProps());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!heldItem.isEmpty()
                && level.getBlockEntity(pos) instanceof AutoItemGenBlockEntity be) {
            be.setFilter(heldItem.getItem());
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useItemOn(heldItem, state, level, pos, player, hand, hit);
    }
}
