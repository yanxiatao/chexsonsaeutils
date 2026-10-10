package git.chexson.chexsonsaeutils.mixin.ae2.menu;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftConfirmMenu;
import git.chexson.chexsonsaeutils.crafting.remote.RemoteConfirmPlan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** 只读原生计划，不改变菜单计算、缺料、CPU 选择和提交行为。 */
@Mixin(value = CraftConfirmMenu.class, remap = false)
public interface CraftConfirmMenuRemoteAccessor extends RemoteConfirmPlan {
    @Override @Accessor(value = "result", remap = false) ICraftingPlan remotePlan();
}
