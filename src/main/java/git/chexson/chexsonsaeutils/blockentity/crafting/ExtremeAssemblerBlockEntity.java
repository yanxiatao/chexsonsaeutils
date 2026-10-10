package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.block.AEBaseEntityBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class ExtremeAssemblerBlockEntity extends AbstractExtremeAssemblerHostBlockEntity {

    public ExtremeAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(
                ((AEBaseEntityBlock<?>) blockState.getBlock()).getBlockEntityType(),
                pos,
                blockState,
                "block.chexsonsaeutils.extreme_assembler.pattern_slots_summary"
        );
    }
}
