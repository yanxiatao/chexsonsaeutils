package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade;
import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractExtremeAssemblerHostBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatchDispatcher;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;
import git.chexson.chexsonsaeutils.integration.neoecoae.PreparedBatchImpl;
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
        implements ECOFastPathDispatchProvider, PreparedBatchDispatcher {

    /**
     * beta4 把 preview14 的「问容量 + 推批」两刀合成一次申报：容量仍是宿主自己的口径，
     * 推送换成 {@code Preparation} 里那个 {@code Predicate<Batch>}。
     * <p>
     * 宿主容量非正时返回 null：beta4 的 {@code Preparation} 构造器要求 capacity&gt;0，
     * 而 preview14 的执行器对容量&lt;=0 本来就是「不走快路径」，口径一致。
     * <p>
     * 派发谓词必须拿 {@code context} 的<b>每发</b>输出来记账：beta4 的 {@code Batch}
     * 已经按份数乘过总量，宿主 {@code chexsonsaeutils$pushNeoEcoBatch} 里还会再乘一次 count，
     * 换成 {@code batch.outputTotal()} 就是双重放大。
     */
    @Override
    public @Nullable Preparation eco$prepareFastPath(ECOBatchDispatchContext context) {
        NeoEcoBatchHost host = chexsonsaeutils$host();
        long capacity = host.chexsonsaeutils$getNeoEcoBatchCapacity(context.pattern());
        if (capacity <= 0L) {
            return null;
        }
        return new Preparation(
                capacity,
                null,
                false,
                batch -> host.chexsonsaeutils$pushNeoEcoBatch(
                        context.pattern(),
                        context.inputItems(),
                        context.outputs(),
                        context.containerItems(),
                        context.craftingJobId(),
                        batch.craftCount()
                )
        );
    }

    /** 21.2.1 的精确事务先限制材料额度，派发成功后才登记 CPU 等待量。 */
    @Override
    public @Nullable ExactPreparation eco$prepareExactFastPath(ECOBatchDispatchContext context,
            java.math.BigInteger requested) {
        return git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoExactBatchPreparation.prepare(
                chexsonsaeutils$host(), context, requested);
    }

    @Nullable
    @Override
    public PreparedBatch prepareBatch(
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
        // preview14 的执行器在内部按每发输入算出单发功耗再乘份数；beta4 把这个 double 上提给调用方。
        // 仍按同一口径算：calculatePatternPower(每发输入)——与常规推送用的是同一个 helper。
        double singlePower = CraftingCpuHelper.calculatePatternPower(perCraftInputs);
        // mixin 声明的不是 ICraftingProvider（真正的供应者是被注入的 BE），按 mixin 惯例经 Object 中转。
        ECOFastPathFacade.PreparedBatch prepared = ECOFastPathFacade.prepare(
                (ICraftingProvider) (Object) this,
                pattern,
                perCraftInputs,
                perCraftOutputs,
                perCraftRemainders,
                inventory,
                requested,
                singlePower,
                energyService,
                level,
                craftingJobId
        );
        if (prepared == null) {
            return null;
        }
        return new PreparedBatchImpl(prepared);
    }

    private NeoEcoBatchHost chexsonsaeutils$host() {
        return (NeoEcoBatchHost) (Object) this;
    }

}
