package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface NeoEcoBatchDispatcher {

    @Nullable
    NeoEcoPreparedBatch chexsonsaeutils$prepareNeoEcoBatch(
            IPatternDetails pattern,
            KeyCounter[] perCraftInputs,
            KeyCounter perCraftOutputs,
            KeyCounter perCraftRemainders,
            ListCraftingInventory inventory,
            long requested,
            IEnergyService energyService,
            Level level,
            UUID craftingJobId
    );
}
