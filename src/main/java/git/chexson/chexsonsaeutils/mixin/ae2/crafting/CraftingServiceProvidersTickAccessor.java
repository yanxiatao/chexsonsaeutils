package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 图编译缓存（Phase 7 后置优化）的失效判据来源：providers 结构 tick。
 */
@Mixin(value = CraftingService.class, remap = false)
public interface CraftingServiceProvidersTickAccessor {

    @Accessor(value = "craftingProviders", remap = false)
    NetworkCraftingProviders chexsonsaeutils$getCraftingProviders();
}
