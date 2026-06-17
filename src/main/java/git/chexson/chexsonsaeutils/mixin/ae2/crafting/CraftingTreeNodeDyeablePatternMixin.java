package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingProviders;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingPlanner;
import git.chexson.chexsonsaeutils.crafting.color.PatternColorHelper;
import java.util.Collection;
import appeng.me.service.CraftingService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class CraftingTreeNodeDyeablePatternMixin {

    @Shadow(remap = false)
    private CraftingTreeProcess parent;

    @Unique
    private int chexsonsaeutils$preferredColor = -1;
    @Unique
    private boolean chexsonsaeutils$ringCalculable;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$capturePreferredColor(
            ICraftingService cc,
            CraftingCalculation job,
            AEKey what,
            long amount,
            CraftingTreeProcess par,
            int slot,
            CallbackInfo ci
    ) {
        if (this.parent == null) {
            return;
        }
        IPatternDetails parentDetails = ((CraftingTreeProcessAccessor) this.parent).chexsonsaeutils$getDetails();
        this.chexsonsaeutils$preferredColor = PatternColorHelper.getPatternColor(parentDetails);
        this.chexsonsaeutils$ringCalculable = false;
        if (cc instanceof CraftingService craftingServiceImpl) {
            var providers = ((CraftingServiceDyeablePatternAccessor) craftingServiceImpl)
                    .chexsonsaeutils$getCraftingProviders();
            if (providers instanceof DyeablePatternCraftingProviders dyeableProviders) {
                this.chexsonsaeutils$ringCalculable =
                        DyeablePatternCraftingPlanner.isCompressedRingCalculable(
                                dyeableProviders.getOrCalculateCompressedRing(this.chexsonsaeutils$preferredColor)
                        );
            }
        }
    }

    @Redirect(
            method = "buildChildPatterns",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingService;getCraftingFor(Lappeng/api/stacks/AEKey;)Ljava/util/Collection;"
            ),
            remap = false
    )
    private Collection<IPatternDetails> chexsonsaeutils$preferSameColorPatterns(
            ICraftingService craftingService,
            AEKey whatToCraft
    ) {
        Collection<IPatternDetails> patterns = craftingService.getCraftingFor(whatToCraft);
        if (this.chexsonsaeutils$preferredColor == -1 || patterns.isEmpty()) {
            return patterns;
        }
        if (craftingService instanceof CraftingService craftingServiceImpl) {
            var providers = ((CraftingServiceDyeablePatternAccessor) craftingServiceImpl)
                    .chexsonsaeutils$getCraftingProviders();
            if (providers instanceof DyeablePatternCraftingProviders dyeableProviders
                    && this.chexsonsaeutils$ringCalculable) {
                return dyeableProviders.getCraftingForByColor(whatToCraft, this.chexsonsaeutils$preferredColor);
            }
        }
        return DyeablePatternCraftingPlanner.prioritizeSameColorFallback(
                patterns,
                this.chexsonsaeutils$preferredColor
        );
    }
}
