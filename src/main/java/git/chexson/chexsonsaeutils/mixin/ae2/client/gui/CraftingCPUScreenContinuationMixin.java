package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.me.crafting.CraftingCPUMenu;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationStatusService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenContinuationMixin extends AEBaseScreen<CraftingCPUMenu> {
    @Unique
    private boolean partialWaiting;

    @Unique
    private String finalOutput = "";

    @Unique
    private long requestedAmount = 0L;

    @Unique
    private List<String> waitingBranchLines = List.of();

    protected CraftingCPUScreenContinuationMixin(CraftingCPUMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$updateContinuationDetail(CallbackInfo ci) {
        if (menu instanceof CraftingContinuationStatusService.SelectedCpuDetailHost host) {
            partialWaiting = host.chexsonsaeutils$partialWaiting();
            finalOutput = host.chexsonsaeutils$finalOutput();
            requestedAmount = host.chexsonsaeutils$requestedAmount();
            waitingBranchLines = host.chexsonsaeutils$waitingBranchLines().isBlank()
                    ? List.of()
                    : Arrays.asList(host.chexsonsaeutils$waitingBranchLines().split("\\R"));
        } else {
            partialWaiting = false;
            finalOutput = "";
            requestedAmount = 0L;
            waitingBranchLines = List.of();
        }
    }

    @Inject(method = "drawFG", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$drawContinuationDetail(
            GuiGraphics guiGraphics,
            int offsetX,
            int offsetY,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        if (!partialWaiting) {
            return;
        }

        int x = 8;
        int y = 108;
        guiGraphics.drawString(font, Component.literal(finalOutput + " x" + requestedAmount), x, y, 0x555555, false);
        int lineY = y + 10;
        for (String line : waitingBranchLines) {
            guiGraphics.drawString(font, Component.literal(line), x, lineY, 0x666666, false);
            lineY += 10;
        }
    }
}
