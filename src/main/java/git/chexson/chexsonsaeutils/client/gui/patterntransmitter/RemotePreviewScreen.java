package git.chexson.chexsonsaeutils.client.gui.patterntransmitter;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 不更换服务端菜单的预览子屏；支持全来源搜索、问题过滤、分页、详细材料滚动与精确数量复制。 */
public final class RemotePreviewScreen extends Screen {
    private static final AtomicLong QUERIES = new AtomicLong();
    private final Screen parent;
    private final int menuId;
    private EditBox search;
    private String searchText = "";
    private boolean problemsOnly;
    private Response response;
    private long query;
    private int page;
    private int selected;
    private int detailScroll;
    private int listScroll;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private String message = "正在计算来源计划…";
    public RemotePreviewScreen(Screen parent, int menuId) { super(Component.literal("远程合成预览")); this.parent = parent; this.menuId = menuId; }
    @Override protected void init() {
        panelWidth = Math.min(700, width - 20); panelHeight = Math.min(420, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        search = new EditBox(font, left + 8, top + 28, panelWidth - 16, 20, Component.literal("搜索来源或产物"));
        search.setMaxLength(128); search.setValue(searchText); addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> { searchText = search.getValue(); page = 0; request(); })
                .bounds(left + 8, top + 52, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("仅问题"), b -> { problemsOnly = !problemsOnly; page = 0; request(); b.setMessage(Component.literal(problemsOnly ? "全部状态" : "仅问题")); })
                .bounds(left + 64, top + 52, 64, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> request(true)).bounds(left + 134, top + 52, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(left + panelWidth - 78, top + 52, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { page = Math.max(0, page - 1); request(); })
                .bounds(left + 8, top + panelHeight - 26, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { if (response != null) page = Math.min(response.pages() - 1, page + 1); request(); })
                .bounds(left + 82, top + panelHeight - 26, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制精确数量"), b -> { if (response != null && !response.rows().isEmpty()) minecraft.keyboardHandler.setClipboard(response.rows().get(selected).amount()); })
                .bounds(left + panelWidth - 124, top + panelHeight - 26, 116, 20).build());
        request();
    }
    private void request() {
        request(false);
    }
    private void request(boolean refresh) {
        query = QUERIES.incrementAndGet(); message = "正在计算来源计划…";
        PacketDistributor.sendToServer(new Request(menuId, query, page, searchText, problemsOnly, refresh));
    }
    /** nonce 与菜单共同隔离旧回复，菜单切换后旧页不能覆盖新计划。 */
    public void accept(Response value) {
        if (value.menuId() != menuId || value.queryId() != query) return;
        response = value; page = value.page(); selected = 0; detailScroll = 0; listScroll = 0; message = value.message();
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu.containerId != menuId) minecraft.setScreen(null);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 257 || key == 335) && search.isFocused()) { searchText = search.getValue(); page = 0; request(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        int column = Math.max(120, panelWidth / 2);
        if (button == 0 && response != null && !response.rows().isEmpty() && x >= left + 8 && x < left + column
                && y >= top + 92 && y < top + panelHeight - 44) {
            selected = Math.min(response.rows().size() - 1, listScroll + (int) (y - top - 92) / 31); detailScroll = 0; return true;
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (response != null && !response.rows().isEmpty()) {
            if (x < left + panelWidth / 2) {
                listScroll = Math.max(0, Math.min(Math.max(0, response.rows().size() - Math.max(1, (panelHeight - 136) / 31)), listScroll - (int) dy));
                return true;
            }
            var lines = wrappedDetails(response.rows().get(selected));
            detailScroll = Math.max(0, Math.min(Math.max(0, lines.size() - (panelHeight - 136) / 10), detailScroll - (int) dy * 3));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }
    private List<net.minecraft.util.FormattedCharSequence> wrappedDetails(Row row) {
        var lines = new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (var line : row.details()) lines.addAll(font.split(Component.literal(line), Math.max(60, panelWidth / 2 - 26)));
        return lines;
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xffe0e0e0);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 23, 0xff304a58);
        graphics.drawString(font, title, left + 8, top + 8, 0xffffff, false);
        graphics.drawString(font, font.plainSubstrByWidth(message, panelWidth - 16), left + 8, top + 78, 0x404040, false);
        int column = panelWidth / 2;
        graphics.fill(left + column, top + 91, left + column + 1, top + panelHeight - 44, 0xff909090);
        if (response != null) {
            for (int i = listScroll; i < response.rows().size(); i++) {
                var row = response.rows().get(i); int y = top + 92 + (i - listScroll) * 31;
                if (y + 30 > top + panelHeight - 44) break;
                if (i == selected) graphics.fill(left + 6, y, left + column - 5, y + 30, 0xffc1d7df);
                graphics.drawString(font, font.plainSubstrByWidth(row.source(), column - 20), left + 9, y + 2, 0x404040, false);
                graphics.drawString(font, font.plainSubstrByWidth(row.output() + " × " + row.amount(), column - 20), left + 9, y + 12, 0x202020, false);
                graphics.drawString(font, font.plainSubstrByWidth(row.status(), column - 20), left + 9, y + 22, row.status().contains("缺料") ? 0x982020 : 0x305070, false);
            }
            if (!response.rows().isEmpty()) {
                var lines = wrappedDetails(response.rows().get(selected));
                for (int i = detailScroll; i < lines.size() && (i - detailScroll + 1) * 10 < panelHeight - 136; i++)
                    graphics.drawString(font, lines.get(i), left + column + 9, top + 96 + (i - detailScroll) * 10, 0x404040, false);
            }
            graphics.drawString(font, (response.page() + 1) + " / " + response.pages() + " · 两栏可滚动", left + 8, top + panelHeight - 38, 0x404040, false);
        }
        super.render(graphics, mouseX, mouseY, partial);
    }
}
