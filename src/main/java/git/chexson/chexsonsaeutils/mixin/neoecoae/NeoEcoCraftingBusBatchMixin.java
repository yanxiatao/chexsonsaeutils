package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade;
import cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher;
import git.chexson.chexsonsaeutils.integration.neoecoae.PreparedBatchImpl;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** 让并行 CPU 使用 NeoEco 合成子系统自己的批量事务与机器容量申报。 */
@Pseudo
@Mixin(value = ECOCraftingPatternBusBlockEntity.class, remap = false)
public abstract class NeoEcoCraftingBusBatchMixin implements PreparedBatchDispatcher {
    @Override public @Nullable PreparedBatch prepareBatch(IPatternDetails pattern, KeyCounter[] inputs,
            KeyCounter outputs, KeyCounter remainders, ListCraftingInventory inventory, long requested,
            IEnergyService energy, Level level, UUID jobId) {
        var prepared = ECOFastPathFacade.prepare((ICraftingProvider) (Object) this, pattern, inputs,
                outputs, remainders, inventory, requested, CraftingCpuHelper.calculatePatternPower(inputs),
                energy, level, jobId);
        return prepared == null ? null : new PreparedBatchImpl(prepared);
    }
}
