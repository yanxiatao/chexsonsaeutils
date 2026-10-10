package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import cn.dancingsnow.neoecoae.api.me.ECOBatchCapacityProvider;
import cn.dancingsnow.neoecoae.api.me.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.impl.crafting.fastpath.ECOBatchCraftingExecutor;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractExtremeAssemblerHostBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchDispatcher;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchHost;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoPreparedBatch;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

import java.util.UUID;

@Pseudo
@Mixin(
        value = {
                AbstractExtremeAssemblerHostBlockEntity.class,
                AEDirectProcessingMachineBlockEntity.class
        },
        remap = false
)
public abstract class ExtremeAssemblerNeoEcoBatchProviderMixin
        implements ECOBatchCapacityProvider, NeoEcoBatchDispatcher {

    @Override
    public long eco$getBatchCapacity(ECOBatchDispatchContext context) {
        return chexsonsaeutils$host().chexsonsaeutils$getNeoEcoBatchCapacity(context.pattern());
    }

    @Override
    public boolean eco$pushBatch(ECOBatchDispatchContext context, long count) {
        return chexsonsaeutils$host().chexsonsaeutils$pushNeoEcoBatch(
                context.pattern(),
                context.inputItems(),
                context.outputs(),
                context.containerItems(),
                context.craftingJobId(),
                count
        );
    }

    @Nullable
    @Override
    public NeoEcoPreparedBatch chexsonsaeutils$prepareNeoEcoBatch(
            IPatternDetails pattern,
            KeyCounter[] perCraftInputs,
            KeyCounter perCraftOutputs,
            KeyCounter perCraftRemainders,
            ListCraftingInventory inventory,
            long requested,
            IEnergyService energyService,
            Level level,
            UUID craftingJobId
    ) {
        ECOBatchCraftingExecutor.PreparedBatch prepared = ECOBatchCraftingExecutor.prepare(
                this,
                pattern,
                perCraftInputs,
                perCraftOutputs,
                perCraftRemainders,
                inventory,
                requested,
                energyService,
                level,
                craftingJobId
        );
        if (prepared == null) {
            return null;
        }
        return new NeoEcoPreparedBatch() {
            @Override
            public boolean push(ListCraftingInventory craftingInventory) {
                return prepared.push(craftingInventory);
            }

            @Override
            public long craftCount() {
                return prepared.craftCount();
            }

            @Override
            public java.util.List<appeng.api.stacks.GenericStack> outputs() {
                return prepared.outputs();
            }

            @Override
            public java.util.List<appeng.api.stacks.GenericStack> remainders() {
                return prepared.remainders();
            }

            @Override
            public double power() {
                return prepared.power();
            }
        };
    }

    private NeoEcoBatchHost chexsonsaeutils$host() {
        return (NeoEcoBatchHost) (Object) this;
    }
}
