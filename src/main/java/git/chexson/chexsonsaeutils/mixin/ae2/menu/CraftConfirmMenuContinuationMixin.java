package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.api.storage.ISubMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuContinuationMixin extends AEBaseMenu
        implements CraftingContinuationSubmitBridge.ContinuationModeHost {
    @Unique
    private static final String chexsonSetContinuationMode = "chexsonSetContinuationMode";

    @Unique
    private CraftingContinuationMode chexsonsaeutils$continuationMode = CraftingContinuationMode.defaultMode();

    protected CraftConfirmMenuContinuationMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$initContinuationMode(int id, Inventory inventory, ISubMenuHost host, CallbackInfo ci) {
        chexsonsaeutils$continuationMode = CraftingContinuationMode.defaultMode();
        registerClientAction(chexsonSetContinuationMode, CraftingContinuationMode.class,
                this::chexsonsaeutils$applyContinuationMode);
    }

    @Override
    public CraftingContinuationMode getContinuationMode() {
        return chexsonsaeutils$continuationMode;
    }

    @Override
    public void setContinuationMode(CraftingContinuationMode mode) {
        chexsonsaeutils$continuationMode = normalize(mode);
    }

    @Override
    public void requestContinuationMode(CraftingContinuationMode mode) {
        setContinuationMode(mode);
        if (isClientSide()) {
            sendClientAction(chexsonSetContinuationMode, chexsonsaeutils$continuationMode);
        }
    }

    @Unique
    private void chexsonsaeutils$applyContinuationMode(CraftingContinuationMode mode) {
        setContinuationMode(mode);
    }

    @Unique
    private static CraftingContinuationMode normalize(CraftingContinuationMode mode) {
        return mode == null ? CraftingContinuationMode.defaultMode() : mode;
    }

    @Redirect(
            method = "startJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingPlan;simulation()Z"
            ),
            remap = false
    )
    private boolean chexsonsaeutils$allowSimulationSubmit(ICraftingPlan plan) {
        return plan.simulation() && chexsonsaeutils$continuationMode != CraftingContinuationMode.IGNORE_MISSING;
    }

    @Redirect(
            method = "startJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingService;submitJob(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/IActionSource;)Lappeng/api/networking/crafting/ICraftingSubmitResult;"
            ),
            remap = false
    )
    private ICraftingSubmitResult chexsonsaeutils$submitWithContinuationMode(
            ICraftingService craftingService,
            ICraftingPlan plan,
            ICraftingRequester requester,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    ) {
        return CraftingContinuationSubmitBridge.withContinuationMode(
                chexsonsaeutils$continuationMode,
                () -> craftingService.submitJob(plan, requester, target, prioritizePower, src)
        );
    }
}
