package git.chexson.chexsonsaeutils.client.gui.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** 带上下移动按钮和拖放支持的原版列表。 */
@OnlyIn(Dist.CLIENT)
final class PlanningAlgorithmOrderList
        extends ContainerObjectSelectionList<PlanningAlgorithmOrderList.Row> {
    private static final int ROW_HEIGHT = 24;

    private final PlanningAlgorithmOrderScreen screen;
    private boolean editable;
    private Row dragSource;
    private Row dragTarget;

    PlanningAlgorithmOrderList(
            Minecraft minecraft,
            int width,
            int height,
            int y,
            PlanningAlgorithmOrderScreen screen
    ) {
        super(minecraft, width, height, y, ROW_HEIGHT);
        this.screen = screen;
        this.centerListVertically = false;
    }

    void rebuild(FixedPlanningAlgorithmOrder order, boolean editable) {
        double scroll = this.getScrollAmount();
        this.clearEntries();
        List<PlanningAlgorithm> algorithms = order.order();
        for (int index = 0; index < algorithms.size(); index++) {
            this.addEntry(new Row(algorithms.get(index), index));
        }
        this.editable = editable;
        this.setScrollAmount(scroll);
    }

    void setEditable(boolean editable) {
        this.editable = editable;
        for (Row row : this.children()) {
            row.updateButtons();
        }
    }

    @Override
    public int getRowWidth() {
        return Math.min(360, this.width - 34);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Row row = this.getEntryAtPosition(mouseX, mouseY);
        if (button == 0 && this.editable && row != null) {
            this.dragSource = row;
            this.dragTarget = row;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        return handled || this.dragSource != null;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && this.dragSource != null) {
            Row row = this.getEntryAtPosition(mouseX, mouseY);
            if (row != null) {
                this.dragTarget = row;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.dragSource != null) {
            Row source = this.dragSource;
            Row target = this.dragTarget;
            this.dragSource = null;
            this.dragTarget = null;
            if (target != null && source.orderIndex != target.orderIndex) {
                this.screen.moveByDrag(source.orderIndex, target.orderIndex);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final PlanningAlgorithm algorithm;
        private final int orderIndex;
        private final Button upButton;
        private final Button downButton;

        private int left;
        private int top;
        private int width;

        Row(PlanningAlgorithm algorithm, int orderIndex) {
            this.algorithm = algorithm;
            this.orderIndex = orderIndex;
            this.upButton = Button.builder(Component.literal("↑"), button -> {
                PlanningAlgorithmOrderList.this.dragSource = null;
                PlanningAlgorithmOrderList.this.dragTarget = null;
                screen.moveUp(this.algorithm);
            }).size(24, Button.DEFAULT_HEIGHT).tooltip(Tooltip.create(
                    Component.translatable("gui.chexsonsaeutils.planning_order.move_up"))).build();
            this.downButton = Button.builder(Component.literal("↓"), button -> {
                PlanningAlgorithmOrderList.this.dragSource = null;
                PlanningAlgorithmOrderList.this.dragTarget = null;
                screen.moveDown(this.algorithm);
            }).size(24, Button.DEFAULT_HEIGHT).tooltip(Tooltip.create(
                    Component.translatable("gui.chexsonsaeutils.planning_order.move_down"))).build();
            updateButtons();
        }

        void updateButtons() {
            this.upButton.active = editable && screen.canMoveUp(this.algorithm);
            this.downButton.active = editable && screen.canMoveDown(this.algorithm);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.upButton, this.downButton);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.upButton, this.downButton);
        }

        @Override
        public void render(
                GuiGraphics guiGraphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick
        ) {
            this.left = left;
            this.top = top;
            this.width = width;
            boolean draggedOver = PlanningAlgorithmOrderList.this.dragSource != null
                    && PlanningAlgorithmOrderList.this.dragTarget == this;
            int background = draggedOver ? 0x6655AAFF : hovering ? 0x44202020 : 0x22000000;
            guiGraphics.fill(left, top, left + width, top + height - 2, background);
            Component algorithmName = Component.translatable(this.algorithm.translationKey());
            guiGraphics.drawString(
                    minecraft.font,
                    Component.translatable(
                            "gui.chexsonsaeutils.planning_order.algorithm_row",
                            this.orderIndex + 1,
                            algorithmName
                    ),
                    left + 8,
                    top + 7,
                    editable ? 0xFFFFFF : 0xA0A0A0,
                    false
            );
            positionButton(this.upButton, left + width - 54, top + 1);
            positionButton(this.downButton, left + width - 27, top + 1);
            this.upButton.render(guiGraphics, mouseX, mouseY, partialTick);
            this.downButton.render(guiGraphics, mouseX, mouseY, partialTick);
            if (hovering && mouseX < left + width - 58) {
                guiGraphics.renderTooltip(
                        minecraft.font,
                        Component.translatable(this.algorithm.descriptionKey()),
                        mouseX,
                        mouseY
                );
            }
        }

        private void positionButton(Button button, int x, int y) {
            button.setX(x);
            button.setY(y);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= this.left && mouseX <= this.left + this.width
                    && mouseY >= this.top && mouseY < this.top + ROW_HEIGHT;
        }
    }
}
