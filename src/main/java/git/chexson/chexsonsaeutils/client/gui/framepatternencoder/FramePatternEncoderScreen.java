package git.chexson.chexsonsaeutils.client.gui.framepatternencoder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.NumberEntryType;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.client.gui.widgets.Scrollbar;
import git.chexson.chexsonsaeutils.menu.framepatternencoder.FramePatternEncoderMenu;
import git.chexson.chexsonsaeutils.network.framepatternencoder.FramePatternSlotChangePacket;

/**
 * 框架样板编码屏幕（advancedae AdvPatternEncoderScreen 的移植改造）。
 * <p>
 * 布局由 {@code assets/ae2/screens/frame_pattern_encoder.json} 定义：左上为
 * 输入样板槽与输出框架样板槽；下方 3 行可见的稀疏输入列表（每行 = 输入
 * 物品图标 + NumberEntryWidget 机器槽位输入，-1 = 未指定），右侧滚动条；中部为
 * 抽取槽位文本框（逗号分隔 CSV）。
 * <p>
 * 交互模式（照 advancedae）：行列表 + 每行输入控件 + 实时生效、无保存按钮——
 * 槽位修改经 onChange 立即发送 {@code FramePatternSlotChangePacket} 回传服务端
 * 并重新编码输出槽，不存在"点了保存没反应"的中间态。
 * <p>
 * 数据流：服务端 Menu 推送 {@code FramePatternEncoderUpdatePayload} →
 * menu.updateFromServer → 本屏幕在 updateBeforeRender 回显；用户输入经
 * FramePatternSlotChangePacket / menu.setExtractSlots 回传服务端。
 */
public class FramePatternEncoderScreen extends AEBaseScreen<FramePatternEncoderMenu> {

    /** 可见行数与行距（行列表模式照 advancedae）。 */
    private static final int VISIBLE_ROWS = 3;
    private static final int ROW_SPACING = 2;
    /**
     * 行高 = NumberEntryWidget 组件高 62 + 行距 2。advancedae 行高 18 是其自定义
     * 方向按钮行；NumberEntryWidget 的 +/- 两排按钮布局写死 62px 高（populateScreen），
     * 无法压入 18px 行，行高按控件物理尺寸适配。
     */
    private static final int ROW_HEIGHT = 62 + ROW_SPACING;
    /** 行列表锚点（照 advancedae：图标列 x=18；Y 因本界面左上槽位区占位而下移至 88）。 */
    private static final int LIST_ANCHOR_X = 18;
    private static final int LIST_ANCHOR_Y = 88;

    private final Scrollbar scrollbar;
    private final NumberEntryWidget[] inputEntries = new NumberEntryWidget[VISIBLE_ROWS];
    private final AETextField extractSlotsField;

    /** 上次回显的抽取槽位 CSV（服务端数据变化时才刷新文本框，避免输入循环）。 */
    private String lastSyncedExtractSlots = "";
    private boolean suppressExtractSlotsUpdate = false;
    /** B3 修复：每行上次回显的机器槽位值（回显 setLongValue 期间抑制 onChange，避免每帧发包循环）。 */
    private final int[] lastRenderedMapping = new int[VISIBLE_ROWS];
    private boolean suppressMappingUpdate = false;

    public FramePatternEncoderScreen(FramePatternEncoderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/frame_pattern_encoder.json"));
        this.scrollbar = widgets.addScrollBar("scrollbar", Scrollbar.SMALL);
        this.scrollbar.setRange(0, 0, VISIBLE_ROWS);

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            final int row = i;
            var entry = widgets.addNumberEntryWidget("input_entry_" + row, NumberEntryType.UNITLESS);
            entry.setTextFieldStyle(getStyle().getWidget("input_entry_" + row + "_input"));
            entry.setMinValue(-1);
            entry.setMaxValue(80); // 9x9 机器槽位范围 0-80（-1 = 未指定）
            entry.setOnChange(() -> saveSlotMapping(row));
            // 初始禁用状态由 init 后第一次 updateBeforeRender 的 entry.setActive(visible) 接管
            // （构造器内 setActive 会在 NumberEntryWidget.buttons 初始化前触发 NPE）
            inputEntries[row] = entry;
        }

        this.extractSlotsField = widgets.addTextField("extract_slots_input");
        this.extractSlotsField.setMaxLength(64);
        this.extractSlotsField.setResponder(this::onExtractSlotsChanged);
    }

    @Override
    public void init() {
        super.init();
        // 包序处理：OpenScreenPacket 已先到达，请求服务端立即同步（pendingInitialUpdate
        // 标志保证 resize 等重复 init 不会重新解码，避免重置玩家已配置的映射）
        this.menu.onUpdateRequested();
    }

    /** 行内 NumberEntryWidget 变更：把可见行号换算为稀疏输入序号后发送槽位变更包。 */
    private void saveSlotMapping(int row) {
        if (this.suppressMappingUpdate) {
            return;
        }
        int index = this.scrollbar.getCurrentScroll() + row;
        var sparseInputs = this.menu.getSparseInputs();
        if (index >= sparseInputs.size()) {
            return;
        }
        var input = sparseInputs.get(index);
        if (input == null) {
            return;
        }
        this.inputEntries[row].getIntValue().ifPresent(value ->
                PacketDistributor.sendToServer(new FramePatternSlotChangePacket(input.what(), value)));
    }

    /** 抽取槽位文本框变更：回传服务端（非法输入忽略，服务端解析失败时沿用上次合法值）。 */
    private void onExtractSlotsChanged(String text) {
        if (this.suppressExtractSlotsUpdate) {
            return;
        }
        try {
            this.menu.setExtractSlots(
                    text.isBlank() ? new int[0]
                            : java.util.Arrays.stream(text.split(",")).map(String::trim)
                                    .mapToInt(Integer::parseInt).toArray()
            );
        } catch (NumberFormatException ignored) {
            // 玩家输入非数字：忽略本次变更，不发送请求
        }
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        var sparseInputs = this.menu.getSparseInputs();
        var mapping = this.menu.getSlotMapping();

        int maxScroll = Math.max(0, sparseInputs.size() - VISIBLE_ROWS);
        this.scrollbar.setRange(0, maxScroll, 2);
        if (this.scrollbar.getCurrentScroll() > maxScroll) {
            this.scrollbar.setCurrentScroll(maxScroll);
        }
        int scroll = this.scrollbar.getCurrentScroll();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int index = scroll + i;
            var entry = this.inputEntries[i];
            boolean visible = index < sparseInputs.size();
            entry.setActive(visible);
            // B3 修复：仅当回显值变化时 setLongValue，且回显期间抑制 onChange，
            // 否则 setLongValue → textField responder → onChange → 每帧发包循环
            if (visible && index < mapping.length) {
                int value = mapping[index];
                if (this.lastRenderedMapping[i] != value) {
                    this.lastRenderedMapping[i] = value;
                    this.suppressMappingUpdate = true;
                    entry.setLongValue(value);
                    this.suppressMappingUpdate = false;
                }
            }
        }

        // 抽取槽位回显（仅服务端数据变化时刷新）
        String extractCsv = toCsv(this.menu.getExtractSlots());
        if (!extractCsv.equals(this.lastSyncedExtractSlots)) {
            this.lastSyncedExtractSlots = extractCsv;
            this.suppressExtractSlotsUpdate = true;
            this.extractSlotsField.setValue(extractCsv);
            this.suppressExtractSlotsUpdate = false;
        }
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);
        var sparseInputs = this.menu.getSparseInputs();
        int scroll = this.scrollbar.getCurrentScroll();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int index = scroll + i;
            if (index >= sparseInputs.size()) {
                break;
            }
            var input = sparseInputs.get(index);
            if (input == null) {
                continue;
            }
            int rowY = LIST_ANCHOR_Y + i * ROW_HEIGHT;
            // 相对坐标：renderLabels 前已有 translate(leftPos, topPos)，drawFG 内不得再加窗口偏移
            // 每行结构（照 advancedae）：行首输入物品图标 + 右侧 NumberEntryWidget 槽位输入
            guiGraphics.renderItem(input.what().wrapForDisplayOrFilter(), LIST_ANCHOR_X, rowY + 2);
            guiGraphics.drawString(this.font, "x" + input.amount(), LIST_ANCHOR_X + 2, rowY + 22, 0x404040);
        }
    }

    private static String toCsv(int[] slots) {
        if (slots.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int slot : slots) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(slot);
        }
        return sb.toString();
    }
}