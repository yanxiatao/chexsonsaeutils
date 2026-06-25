package git.chexson.chexsonsaeutils.block.crafting;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.phys.BlockHitResult;

public class AE2ParallelCpuToolBlock extends AEBaseEntityBlock<AE2ParallelCpuToolBlockEntity> {

    public AE2ParallelCpuToolBlock() {
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
        AE2ParallelCpuToolBlockEntity blockEntity = this.getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            if (!blockEntity.isParallelCpuProviderActive()) {
                return InteractionResult.FAIL;
            }
            blockEntity.refreshParallelCpuProvider();
            boolean opened = MenuOpener.open(
                    craftingCpuMenuTypeForServerPath(blockEntity),
                    player,
                    MenuLocators.forBlockEntity(blockEntity)
            );
            return opened ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    public static ParallelCraftingCPUMenu createCraftingCpuMenuForServerPath(
            int id,
            Inventory inventory,
            AE2ParallelCpuToolBlockEntity blockEntity
    ) {
        return new ParallelCraftingCPUMenu(id, inventory, blockEntity);
    }

    public static MenuType<ParallelCraftingCPUMenu> craftingCpuMenuTypeForServerPath(
            AE2ParallelCpuToolBlockEntity blockEntity
    ) {
        return Chexsonsaeutils.AE2_PARALLEL_CPU_TOOL_CPU_MENU.get();
    }

    @Override
    public void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block changedBlock,
            BlockPos changedPos,
            boolean movedByPiston
    ) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof AE2ParallelCpuToolBlockEntity parallelCpuTool) {
            parallelCpuTool.refreshParallelCpuProvider();
        }
        super.neighborChanged(state, level, pos, changedBlock, changedPos, movedByPiston);
    }
}
