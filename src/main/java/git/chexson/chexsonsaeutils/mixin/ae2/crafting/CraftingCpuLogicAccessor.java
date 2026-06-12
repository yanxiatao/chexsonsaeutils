package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.AEKey;
import appeng.core.network.clientbound.CraftingJobStatusPacket;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public interface CraftingCpuLogicAccessor {
    @Accessor(value = "inventory", remap = false)
    ListCraftingInventory getInventory();

    @Accessor(value = "job", remap = false)
    ExecutingCraftingJob getJob();

    @Accessor(value = "job", remap = false)
    void setJob(ExecutingCraftingJob job);

    @Accessor(value = "cluster", remap = false)
    CraftingCPUCluster getCluster();

    @Invoker(value = "postChange", remap = false)
    void invokePostChange(AEKey what);

    @Invoker(value = "notifyJobOwner", remap = false)
    void invokeNotifyJobOwner(ExecutingCraftingJob job, CraftingJobStatusPacket.Status status);

    @Invoker(value = "finishJob", remap = false)
    void invokeFinishJob(boolean success);
}
