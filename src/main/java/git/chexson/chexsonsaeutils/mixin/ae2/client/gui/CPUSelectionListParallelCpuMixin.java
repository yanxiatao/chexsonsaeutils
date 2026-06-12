package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.Point;
import appeng.client.gui.Tooltip;
import appeng.client.gui.widgets.CPUSelectionList;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.Tooltips;
import appeng.menu.me.crafting.CraftingStatusMenu;
import git.chexson.chexsonsaeutils.client.gui.Ae2ByteDisplayFormatter;
import git.chexson.chexsonsaeutils.client.gui.Ae2CompactNumberFormatter;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@Mixin(value = CPUSelectionList.class, remap = false)
public abstract class CPUSelectionListParallelCpuMixin {

    @Shadow(remap = false)
    @Nullable
    protected abstract CraftingStatusMenu.CraftingCpuListEntry hitTestCpu(Point mousePos);

    @Shadow(remap = false)
    protected abstract Component getCpuName(CraftingStatusMenu.CraftingCpuListEntry cpu);

    @Inject(method = "formatStorage", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$formatLargeStorageSafely(
            CraftingStatusMenu.CraftingCpuListEntry cpu,
            CallbackInfoReturnable<String> cir
    ) {
        cir.setReturnValue(Ae2ByteDisplayFormatter.format(cpu.storage()));
    }

    @Inject(method = "getTooltip", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$renderLargeStorageTooltipSafely(
            int mouseX,
            int mouseY,
            CallbackInfoReturnable<Tooltip> cir
    ) {
        var cpu = hitTestCpu(new Point(mouseX, mouseY));
        if (cpu == null) {
            return;
        }

        var tooltipLines = new ArrayList<Component>();
        tooltipLines.add(getCpuName(cpu));

        var coProcessors = cpu.coProcessors();
        if (coProcessors == 1) {
            tooltipLines.add(ButtonToolTips.CpuStatusCoProcessor.text(Tooltips.ofNumber(coProcessors))
                    .withStyle(ChatFormatting.GRAY));
        } else if (coProcessors > 1) {
            tooltipLines.add(ButtonToolTips.CpuStatusCoProcessors.text(Tooltips.ofNumber(coProcessors))
                    .withStyle(ChatFormatting.GRAY));
        }

        tooltipLines.add(ButtonToolTips.CpuStatusStorage.text(Ae2ByteDisplayFormatter.component(cpu.storage()))
                .withStyle(ChatFormatting.GRAY));

        var modeText = switch (cpu.mode()) {
            case PLAYER_ONLY -> ButtonToolTips.CpuSelectionModePlayersOnly.text();
            case MACHINE_ONLY -> ButtonToolTips.CpuSelectionModeAutomationOnly.text();
            default -> null;
        };
        if (modeText != null) {
            tooltipLines.add(modeText);
        }

        var currentJob = cpu.currentJob();
        if (currentJob != null) {
            tooltipLines.add(ButtonToolTips.CpuStatusCrafting.text(
                            Tooltips.ofAmount(currentJob))
                    .append(" ")
                    .append(currentJob.what().getDisplayName()));
            tooltipLines.add(ButtonToolTips.CpuStatusCraftedIn.text(
                            Tooltips.ofPercent(cpu.progress()),
                            Tooltips.ofDuration(cpu.elapsedTimeNanos(), TimeUnit.NANOSECONDS)));
        }

        cir.setReturnValue(new Tooltip(tooltipLines));
    }

    @Redirect(
            method = "drawBackgroundLayer",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;valueOf(I)Ljava/lang/String;"),
            remap = false
    )
    private String chexsonsaeutils$formatCompactCoProcessors(int coProcessors) {
        return Ae2CompactNumberFormatter.format(coProcessors);
    }
}
