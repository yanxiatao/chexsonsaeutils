package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingPlanner;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingProviders;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCompressedRing;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
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
    @Unique
    private AEKey chexsonsaeutils$whatToCraft;

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
        boolean rootNode = this.parent == null;
        this.chexsonsaeutils$whatToCraft = what;
        IPatternDetails parentDetails = rootNode
                ? null
                : ((CraftingTreeProcessAccessor) this.parent).chexsonsaeutils$getDetails();
        GenericStack requestedOutput = job instanceof DyeablePatternCraftingCalculation dyeableCalculation
                ? dyeableCalculation.chexsonsaeutils$getRequestedOutput()
                : null;
        this.chexsonsaeutils$preferredColor = DyeablePatternCraftingPlanner.resolvePreferredColor(
                parentDetails,
                rootNode,
                requestedOutput
        );
        this.chexsonsaeutils$ringCalculable = false;
        if (this.chexsonsaeutils$preferredColor == -1) {
            return;
        }
        if (cc instanceof CraftingService craftingServiceImpl) {
            var providers = ((CraftingServiceDyeablePatternAccessor) craftingServiceImpl)
                    .chexsonsaeutils$getCraftingProviders();
            if (providers instanceof DyeablePatternCraftingProviders dyeableProviders) {
                DyeablePatternCompressedRing ring = rootNode
                        && job instanceof DyeablePatternCraftingCalculation dyeableCalculation
                        ? dyeableCalculation.chexsonsaeutils$getPreparedCompressedRing(dyeableProviders)
                        : dyeableProviders.getOrCalculateCompressedRing(
                                this.chexsonsaeutils$preferredColor,
                                this.chexsonsaeutils$whatToCraft
                        );
                this.chexsonsaeutils$ringCalculable =
                        DyeablePatternCraftingPlanner.canPlanRingReplacementWithoutSwallowingReplacement(ring);
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
