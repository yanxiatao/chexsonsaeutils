package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import cn.dancingsnow.neoecoae.crafting.execution.ElapsedTimeTracker;
import cn.dancingsnow.neoecoae.crafting.execution.ExecutingCraftingJob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 精确接收所需的执行账；字段仅用于同一次服务器线程实物转移。 */
@Pseudo
@Mixin(value = ExecutingCraftingJob.class, remap = false)
public interface NeoEcoJobOutputAccessor {
    @Accessor("waitingFor") ListCraftingInventory chexsonsaeutils$waitingFor();
    @Accessor("timeTracker") ElapsedTimeTracker chexsonsaeutils$timeTracker();
    @Accessor("link") CraftingLink chexsonsaeutils$link();
    @Accessor("exactOrder") boolean chexsonsaeutils$exactOrder();
}
