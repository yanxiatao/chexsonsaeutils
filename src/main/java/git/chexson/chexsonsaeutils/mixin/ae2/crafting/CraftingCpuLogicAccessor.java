package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.AEKey;
import appeng.core.sync.packets.CraftingJobStatusPacket;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
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

    @Invoker(value = "postChange", remap = false)
    void invokePostChange(AEKey what);

    @Invoker(value = "notifyJobOwner", remap = false)
    void invokeNotifyJobOwner(ExecutingCraftingJob job, CraftingJobStatusPacket.Status status);
}
