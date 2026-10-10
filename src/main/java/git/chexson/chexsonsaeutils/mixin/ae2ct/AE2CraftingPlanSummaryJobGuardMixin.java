package git.chexson.chexsonsaeutils.mixin.ae2ct;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.integration.ae2ct.Ae2ctJobBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * 只针对在场 addon 的编码兜底守卫。
 *
 * <p>ae2ct（AE2 Crafting Tweaks）给 {@link CraftingPlanSummary} 注入了一个
 * {@code RecipeHelper jobs} 字段，并在 {@code write} 的 TAIL 无条件
 * {@code jobs.write(buffer)}。字段没填就是 NPE，AE2 的 {@code ae2:craft_confirm_plan}
 * 编码失败会直接把整条连接掐断（2026-09-23 00:27 实机崩溃即此）。
 *
 * <p>这里在 {@code write} 的 HEAD 补一手：job 为空就塞一个空的合法 helper。
 * HEAD 注入的字节位置一定早于任何 TAIL 注入，所以跟 ae2ct 的先后无关，
 * 也与 {@link AE2CTRecipeHelperCompatMixin}（{@code fromJob} HEAD，正常路径下已经把
 * job 填好）互不影响——那条路走通了这里就是空操作。
 *
 * <p>不引入对 ae2ct 的编译期依赖：判定与写入全在 {@link Ae2ctJobBridge} 里靠反射完成，
 * 没装 ae2ct 或反射失败时一个字节都不改。注册见 {@code chexsonsaeutils.mixins.json}，
 * 是否应用由 {@code ChexsonsaeutilsMixinPlugin#AE2CT_COMPAT_MIXINS} 按 ae2ct 在场 gating。
 */
@Mixin(value = CraftingPlanSummary.class, priority = 1100, remap = false)
public abstract class AE2CraftingPlanSummaryJobGuardMixin {

    @Inject(method = "write", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$fillMissingAe2ctJob(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        Ae2ctJobBridge.fillEmptyJobWhenMissing(this);
    }
}
