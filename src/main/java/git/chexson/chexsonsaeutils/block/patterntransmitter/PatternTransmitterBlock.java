package git.chexson.chexsonsaeutils.block.patterntransmitter;
import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.blockentity.patterntransmitter.PatternTransmitterBlockEntity;
import git.chexson.chexsonsaeutils.menu.patterntransmitter.PatternTransmitterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 双面单向样板桥；外观箭头从背面来源指向正面接收网络。 */
public final class PatternTransmitterBlock extends AEBaseEntityBlock<PatternTransmitterBlockEntity> {
    public PatternTransmitterBlock() { super(metalProps()); }
    @Override public appeng.api.orientation.IOrientationStrategy getOrientationStrategy() {
        return appeng.api.orientation.OrientationStrategies.facing();
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        var be = getBlockEntity(level, pos);
        if (be == null) return InteractionResult.PASS;
        if (!level.isClientSide()) MenuOpener.open(PatternTransmitterMenu.TYPE, player, MenuLocators.forBlockEntity(be));
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!level.isClientSide() && !next.is(state.getBlock()) && level.getBlockEntity(pos) instanceof PatternTransmitterBlockEntity be) be.dismantle();
        super.onRemove(state, level, pos, next, moving);
    }
}
