package git.chexson.chexsonsaeutils.client.gui.patterntransmitter;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.StyleManager;
import git.chexson.chexsonsaeutils.menu.patterntransmitter.PatternTransmitterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.ArrayList;
import java.util.List;

/** 设备配置页：明确 A/B 方向、标记过滤、优先级及分页订单；实际物品不会被过滤槽吞入。 */
public final class PatternTransmitterScreen extends AEBaseScreen<PatternTransmitterMenu> {
    private final List<Button> configuration = new ArrayList<>();
    private Button pause;
    public PatternTransmitterScreen(PatternTransmitterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StyleManager.loadStyleDoc("/screens/pattern_transmitter.json"));
    }
    @Override protected void init() {
        super.init(); configuration.clear();
        configuration.add(addRenderableWidget(Button.builder(Component.literal("优先级 −"), b -> menu.setPriority(Math.max(-100000, menu.priority - 10)))
                .bounds(leftPos + 184, topPos + 28, 73, 20).build()));
        configuration.add(addRenderableWidget(Button.builder(Component.literal("优先级 ＋"), b -> menu.setPriority(Math.min(100000, menu.priority + 10)))
                .bounds(leftPos + 263, topPos + 28, 73, 20).build()));
        pause = addRenderableWidget(Button.builder(Component.literal("暂停"), b -> menu.action("pause")).bounds(leftPos + 184, topPos + 53, 73, 20).build());
        configuration.add(pause);
        configuration.add(addRenderableWidget(Button.builder(Component.literal("重试 / 确认网络"), b -> menu.action("retry"))
                .bounds(leftPos + 263, topPos + 53, 89, 20).build()));
        configuration.add(addRenderableWidget(Button.builder(Component.literal("取消生产"), b -> menu.action("cancel"))
                .bounds(leftPos + 263, topPos + 78, 89, 20).build()));
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> menu.setPage(menu.page - 1)).bounds(leftPos + 184, topPos + 212, 73, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> menu.setPage(menu.page + 1)).bounds(leftPos + 263, topPos + 212, 73, 20).build());
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender(); configuration.forEach(b -> b.active = menu.editable);
        if (pause != null) pause.setMessage(Component.literal(menu.paused ? "恢复接单" : "暂停接单"));
    }
    @Override public void drawFG(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        graphics.drawString(font, "背面 A → 正面 B", 8, 18, 0x404040, false);
        graphics.drawString(font, "状态：" + font.plainSubstrByWidth(menu.status, 132), 8, 38, 0x404040, false);
        graphics.drawString(font, "输出标记过滤（空表 = 全部）", 8, 49, 0x404040, false);
        graphics.drawString(font, "优先级 " + menu.priority + (menu.editable ? "" : " · 只读"), 184, 10, 0x404040, false);
        graphics.drawString(font, "远程订单 " + (menu.page + 1) + " / " + menu.pages, 8, 108, 0x404040, false);
        int row = 0;
        for (var line : menu.orders.split("\n")) {
            graphics.drawString(font, font.plainSubstrByWidth(line, 337), 8, 119 + row++ * 10, 0x404040, false);
        }
        graphics.drawString(font, "多来源分流；提交后固定来源", 184, 165, 0x707070, false);
        graphics.drawString(font, "取消生产后，缓冲仍交付至 B", 184, 185, 0x707070, false);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= leftPos + 8 && mouseX < leftPos + 172 && mouseY >= topPos + 36 && mouseY < topPos + 48)
            graphics.renderTooltip(font, font.split(Component.literal(menu.status), 260), mouseX, mouseY);
    }
}
