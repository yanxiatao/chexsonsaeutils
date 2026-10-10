package git.chexson.chexsonsaeutils.client.gui.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Objects;

/** 服务端任务计算算法优先级的纯客户端编辑页。 */
@OnlyIn(Dist.CLIENT)
public final class PlanningAlgorithmOrderScreen extends Screen {
    private final Screen parent;
    private final PlanningAlgorithmOrderClientBridge bridge;

    private PlanningAlgorithmOrderSnapshot serverSnapshot = PlanningAlgorithmOrderSnapshot.disconnected();
    private FixedPlanningAlgorithmOrder order = FixedPlanningAlgorithmOrder.defaults();
    private PlanningAlgorithmOrderList orderList;
    private Button saveButton;
    private Button restoreButton;
    private Button refreshButton;
    private boolean loaded;
    private boolean busy;
    private Component status = Component.translatable("gui.chexsonsaeutils.planning_order.status.loading");

    public PlanningAlgorithmOrderScreen(Screen parent, PlanningAlgorithmOrderClientBridge bridge) {
        super(Component.translatable("gui.chexsonsaeutils.planning_order.title"));
        this.parent = parent;
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    protected void init() {
        int listBottom = Math.max(76, this.height - 62);
        this.orderList = new PlanningAlgorithmOrderList(
                Minecraft.getInstance(), this.width, listBottom - 48, 48, this);
        this.addRenderableWidget(this.orderList);

        int buttonWidth = 74;
        int gap = 4;
        int totalWidth = buttonWidth * 4 + gap * 3;
        int left = (this.width - totalWidth) / 2;
        int buttonY = this.height - 28;
        this.saveButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.chexsonsaeutils.planning_order.save"), button -> save())
                .bounds(left, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        left += buttonWidth + gap;
        this.restoreButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.chexsonsaeutils.planning_order.restore"),
                        button -> restoreDefaults())
                .bounds(left, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        left += buttonWidth + gap;
        this.refreshButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.chexsonsaeutils.planning_order.refresh"), button -> refresh())
                .bounds(left, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());
        left += buttonWidth + gap;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left, buttonY, buttonWidth, Button.DEFAULT_HEIGHT).build());

        rebuildOrderList();
        updateButtons();
        if (!this.loaded && !this.busy) {
            refresh();
        }
    }

    private void refresh() {
        if (!this.bridge.hasActiveConnection()) {
            applySnapshot(PlanningAlgorithmOrderSnapshot.disconnected(),
                    "gui.chexsonsaeutils.planning_order.status.disconnected");
            return;
        }
        this.busy = true;
        this.status = Component.translatable("gui.chexsonsaeutils.planning_order.status.loading");
        updateButtons();
        this.bridge.requestState(result -> runOnClientThread(() -> {
            this.busy = false;
            applySnapshot(result.snapshot(), result.messageKey());
        }));
    }

    private void save() {
        if (!canEdit() || !hasChanges()) {
            return;
        }
        this.busy = true;
        this.status = Component.translatable("gui.chexsonsaeutils.planning_order.status.saving");
        updateButtons();
        PlanningAlgorithmOrderSnapshot submitted = this.order.snapshot(this.serverSnapshot.revision(), true);
        this.bridge.save(submitted, result -> runOnClientThread(() -> {
            this.busy = false;
            applySnapshot(result.snapshot(), result.messageKey());
        }));
    }

    private void restoreDefaults() {
        if (!canEdit()) {
            return;
        }
        this.order.restoreDefaults();
        this.status = Component.translatable("gui.chexsonsaeutils.planning_order.status.defaults_pending");
        rebuildOrderList();
        updateButtons();
    }

    private void applySnapshot(PlanningAlgorithmOrderSnapshot snapshot, String messageKey) {
        this.serverSnapshot = snapshot;
        this.order = FixedPlanningAlgorithmOrder.from(snapshot);
        this.loaded = true;
        this.status = messageKey == null || messageKey.isBlank()
                ? describeSnapshot(snapshot)
                : Component.translatable(messageKey);
        rebuildOrderList();
        updateButtons();
    }

    private Component describeSnapshot(PlanningAlgorithmOrderSnapshot snapshot) {
        return Component.translatable(
                snapshot.editable()
                        ? "gui.chexsonsaeutils.planning_order.status.loaded_editable"
                        : "gui.chexsonsaeutils.planning_order.status.loaded_read_only",
                snapshot.revision()
        );
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(() -> {
            if (this.minecraft != null) {
                action.run();
            }
        });
    }

    boolean moveUp(PlanningAlgorithm algorithm) {
        if (!canEdit() || !this.order.moveUp(algorithm)) {
            return false;
        }
        markChanged();
        return true;
    }

    boolean moveDown(PlanningAlgorithm algorithm) {
        if (!canEdit() || !this.order.moveDown(algorithm)) {
            return false;
        }
        markChanged();
        return true;
    }

    boolean moveByDrag(int fromIndex, int toIndex) {
        if (!canEdit() || !this.order.move(fromIndex, toIndex)) {
            return false;
        }
        markChanged();
        return true;
    }

    private void markChanged() {
        this.status = Component.translatable("gui.chexsonsaeutils.planning_order.status.changed");
        rebuildOrderList();
        updateButtons();
    }

    boolean canMoveUp(PlanningAlgorithm algorithm) {
        return canEdit() && this.order.canMoveUp(algorithm);
    }

    boolean canMoveDown(PlanningAlgorithm algorithm) {
        return canEdit() && this.order.canMoveDown(algorithm);
    }

    private boolean canEdit() {
        return this.loaded && !this.busy && this.bridge.hasActiveConnection() && this.serverSnapshot.editable();
    }

    private boolean hasChanges() {
        return this.loaded && !this.order.hasSameOrder(this.serverSnapshot);
    }

    private void rebuildOrderList() {
        if (this.orderList != null) {
            this.orderList.rebuild(this.order, canEdit());
        }
    }

    private void updateButtons() {
        if (this.saveButton == null) {
            return;
        }
        this.saveButton.active = canEdit() && hasChanges();
        this.restoreButton.active = canEdit() && !this.order.isDefault();
        this.refreshButton.active = !this.busy && this.bridge.hasActiveConnection();
        if (this.orderList != null) {
            this.orderList.setEditable(canEdit());
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        int statusColor = canEdit() ? 0xA0A0A0 : 0xFFAA55;
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, 30, statusColor);
    }
}
