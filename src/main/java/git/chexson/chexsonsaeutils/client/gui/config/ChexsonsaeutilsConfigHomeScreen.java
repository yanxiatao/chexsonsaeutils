package git.chexson.chexsonsaeutils.client.gui.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

import java.util.Objects;

/** 模组配置入口主页。 */
@OnlyIn(Dist.CLIENT)
public final class ChexsonsaeutilsConfigHomeScreen extends Screen {
    private static final int BUTTON_WIDTH = 220;

    private final Screen parent;
    private final ModContainer modContainer;
    private final PlanningAlgorithmOrderClientBridge orderBridge;

    public ChexsonsaeutilsConfigHomeScreen(
            Screen parent,
            ModContainer modContainer,
            PlanningAlgorithmOrderClientBridge orderBridge
    ) {
        super(Component.translatable("gui.chexsonsaeutils.config_home.title"));
        this.parent = parent;
        this.modContainer = Objects.requireNonNull(modContainer, "modContainer");
        this.orderBridge = Objects.requireNonNull(orderBridge, "orderBridge");
    }

    @Override
    protected void init() {
        int left = (this.width - BUTTON_WIDTH) / 2;
        int top = this.height / 2 - 34;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.chexsonsaeutils.config_home.general"),
                        button -> openGeneralConfig())
                .bounds(left, top, BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.chexsonsaeutils.config_home.planning_order"),
                        button -> openOrderScreen())
                .bounds(left, top + 28, BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds((this.width - Button.SMALL_WIDTH) / 2, this.height - 28,
                        Button.SMALL_WIDTH, Button.DEFAULT_HEIGHT)
                .build());
    }

    private void openGeneralConfig() {
        Minecraft.getInstance().setScreen(new ConfigurationScreen(this.modContainer, this));
    }

    private void openOrderScreen() {
        Minecraft.getInstance().setScreen(new PlanningAlgorithmOrderScreen(this, this.orderBridge));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("gui.chexsonsaeutils.config_home.choose"),
                this.width / 2,
                42,
                0xA0A0A0
        );
    }
}
