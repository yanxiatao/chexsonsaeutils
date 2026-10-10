package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Decodes host-bound virtual aggregated crafting patterns persisted inside normal AE2 encoded crafting pattern stacks.
 */
public final class HostBatchPatternDecoder implements IPatternDetailsDecoder {

    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        return HostBatchPatternImpl.isEncodedDefinition(stack);
    }

    @Override
    public @Nullable IPatternDetails decodePattern(AEItemKey what, Level level) {
        return HostBatchPatternImpl.decode(what, level);
    }

    @Override
    public @Nullable IPatternDetails decodePattern(ItemStack what, Level level) {
        AEItemKey definition = AEItemKey.of(what);
        return definition == null ? null : decodePattern(definition, level);
    }
}
