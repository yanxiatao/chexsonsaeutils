package git.chexson.chexsonsaeutils.block.crafting;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class AEDirectProcessingMachineBlock extends AEBaseEntityBlock<AEDirectProcessingMachineBlockEntity> {

    public AEDirectProcessingMachineBlock() {
        super(metalProps());
    }

    @Override
    public InteractionResult use(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult
    ) {
        var blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            MenuOpener.open(
                    Chexsonsaeutils.AE_DIRECT_PROCESSING_MACHINE_MENU.get(),
                    player,
                    MenuLocators.forBlockEntity(blockEntity)
            );
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
