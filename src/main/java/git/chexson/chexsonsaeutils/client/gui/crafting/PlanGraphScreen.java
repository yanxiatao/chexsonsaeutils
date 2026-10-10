package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.api.client.AEKeyRendering;
import appeng.client.gui.AESubScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.menu.me.crafting.CraftConfirmMenu;
import git.chexson.chexsonsaeutils.crafting.status.*;
import git.chexson.chexsonsaeutils.client.gui.matrix.PatternSearchMatcher;
import git.chexson.chexsonsaeutils.client.integration.jech.JechPatternSearchMatcherImpl;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import java.util.*;

/** 与确认菜单共用生命周期的独立合成图，返回时保留预览与 CPU 选择。 */
public final class PlanGraphScreen extends AESubScreen<CraftConfirmMenu, CraftConfirmScreen> {
    private PlanGraph sourceGraph = PlanGraph.EMPTY;
    private PlanGraphMaterials materials = PlanGraphMaterials.create(PlanGraph.EMPTY);
    private PlanGraph graph = PlanGraph.EMPTY;
    private PlanGraphLayout layout = PlanGraphLayout.create(graph);
    private List<PlanGraphRoutes.Route> routes = List.of(), overviewRoutes = List.of();
    private List<Integer> lineOrder = List.of();
    private PlanGraphHighlight highlight = PlanGraphHighlight.create(PlanGraph.EMPTY, -1, false);
    private boolean chainHighlight, pressedShift;
    private final PatternSearchMatcher matcher = ModList.get().isLoaded("jecharacters") ? JechHolder.create() : String::contains;
    private static final class JechHolder { static PatternSearchMatcher create() { return new JechPatternSearchMatcherImpl(); } }
    private EditBox search;
    private final List<Integer> matches = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private int selected = -1, matchIndex = -1;
    private double zoom = 1, panX = 0, panY = 40;
    private double zoomTarget = 1, panTargetX = 0, panTargetY = 40;
    private long lastFrame = System.nanoTime();
    private boolean dragging;
    private int pressedNode = -1;
    private double dragDistance;
    private boolean exporting;
    private boolean detailsOpen;
    private int detailScroll;
    private record Detail(String text, int target) {}
    private final List<Detail> details = new ArrayList<>();
    private int canvasTop = 62;
    private String notice = "";
    public PlanGraphScreen(CraftConfirmScreen parent) { super(parent, "/screens/chexson_plan_graph.json"); }
    @Override public List<net.minecraft.client.renderer.Rect2i> getExclusionZones() {
        return List.of(new net.minecraft.client.renderer.Rect2i(0, 0, width, height));
    }
    private static Component text(String key) { return Component.translatable("gui.chexsonsaeutils.graph." + key); }
    @Override protected void init() {
        super.init(); clearWidgets();
        String query = search == null ? "" : search.getValue();
        search = addRenderableWidget(new EditBox(font, 8, 8, Math.max(40, width - 16), 20, text("search")));
        search.setHint(text("search")); search.setResponder(this::find);
        int w = Math.max(20, (width - 24) / 8);
        button(8, w, "previous", () -> next(-1)); button(10 + w, w, "next", () -> next(1));
        button(12 + 2 * w, w, "missing", this::missing); button(14 + 3 * w, w, "fit", this::fit);
        button(16 + 4 * w, w, "target", () -> { if (!graph.nodes().isEmpty()) focus(0); });
        button(18 + 5 * w, w, "copy", () -> { if (selected >= 0) minecraft.keyboardHandler.setClipboard(amount(graph.nodes().get(selected)).toString()); });
        button(20 + 6 * w, w, "export", this::export); button(22 + 7 * w, w, "back", this::returnToParent);
        refresh(); search.setValue(query); fit();
    }
    private void button(int x, int w, String key, Runnable action) {
        addRenderableWidget(new PlanGraphButton(x, 32, w, text(key), action));
    }
    private void refresh() {
        var summary = menu.getPlan();
        var next = summary instanceof EnhancedCraftingPlanSummary e ? e.chexsonsaeutils$graph() : PlanGraph.EMPTY;
        if (next == sourceGraph) return; sourceGraph = next; materials = PlanGraphMaterials.create(next); graph = materials.graph(); layout = PlanGraphLayout.create(graph); routes = PlanGraphRoutes.create(graph, layout); overviewRoutes = PlanGraphRoutes.createOverview(graph, layout); select(-1, false);
        labels.clear();
        for (var node : graph.nodes()) labels.add(node.key().getDisplayName().getString());
        notice = graph.problem(); find(search.getValue()); fit();
    }
    private String name(int i) { return labels.get(i); }
    private void find(String query) {
        matches.clear(); matchIndex = -1; query = query.trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) for (int i = 0; i < graph.nodes().size(); i++) {
            var n = graph.nodes().get(i); String candidate = (name(i) + " " + n.key().getId()).toLowerCase(Locale.ROOT);
            boolean match = true; for (var word : query.split("\\s+")) if (!matcher.contains(candidate, word)) { match = false; break; }
            if (match) matches.add(i);
        }
        if (!matches.isEmpty()) next(1);
    }
    private void next(int direction) { if (matches.isEmpty()) return;
        matchIndex = Math.floorMod(matchIndex + direction, matches.size()); focus(matches.get(matchIndex)); }
    private void missing() { for (int j = 1; j <= graph.nodes().size(); j++) {
        int i = Math.floorMod(selected + j, graph.nodes().size());
        if (graph.nodes().get(i).missing().signum() > 0) { focus(i); return; }
    } notice = text("no_missing").getString(); }
    private void focus(int i) { chainHighlight = false; selected = i; var p = layout.positions().get(i);
        select(i, true); zoom = Math.max(zoom, 1); panX = canvasRight() / 2.0 - (p.x() + 15) * zoom;
        panY = (height + canvasTop) / 2.0 - (p.y() + 15) * zoom; resetMotion(); }
    private int canvasRight() { return detailsOpen && selected >= 0 ? width - Math.min(230, width / 2) : width; }
    private void select(int i, boolean open) {
        selected = i; highlight = PlanGraphHighlight.create(graph, selected, chainHighlight); lineOrder = PlanGraphLinePixels.order(graph, highlight); detailsOpen = open && i >= 0; detailScroll = 0; details.clear();
        if (i < 0) return;
        var n = graph.nodes().get(i);
        details.add(new Detail(text(n.pattern() ? "times" : "quantity").getString() + ": " + amount(n), -1));
        if (!n.pattern()) {
            details.add(new Detail(text("amount").getString() + ": " + n.amount(), -1));
            details.add(new Detail(text("stored").getString() + ": " + n.stored(), -1));
            details.add(new Detail(text("short").getString() + ": " + n.missing(), -1));
        }
        for (int edge = 0; edge < graph.edges().size(); edge++) {
            var e = graph.edges().get(edge);
            if (e.from() != i && e.to() != i) continue;
            int neighbor = e.from() == i ? e.to() : e.from();
            details.add(new Detail(relationText(edge, i), neighbor));
        }
        for (var recipe : materials.recipesFor(i)) {
            details.add(new Detail(text("pattern").getString() + ": " + recipe.key().getDisplayName().getString()
                    + " · " + text("times").getString() + ": " + recipe.amount(), -1));
        }
    }
    private static java.math.BigInteger amount(PlanGraph.Node n) { return n.pattern() || n.amount().signum() > 0 ? n.amount() : n.stored().add(n.missing()); }
    private void fit() {
        zoom = Math.max(0.025, Math.min(1.5, Math.min((canvasRight() - 24.0) / layout.width(), (height - canvasTop - 36.0) / layout.height())));
        panX = (canvasRight() - layout.width() * zoom) / 2; panY = canvasTop + (height - canvasTop - 24 - layout.height() * zoom) / 2; resetMotion();
    }
    private void resetMotion() { zoomTarget = zoom; panTargetX = panX; panTargetY = panY; lastFrame = System.nanoTime(); }
    private void animateZoom() {
        long now = System.nanoTime(); double elapsed = Math.min(0.1, (now - lastFrame) / 1_000_000_000.0); lastFrame = now;
        double step = 1 - Math.exp(-22 * elapsed);
        zoom += (zoomTarget - zoom) * step; panX += (panTargetX - panX) * step; panY += (panTargetY - panY) * step;
        if (Math.abs(zoomTarget - zoom) < 0.00001 && Math.abs(panTargetX - panX) < 0.01 && Math.abs(panTargetY - panY) < 0.01) {
            zoom = zoomTarget; panX = panTargetX; panY = panTargetY;
        }
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        refresh(); animateZoom(); g.fill(0, 0, width, height, 0xff111820);
        g.fill(0, 0, width, canvasTop, 0xff202a33);
        g.enableScissor(0, canvasTop, canvasRight(), height - 24);
        double detail = PlanGraphZoom.routes(zoom);
        for (int i : lineOrder) {
            var e = graph.edges().get(i); boolean active = highlight.edges().contains(i);
            int color = active ? 0xffffd166 : selected >= 0 ? 0xff283641
                    : e.remainder() ? 0xff69bca2 : e.alternative() ? 0xff9875bd : 0xff71899d;
            drawRoute(g, overviewRoutes.get(i), active ? color : 0xff344956, 1 - detail, false);
            drawRoute(g, routes.get(i), color, detail, true);
        }
        g.flush();
        for (int i = 0; i < graph.nodes().size(); i++) {
            var p = layout.positions().get(i);
            int x = (int) Math.round(panX + p.x() * zoom), y = (int) Math.round(panY + p.y() * zoom);
            int size = Math.max(1, (int) Math.round(PlanGraphLayout.NODE_SIZE * zoom));
            if (x + size < 0 || x > canvasRight() || y + size + 12 < canvasTop || y > height - 24) continue;
            var n = graph.nodes().get(i); boolean active = highlight.nodes().contains(i);
            int border = i == selected ? 0xffffd166 : active && chainHighlight ? 0xffdfb85e
                    : selected >= 0 && !active ? 0xff34404c : n.missing().signum() > 0 ? 0xffed6565
                    : p.cycle() ? 0xffbd8cfa : 0xff687d96;
            int dot = Math.min(4, size), expanded = (int) Math.round(dot + (size - dot) * detail);
            int offset = (size - expanded) / 2;
            g.fill(x + offset, y + offset, x + offset + expanded, y + offset + expanded, border);
            if (expanded > 2) g.fill(x + offset + 1, y + offset + 1, x + offset + expanded - 1, y + offset + expanded - 1,
                    PlanGraphZoom.color(0xff243040, detail));
            double iconOpacity = PlanGraphZoom.icons(zoom), amountOpacity = PlanGraphZoom.amounts(zoom);
            if (iconOpacity > 0) {
                int iconX = x + (size - 16) / 2, iconY = y + (size - 16) / 2;
                AEKeyRendering.drawInGui(minecraft, g, iconX, iconY, n.key());
                if (iconOpacity < 1) {
                    g.flush(); g.pose().pushPose(); g.pose().translate(0, 0, 300);
                    g.fill(iconX, iconY, iconX + 16, iconY + 16, PlanGraphZoom.color(0xff243040, 1 - iconOpacity));
                    g.pose().popPose();
                }
            }
            if (amountOpacity >= 0.04) {
                String number = compact(amount(n)); int labelX = x + (size - font.width(number)) / 2;
                g.fill(labelX - 2, y + size + 1, labelX + font.width(number) + 2, y + size + 12, PlanGraphZoom.color(0xff111820, amountOpacity));
                g.drawString(font, number, labelX, y + size + 2, PlanGraphZoom.color(selected >= 0 && !active ? 0xff718391
                        : n.missing().signum() > 0 ? 0xffff9090 : 0xffffffff, amountOpacity), false);
            }
            if (i == 0 && detail > 0.5) g.fill(x + 2, y + 2, x + 4, y + 4, 0xffe6c45c);
        }
        g.flush(); g.disableScissor();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, 1);
        for (var widget : renderables) widget.render(g, mouseX, mouseY, partial);
        drawDetails(g, mouseX, mouseY);
        g.drawString(font, text(zoom < 0.65 ? "overview_help" : "help").getString(), 8, height - 20, 0xffa8bfd0, false);
        g.drawString(font, notice.isEmpty() ? (graph.nodes().isEmpty() ? text("empty").getString() : matches.isEmpty() ? "" : (matchIndex + 1) + "/" + matches.size()) : notice, 8, 54, 0xffffd166, false);
        int hit = hit(mouseX, mouseY);
        if (hit >= 0) { var n = graph.nodes().get(hit); var lines = new ArrayList<Component>();
            lines.add(Component.literal(name(hit))); lines.add(Component.literal(text(n.pattern() ? "times" : "amount").getString() + ": " + n.amount()));
            if (!n.pattern()) { lines.add(Component.literal(text("stored").getString() + ": " + n.stored())); lines.add(Component.literal(text("short").getString() + ": " + n.missing())); }
            int shown = 0;
            for (int edge = 0; edge < graph.edges().size(); edge++) {
                var e = graph.edges().get(edge);
                if (e.from() != hit && e.to() != hit) continue;
                if (++shown > 14) { lines.add(Component.literal("…")); break; }
                lines.add(Component.literal(relationText(edge, hit)));
            }
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }
    private void drawRoute(GuiGraphics g, PlanGraphRoutes.Route route, int color, double opacity, boolean arrows) {
        if (opacity <= 0 || !route.visible(-panX / zoom, (canvasTop - panY) / zoom,
                (canvasRight() - panX) / zoom, (height - 24 - panY) / zoom)) return;
        for (var rect : PlanGraphLinePixels.rasterize(route, panX, panY, zoom, arrows))
            g.fill(rect.left(), rect.top(), rect.right(), rect.bottom(), PlanGraphZoom.color(color, opacity));
    }
    private String relationText(int index, int node) {
        var edge = graph.edges().get(index); var relation = materials.relations().get(index);
        return (edge.from() == node ? "→ " : "← ") + name(edge.from() == node ? edge.to() : edge.from())
                + " · " + text("quantity").getString() + ": " + relation.input() + " → " + relation.output()
                + (edge.remainder() ? " [" + text("remainder").getString() + "]" : "")
                + (edge.alternative() ? " [" + text("alternative").getString() + "]" : "");
    }
    private static String compact(java.math.BigInteger n) { String s = n.toString(); return s.length() <= 6 ? s : s.charAt(0) + "." + s.substring(1, 3) + "e" + (s.length() - 1); }
    private int detailRows() { return Math.max(1, (height - canvasTop - 64) / 16); }
    private void drawDetails(GuiGraphics g, int mouseX, int mouseY) {
        if (!detailsOpen || selected < 0) return;
        int left = canvasRight();
        g.fill(left, canvasTop, width, height - 24, 0xff1c2630); g.fill(left, canvasTop, left + 1, height - 24, 0xff526778);
        g.drawString(font, font.plainSubstrByWidth(name(selected), width - left - 32), left + 8, canvasTop + 8, 0xffffffff, false);
        g.fill(width - 22, canvasTop + 3, width - 3, canvasTop + 21, 0xff354957);
        g.drawCenteredString(font, "×", width - 13, canvasTop + 7, 0xffd7e4ed);
        g.drawString(font, text(graph.nodes().get(selected).pattern() ? "pattern" : "material"), left + 8, canvasTop + 23, 0xff91adbf, false);
        Detail hover = null;
        for (int i = detailScroll; i < Math.min(details.size(), detailScroll + detailRows()); i++) {
            var row = details.get(i); int y = canvasTop + 36 + (i - detailScroll) * 16;
            boolean over = mouseX >= left + 4 && mouseX < width - 4 && mouseY >= y && mouseY < y + 16;
            if (over) { g.fill(left + 4, y, width - 4, y + 16, 0xff304554); hover = row; }
            int x = left + 8;
            if (row.target >= 0) { AEKeyRendering.drawInGui(minecraft, g, x, y, graph.nodes().get(row.target).key()); x += 20; }
            g.drawString(font, font.plainSubstrByWidth(row.text, width - x - 8), x, y + 4, row.target >= 0 ? 0xffbddbe9 : 0xffd7e4ed, false);
        }
        g.flush();
        if (details.size() > detailRows()) {
            int track = detailRows() * 16, thumb = Math.max(8, track * detailRows() / details.size());
            int y = canvasTop + 36 + (track - thumb) * detailScroll / Math.max(1, details.size() - detailRows());
            g.fill(width - 3, y, width - 1, y + thumb, 0xff7c9aae);
        }
        if (hover != null) g.renderComponentTooltip(font, List.of(Component.literal(hover.text)), mouseX, mouseY);
    }
    private int hit(double x, double y) {
        if (x < 0 || x >= canvasRight() || y < canvasTop || y >= height - 24) return -1;
        int size = Math.max(1, (int) Math.round(PlanGraphLayout.NODE_SIZE * zoom));
        for (int i = 0; i < layout.positions().size(); i++) { var p = layout.positions().get(i);
            int left = (int) Math.round(panX + p.x() * zoom), top = (int) Math.round(panY + p.y() * zoom);
            if (x >= left && x < left + size && y >= top && y < top + size) return i; } return -1;
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (detailsOpen && x >= canvasRight() && y >= canvasTop) { detailScroll = Math.max(0, Math.min(Math.max(0, details.size() - detailRows()), detailScroll - (int) Math.signum(dy) * 3)); return true; }
        if (y < canvasTop || y >= height - 24) return super.mouseScrolled(x, y, dx, dy);
        double old = zoomTarget; zoomTarget = Math.max(0.025, Math.min(3, zoomTarget * Math.pow(1.15, dy)));
        panTargetX = x - (x - panTargetX) * zoomTarget / old; panTargetY = y - (y - panTargetY) * zoomTarget / old; return true;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (detailsOpen && x >= canvasRight() && y >= canvasTop) {
            if (x >= width - 24 && y < canvasTop + 20) { detailsOpen = false; return true; }
            int row = (int) (y - canvasTop - 36) / 16 + detailScroll;
            if (y >= canvasTop + 36 && row >= 0 && row < details.size() && details.get(row).target >= 0) focus(details.get(row).target);
            return true;
        }
        if (y >= canvasTop && y < height - 24 && (button == 0 || button == 2)) {
            pressedNode = button == 0 ? hit(x, y) : -1; pressedShift = hasShiftDown(); dragDistance = 0; dragging = true; return true;
        }
        if (button == 1 && search.isMouseOver(x, y)) { search.setValue(""); return true; }
        // 子屏只接收画布与控件，不将空白区域点击交给容器的丢弃物品逻辑。
        for (var child : children()) if (child.mouseClicked(x, y, button)) { setFocused(child); setDragging(button == 0); return true; }
        return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging) { panX += dx; panY += dy; panTargetX += dx; panTargetY += dy; dragDistance += Math.abs(dx) + Math.abs(dy); return true; } return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int b) {
        if (dragging) { if (dragDistance < 3 && pressedNode >= 0) {
                if (pressedShift) { chainHighlight = true; select(pressedNode, false); }
                else focus(pressedNode);
            } else if (dragDistance < 3 && b == 0) { chainHighlight = false; select(-1, false); } dragging = false; pressedNode = -1; return true; }
        return super.mouseReleased(x, y, b);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) { returnToParent(); return true; }
        if (key == 257 || key == 335) { next(hasShiftDown() ? -1 : 1); return true; }
        if (key == 70 && hasControlDown()) { setFocused(search); search.setFocused(true); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { returnToParent(); }
    private void export() {
        if (exporting || graph.nodes().isEmpty()) return;
        exporting = true;
        var snapshot = graph; var positions = layout; var directory = minecraft.gameDirectory.toPath();
        final Map<appeng.api.stacks.AEKey, java.awt.image.BufferedImage> icons;
        final long clipboardOwner = com.sun.jna.Platform.isWindows()
                ? org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(minecraft.getWindow().getWindow()) : 0;
        try { icons = PlanGraphIcons.capture(snapshot.nodes().stream().map(PlanGraph.Node::key).toList()); }
        catch (java.io.IOException | RuntimeException error) {
            exporting = false; com.mojang.logging.LogUtils.getLogger().error("截取合成图图标失败", error);
            notice = text("failed").getString(); return;
        }
        notice = text("exporting").getString();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (var result = PlanGraphPng.export(snapshot, positions, directory, icons)) {
                try { PlanGraphClipboard.copy(result.image(), clipboardOwner); }
                catch (java.io.IOException | RuntimeException | LinkageError error) {
                    // 用户要求复制失败静默跳过；仅留调试日志，不影响已完成的 PNG 导出。
                    com.mojang.logging.LogUtils.getLogger().debug("合成图图片剪贴板写入失败", error);
                }
                return result.path();
            } catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
        }, net.minecraft.Util.ioPool()).whenComplete((path, error) -> minecraft.execute(() -> {
            exporting = false;
            if (error != null) { com.mojang.logging.LogUtils.getLogger().error("导出合成图失败", error); notice = text("failed").getString(); }
            else { notice = text("saved").getString() + " " + path;
                if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.literal(notice).withStyle(style -> style.withClickEvent(
                        new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_FILE, path.toAbsolutePath().toString()))));
            }
        }));
    }
}
