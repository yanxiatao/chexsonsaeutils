package git.chexson.chexsonsaeutils.integration.buildinggadgets2;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

@FunctionalInterface
public interface BuildingGadgets2StateDrops {
    List<ItemStack> dropsFor(BlockState state);
}
