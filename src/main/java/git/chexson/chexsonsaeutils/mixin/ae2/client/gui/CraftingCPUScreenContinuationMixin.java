package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.me.crafting.CraftingCPUMenu;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusService;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;

@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenContinuationMixin extends AEBaseScreen<CraftingCPUMenu>
        implements CraftingContinuationStatusService.WaitingStackProjectionHost {
    @Unique
    private boolean partialWaiting;

    @Unique
    private Map<String, Long> waitingStackAmounts = Map.of();

    protected CraftingCPUScreenContinuationMixin(CraftingCPUMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$updateContinuationDetail(CallbackInfo ci) {
        if (menu instanceof CraftingContinuationStatusService.SelectedCpuDetailHost host) {
            partialWaiting = host.chexsonsaeutils$partialWaiting();
            waitingStackAmounts = parseWaitingStackLines(host.chexsonsaeutils$waitingStackLines());
        } else {
            partialWaiting = false;
            waitingStackAmounts = Map.of();
        }
    }

    @Override
    public boolean chexsonsaeutils$partialWaiting() {
        return partialWaiting;
    }

    @Override
    public Map<String, Long> chexsonsaeutils$waitingStackAmounts() {
        return waitingStackAmounts;
    }

    @Unique
    private static Map<String, Long> parseWaitingStackLines(String rawLines) {
        if (rawLines == null || rawLines.isBlank()) {
            return Map.of();
        }

        Map<String, Long> waitingStacks = new LinkedHashMap<>();
        for (String rawLine : rawLines.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }

            String[] parts = rawLine.split("\t", 2);
            if (parts.length != 2) {
                continue;
            }

            try {
                waitingStacks.put(parts[0], Long.parseLong(parts[1]));
            } catch (NumberFormatException ignored) {
                // Ignore malformed sync payloads and fall back to stock AE2 status text.
            }
        }

        return waitingStacks.isEmpty() ? Map.of() : Map.copyOf(waitingStacks);
    }
}
