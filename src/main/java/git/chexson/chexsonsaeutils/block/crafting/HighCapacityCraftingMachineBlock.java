package git.chexson.chexsonsaeutils.block.crafting;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class HighCapacityCraftingMachineBlock extends AEBaseEntityBlock<HighCapacityCraftingMachineBlockEntity> {

    public HighCapacityCraftingMachineBlock() {
        super(metalProps());
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        var blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            MenuOpener.open(
                    Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_MENU.get(),
                    player,
                    MenuLocators.forBlockEntity(blockEntity)
            );
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof HighCapacityCraftingMachineBlockEntity machineBlockEntity) {
                machineBlockEntity.onBlockRemovedFromWorld();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
