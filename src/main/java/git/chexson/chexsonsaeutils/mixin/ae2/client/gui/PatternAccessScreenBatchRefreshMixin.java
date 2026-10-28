package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
import java.util.Set;

/** 分批到达的后续槽位也参与搜索；一帧只重建一次，避免每片重扫整个提供器列表。 */
@Mixin(value = PatternAccessTermScreen.class, remap = false)
public abstract class PatternAccessScreenBatchRefreshMixin {
    @Shadow(remap = false) @Final private Map<String, Set<Object>> cachedSearches;
    @Unique private boolean chexsonsaeutils$patternsDirty;
    @Invoker(value = "refreshList", remap = false) protected abstract void chexsonsaeutils$refreshPatternList();

    @Inject(method = "postIncrementalUpdate", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$markPatternSearchDirty(long inventoryId, Int2ObjectMap<ItemStack> slots, CallbackInfo ci) {
        if (!slots.isEmpty()) chexsonsaeutils$patternsDirty = true;
    }

    @Inject(method = "updateBeforeRender", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$refreshAfterPatternBatches(CallbackInfo ci) {
        if (!chexsonsaeutils$patternsDirty) return;
        cachedSearches.clear();
        chexsonsaeutils$refreshPatternList();
        chexsonsaeutils$patternsDirty = false;
    }
}
