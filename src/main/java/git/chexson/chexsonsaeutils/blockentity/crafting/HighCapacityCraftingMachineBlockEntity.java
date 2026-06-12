package git.chexson.chexsonsaeutils.blockentity.crafting;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class HighCapacityCraftingMachineBlockEntity extends AbstractHighCapacityCraftingHostBlockEntity {

    public HighCapacityCraftingMachineBlockEntity(BlockPos pos, BlockState blockState) {
        super(
                Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY.get(),
                pos,
                blockState,
                () -> Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_ITEM.get().getDefaultInstance(),
                "gui.chexsonsaeutils.high_capacity_crafting_machine.page_status",
                BatchExecutionMode.SAME_PATTERN_DRAIN,
                true
        );
    }

    @Override
    protected int computeLaneCount(int speedCards) {
        return Math.max(1, 1 << Math.max(0, speedCards));
    }
}
