package git.chexson.chexsonsaeutils.client.gui.widgets;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import appeng.api.stacks.AmountFormat;
import appeng.client.Point;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.Icon;
import appeng.client.gui.Tooltip;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.Color;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.definitions.AEParts;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.GuiText;
import appeng.core.localization.Tooltips;
import git.chexson.chexsonsaeutils.client.gui.Ae2ByteDisplayFormatter;
import git.chexson.chexsonsaeutils.client.gui.Ae2CompactNumberFormatter;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class ParallelCpuSelectionList implements ICompositeWidget {

    private static final int ROWS = 6;

    private final Blitter background;
    private final Blitter buttonBg;
    private final Blitter buttonBgSelected;
    private final ParallelCraftingCPUMenu menu;
    private final Color textColor;
    private final int selectedColor;
    private final Scrollbar scrollbar;
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);

    public ParallelCpuSelectionList(ParallelCraftingCPUMenu menu, Scrollbar scrollbar, ScreenStyle style) {
        this.menu = menu;
        this.scrollbar = scrollbar;
        this.background = style.getImage("cpuList");
        this.buttonBg = style.getImage("cpuListButton");
        this.buttonBgSelected = style.getImage("cpuListButtonSelected");
        this.textColor = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR);
        this.selectedColor = style.getColor(PaletteColor.SELECTION_COLOR).toARGB();
        this.scrollbar.setCaptureMouseWheel(false);
    }

    @Override
    public void setPosition(Point position) {
        this.bounds = new Rect2i(position.getX(), position.getY(), bounds.getWidth(), bounds.getHeight());
    }

    @Override
    public void setSize(int width, int height) {
        this.bounds = new Rect2i(bounds.getX(), bounds.getY(), width, height);
    }

    @Override
    public Rect2i getBounds() {
        return bounds;
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        scrollbar.onMouseWheel(mousePos, delta);
        return true;
    }

    @Override
    public void updateBeforeRender() {
        int hiddenRows = Math.max(0, menu.cpuList.cpus().size() - ROWS);
        scrollbar.setRange(0, hiddenRows, ROWS / 3);
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        var cpu = hitTestCpu(new Point(mouseX, mouseY));
        if (cpu == null) {
            return null;
        }

        var tooltipLines = new ArrayList<Component>();
        tooltipLines.add(getCpuName(cpu));

        tooltipLines.add(ButtonToolTips.CpuStatusStorage.text(Ae2ByteDisplayFormatter.component(cpu.storage()))
                .withStyle(ChatFormatting.GRAY));

        int coProcessors = cpu.coProcessors();
        if (coProcessors == 1) {
            tooltipLines.add(ButtonToolTips.CpuStatusCoProcessor.text(Tooltips.ofNumber(coProcessors))
                    .withStyle(ChatFormatting.GRAY));
        } else if (coProcessors > 1) {
            tooltipLines.add(ButtonToolTips.CpuStatusCoProcessors.text(Tooltips.ofNumber(coProcessors))
                    .withStyle(ChatFormatting.GRAY));
        }

        Component modeText = switch (cpu.mode()) {
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
                            Tooltips.ofAmount(currentJob)).append(" ").append(currentJob.what().getDisplayName()));
            tooltipLines.add(ButtonToolTips.CpuStatusCraftedIn.text(
                    Tooltips.ofPercent(cpu.progress()),
                    Tooltips.ofDuration(cpu.elapsedTimeNanos(), TimeUnit.NANOSECONDS)));
        }

        return new Tooltip(tooltipLines);
    }

    @Override
    public boolean onMouseUp(Point mousePos, int button) {
        var cpu = hitTestCpu(mousePos);
        if (cpu != null) {
            menu.selectCpu(cpu.serial());
            return true;
        }
        return false;
    }

    @Nullable
    private ParallelCraftingCPUMenu.CraftingCpuListEntry hitTestCpu(Point mousePos) {
        int relX = mousePos.getX() - bounds.getX() - 9;
        if (relX < 0 || relX >= buttonBg.getSrcWidth()) {
            return null;
        }

        int relY = mousePos.getY() - bounds.getY() - 19;
        int buttonIdx = scrollbar.getCurrentScroll() + relY / (buttonBg.getSrcHeight() + 1);
        if (relY % (buttonBg.getSrcHeight() + 1) == buttonBg.getSrcHeight()) {
            return null;
        }
        if (relY < 0 || buttonIdx >= menu.cpuList.cpus().size()) {
            return null;
        }

        var cpus = menu.cpuList.cpus();
        return buttonIdx >= 0 && buttonIdx < cpus.size() ? cpus.get(buttonIdx) : null;
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        int x = bounds.getX() + this.bounds.getX();
        int y = bounds.getY() + this.bounds.getY();
        background.dest(x, y, this.bounds.getWidth(), this.bounds.getHeight()).blit(guiGraphics);

        x += 9;
        y += 19;

        var pose = guiGraphics.pose();
        var font = Minecraft.getInstance().font;
        var cpus = menu.cpuList.cpus().subList(
                Mth.clamp(scrollbar.getCurrentScroll(), 0, menu.cpuList.cpus().size()),
                Mth.clamp(scrollbar.getCurrentScroll() + ROWS, 0, menu.cpuList.cpus().size()));
        for (var cpu : cpus) {
            if (cpu.serial() == menu.getSelectedCpuSerial()) {
                buttonBgSelected.dest(x, y).blit(guiGraphics);
            } else {
                buttonBg.dest(x, y).blit(guiGraphics);
            }

            var name = getCpuName(cpu);
            pose.pushPose();
            pose.translate(x + 3, y + 3, 0);
            pose.scale(0.8f, 0.8f, 1);
            guiGraphics.drawString(font, name, 0, 0, textColor.toARGB(), false);
            pose.popPose();

            var infoBar = new InfoBar();

            var currentJob = cpu.currentJob();
            if (currentJob != null) {
                infoBar.add(Icon.CRAFT_HAMMER, 0.6f);
                infoBar.addSpace(2);
                var craftAmt = currentJob.what().formatAmount(currentJob.amount(), AmountFormat.SLOT);
                infoBar.add(craftAmt, textColor.toARGB(), 0.6f);
                infoBar.addSpace(1);
                infoBar.add(currentJob.what(), 0.6f);

                int progress = (int) (cpu.progress() * (buttonBg.getSrcWidth() - 1));
                guiGraphics.fill(
                        x + 1,
                        y + buttonBg.getSrcHeight() - 2,
                        x + progress,
                        y + buttonBg.getSrcHeight() - 1,
                        menu.getSelectedCpuSerial() == cpu.serial() ? 0xFFFFFFFF : (0xFF000000 + selectedColor));
            } else {
                infoBar.add(Icon.LEVEL_ITEM, 0.6f);
                infoBar.addSpace(1);

                infoBar.add(Ae2ByteDisplayFormatter.format(cpu.storage()), textColor.toARGB(), 0.6f);
                infoBar.addSpace(1);

                if (cpu.coProcessors() > 0) {
                    infoBar.add(Icon.BLOCKING_MODE_NO, 0.6f);
                    infoBar.add(Ae2CompactNumberFormatter.format(cpu.coProcessors()), textColor.toARGB(), 0.6f);
                    infoBar.addSpace(1);
                }

                switch (cpu.mode()) {
                    case PLAYER_ONLY -> infoBar.add(AEParts.TERMINAL, 0.6f);
                    case MACHINE_ONLY -> infoBar.add(AEParts.EXPORT_BUS, 0.6f);
                }
            }

            infoBar.render(guiGraphics, x + 2, y + buttonBg.getSrcHeight() - 12);
            y += buttonBg.getSrcHeight() + 1;
        }
    }

    private Component getCpuName(ParallelCraftingCPUMenu.CraftingCpuListEntry cpu) {
        return cpu.name() != null ? cpu.name() : GuiText.CPUs.text().append(String.format(" #%d", cpu.serial()));
    }
}
