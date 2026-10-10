package git.chexson.chexsonsaeutils.client.gui.matrix;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.AppEng;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixInteraction;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixMenuLink;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 装配矩阵界面基类：ExtendedAE {@code GuiAssemblerMatrix} 的移植件，背景与布局连贴图一起抄。
 *
 * <p>对话框用 ExtendedAE 注入到 AE2 命名空间的 {@code textures/guis/assembler_matrix.png}，
 * 格子凹槽、搜索框、滚动条轨道都是烘焙像素，所以这里不再手画任何面板或槽框。
 * 滚动条走行、搜索框即时过滤、逐格命中涂绿未命中压暗、点击换算成 AE2 的
 * {@link InventoryActionPacket} 交给服务端落实，整套交互模型按移植件照搬。
 * 被迫适配四处：行内容由本地视图按需回填而不是开包全量镜像（一万六千格整份配方
 * 会撑爆连接）；命中集合来自服务端位图（样板解码在服务端，客户端没有配方）；
 * 格子坐标在 1.21.1 只能构造时给定，所以窗口重建放在 {@code updateBeforeRender}；
 * 参与滚动的行截断到最后一个有内容的行再加一条空行，见 {@link PatternInfo#listRows}。</p>
 */
public abstract class GuiAssemblerMatrix<T extends AEBaseMenu> extends AEBaseScreen<T> {

    private static final int ROW_HEIGHT = 18;
    private static final int SLOT_SIZE = 18;
    /** 移植件的滚动步长：一次滚轮两行。 */
    private static final int SCROLL_STEP = 2;
    /** 两次搜索上行之间的最小帧数，服务端要扫全量槽位，不能连打就刷。 */
    private static final int SEARCH_SEND_INTERVAL_FRAMES = 6;
    /** 背景图烘焙了四行样板格，少列的行数要用图里自带的空白面板条抹掉分隔线。 */
    private static final int BAKED_BANDS = 4;
    /** 与移植件一致：贴图为空白行的两条纯色面板带。 */
    private static final Rect2i EMPTY_ROW1 = new Rect2i(0, 203, 160, 16);
    private static final Rect2i EMPTY_ROW2 = new Rect2i(0, 219, 160, 18);
    /** 同一张图里玩家背包的一个空格子，供机器专属槽位借用来画框。 */
    private static final Rect2i SLOT_CELL = new Rect2i(7, 116, 18, 18);
    /** 命中绿、未命中压暗，与移植件的取值一致。 */
    private static final int SEARCH_MATCH_FILL = 0x8A00FF00;
    private static final int SEARCH_DIM = 0x6A000000;
    private static final ResourceLocation MATRIX_TEXTURE =
            AppEng.makeId("textures/guis/assembler_matrix.png");

    private final PatternMatrixMenuLink link;
    private final int columns;
    private final int visibleRows;
    private final int matrixLeft;
    private final int matrixTop;
    private final ArrayList<PatternRow> rows = new ArrayList<>();
    private final List<AssemblerMatrixSlot> cells = new ArrayList<>();
    private final int[] pendingSlots;

    private final Scrollbar scrollbar;
    private final AETextField searchField;

    private PatternInfo info = new PatternInfo(0, 1);
    private int listedChangeCount = -1;
    private int listedTotalSlots = -1;
    private int[] lastRequestedSlots = new int[0];
    private String sentQuery = "";
    private int searchSendCooldown;

    protected GuiAssemblerMatrix(
            T menu,
            Inventory playerInventory,
            Component title,
            ScreenStyle style,
            PatternMatrixMenuLink link,
            int columns,
            int visibleRows,
            int matrixLeft,
            int matrixTop
    ) {
        super(menu, playerInventory, title, style);
        this.link = link;
        this.columns = columns;
        this.visibleRows = visibleRows;
        this.matrixLeft = matrixLeft;
        this.matrixTop = matrixTop;
        this.pendingSlots = new int[columns * visibleRows];
        this.scrollbar = widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        this.searchField = widgets.addTextField("search");
        this.searchField.setResponder(str -> this.refreshList(link.matrixView()));
        this.searchField.setPlaceholder(Component.translatable("gui.chexsonsaeutils.matrix.search_hint"));
        // 滚轮在窗口任意位置都能滚动列表，与 AE2 终端一致
        this.scrollbar.setCaptureMouseWheel(true);
    }

    @Override
    protected void init() {
        super.init();
        this.setInitialFocus(this.searchField);
        this.resetScrollbar();
    }

    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        if (btn == 1 && this.searchField.isMouseOver(xCoord, yCoord)) {
            this.searchField.setValue("");
        }
        return super.mouseClicked(xCoord, yCoord, btn);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        PatternMatrixClientView view = link.matrixView();
        tickSearchUpload();
        if (view.getTotalSlots() != listedTotalSlots) {
            listedTotalSlots = view.getTotalSlots();
            this.info = new PatternInfo(view.getTotalSlots(), columns);
            this.listedChangeCount = -1;
        }
        if (view.changeCount() != listedChangeCount) {
            listedChangeCount = view.changeCount();
            // 内容一变就可能作废了某些格子的本体，去重集合必须跟着作废
            lastRequestedSlots = new int[0];
            refreshList(view);
        }
        rebindCells(view);
        requestPendingRealStacks(view);
    }

    /** 逐格重建可见窗口：1.21.1 的槽位坐标不可再改，所以窗口一动就换一批只读槽。 */
    private void rebindCells(PatternMatrixClientView view) {
        removeStaleCells();
        final int scrollLevel = scrollbar.getCurrentScroll();
        for (int i = 0; i < visibleRows; i++) {
            if (scrollLevel + i >= this.rows.size()) {
                continue;
            }
            var row = this.rows.get(scrollLevel + i);
            row.syncFrom(view);
            for (int col = 0; col < row.slots(); col++) {
                var slot = new AssemblerMatrixSlot(
                        row.inventory(),
                        col,
                        row.offset(),
                        col * SLOT_SIZE + matrixLeft,
                        i * ROW_HEIGHT + matrixTop
                );
                cells.add((AssemblerMatrixSlot) menu.addClientSideSlot(slot, null));
            }
        }
    }

    /** 同一菜单可能被重建后的界面复用（例如窗口缩放），先清掉上一批虚拟槽。 */
    private void removeStaleCells() {
        for (Slot slot : List.copyOf(menu.slots)) {
            if (slot instanceof AssemblerMatrixSlot cell) {
                menu.removeClientSideSlot(cell);
            }
        }
        cells.clear();
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        if (this.searchField.getValue().isEmpty()) {
            return;
        }
        PatternMatrixClientView view = link.matrixView();
        for (AssemblerMatrixSlot slot : cells) {
            fillRect(guiGraphics,
                    new Rect2i(slot.x, slot.y, SLOT_SIZE - 2, SLOT_SIZE - 2),
                    view.isMatched(slot.getActuallySlot())
                            ? SEARCH_MATCH_FILL
                            : SEARCH_DIM);
        }
    }

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        blitEmptyBands(guiGraphics, offsetX, offsetY);
    }

    /** 与移植件一致：列出的行不满烘焙行数时，用图里自带的纯色带盖掉多余的分隔线。 */
    private void blitEmptyBands(GuiGraphics guiGraphics, int offsetX, int offsetY) {
        int lastUsed = Math.min(rows.size(), visibleRows);
        boolean first = true;
        for (int band = lastUsed; band < BAKED_BANDS; band++) {
            if (first) {
                blit(guiGraphics, offsetX + matrixLeft, offsetY + ROW_HEIGHT * band + matrixTop, EMPTY_ROW1);
                first = false;
            } else {
                blit(guiGraphics, offsetX + matrixLeft, offsetY + ROW_HEIGHT * band + matrixTop - 2, EMPTY_ROW2);
            }
        }
    }

    /** 机器专属槽位（例如直连处理机的绑定槽）借用图里玩家背包的现成凹槽画框。 */
    protected final void blitSlotCell(GuiGraphics guiGraphics, int offsetX, int offsetY, Slot slot) {
        blit(guiGraphics, offsetX + slot.x - 1, offsetY + slot.y - 1, SLOT_CELL);
    }

    private void blit(GuiGraphics guiGraphics, int x, int y, Rect2i srcRect) {
        guiGraphics.blit(MATRIX_TEXTURE, x, y, srcRect.getX(), srcRect.getY(),
                srcRect.getWidth(), srcRect.getHeight());
    }

    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        if (slot instanceof AssemblerMatrixSlot cell) {
            ItemStack stack = cell.getRenderStack();
            if (!stack.isEmpty()) {
                guiGraphics.renderItem(stack, cell.x, cell.y);
                guiGraphics.renderItemDecorations(this.font, stack, cell.x, cell.y);
            }
            return;
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    protected void slotClicked(Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        if (slot instanceof AssemblerMatrixSlot machineSlot) {
            InventoryAction action = PatternMatrixInteraction.clickAction(
                    clickType,
                    mouseButton == 1,
                    getPlayer().getAbilities().instabuild
            );
            if (action != null) {
                final InventoryActionPacket p = new InventoryActionPacket(
                        action,
                        machineSlot.getActuallySlot(),
                        PatternMatrixInteraction.MATRIX_PATTERN_ID
                );
                PacketDistributor.sendToServer(p);
            }
            return;
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    private void resetScrollbar() {
        this.scrollbar.setHeight(visibleRows * ROW_HEIGHT - 2);
        this.scrollbar.setRange(0, this.rows.size() - visibleRows, SCROLL_STEP);
    }

    private void refreshList(PatternMatrixClientView view) {
        this.rows.clear();
        this.rows.addAll(this.info.listRows(view));
        this.resetScrollbar();
    }

    /** 当前窗口里的样板计数，过滤时附带命中数。 */
    protected Component statusLine() {
        PatternMatrixClientView view = link.matrixView();
        int usedSlots = view.getNonEmptySlots();
        int totalSlots = view.getTotalSlots();
        if (!view.filterActive()) {
            return Component.translatable("gui.chexsonsaeutils.matrix.pattern_count", usedSlots, totalSlots);
        }
        return Component.translatable(
                "gui.chexsonsaeutils.matrix.pattern_count_matched",
                view.matchCount(),
                usedSlots,
                totalSlots
        );
    }

    /** 只索取当前窗口里还没拿到本体的格子，索取集合不变时不重复上行。 */
    private void requestPendingRealStacks(PatternMatrixClientView view) {
        int pending = 0;
        for (AssemblerMatrixSlot cell : cells) {
            int globalSlot = cell.getActuallySlot();
            if (view.hasDisplayStack(globalSlot)
                    && !view.hasRealStack(globalSlot)
                    && pending < pendingSlots.length) {
                pendingSlots[pending++] = globalSlot;
            }
        }
        int[] slots = Arrays.copyOf(pendingSlots, pending);
        if (slots.length == 0 || Arrays.equals(slots, lastRequestedSlots)) {
            return;
        }
        lastRequestedSlots = slots;
        link.requestVisibleRealStacks(slots);
    }

    private void tickSearchUpload() {
        if (searchSendCooldown > 0) {
            searchSendCooldown--;
            return;
        }
        String query = searchField.getValue();
        if (query.equals(sentQuery)) {
            return;
        }
        sentQuery = query;
        searchSendCooldown = SEARCH_SEND_INTERVAL_FRAMES;
        link.requestSearch(query);
    }
}
