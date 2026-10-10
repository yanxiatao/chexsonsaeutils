package git.chexson.chexsonsaeutils.blockentity.crafting;

import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class ExtremeAssemblerBlockEntity extends AbstractExtremeAssemblerHostBlockEntity {

    public ExtremeAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(
                Chexsonsaeutils.EXTREME_ASSEMBLER_BLOCK_ENTITY.get(),
                pos,
                blockState,
                () -> Chexsonsaeutils.EXTREME_ASSEMBLER_ITEM.get().getDefaultInstance(),
                "block.chexsonsaeutils.extreme_assembler.pattern_slots_summary"
        );
    }
}
