package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.guisync.GuiSync;
import git.chexson.chexsonsaeutils.crafting.progress.PlanningProgressMenu;
import git.chexson.chexsonsaeutils.crafting.progress.PlanningProgressSource;
import java.util.concurrent.Future;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 同步菜单当前 Future 的进度；重新规划自动换源，完成/取消后清除旧标题。 */
@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuPlanningProgressMixin extends appeng.menu.AEBaseMenu implements PlanningProgressMenu {
    protected CraftConfirmMenuPlanningProgressMixin(net.minecraft.world.inventory.MenuType<?> type, int id,
            net.minecraft.world.entity.player.Inventory inventory, Object host) {
        super(type, id, inventory, host);
    }
    @Shadow private Future<ICraftingPlan> job;
    @Unique private Future<ICraftingPlan> chexsonsaeutils$progressJob;
    @Unique private long chexsonsaeutils$progressStarted;
    @GuiSync(9214) public String chexsonsaeutils$planningPhase = "";
    @GuiSync(9215) public long chexsonsaeutils$planningSteps;
    @GuiSync(9216) public long chexsonsaeutils$planningMillis;

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$syncPlanningProgress(CallbackInfo ci) {
        if (!isServerSide()) return;
        if (job != chexsonsaeutils$progressJob) {
            chexsonsaeutils$progressJob = job;
            chexsonsaeutils$progressStarted = System.nanoTime();
        }
        if (job != null && !job.isDone() && !job.isCancelled()) {
            if (job instanceof PlanningProgressSource source) {
                var progress = source.planningProgress().snapshot();
                chexsonsaeutils$planningPhase = progress.phase();
                chexsonsaeutils$planningSteps = progress.steps();
                chexsonsaeutils$planningMillis = progress.elapsedMillis();
            } else {
                chexsonsaeutils$planningPhase = "EXTERNAL";
                chexsonsaeutils$planningSteps = 0L;
                chexsonsaeutils$planningMillis = Math.max(0L, (System.nanoTime() - chexsonsaeutils$progressStarted) / 1_000_000L);
            }
        } else {
            chexsonsaeutils$planningPhase = "";
            chexsonsaeutils$planningSteps = 0L;
            chexsonsaeutils$planningMillis = 0L;
        }
    }
    @Override public String planningPhase() { return chexsonsaeutils$planningPhase; }
    @Override public long planningSteps() { return chexsonsaeutils$planningSteps; }
    @Override public long planningElapsedMillis() { return chexsonsaeutils$planningMillis; }
}
