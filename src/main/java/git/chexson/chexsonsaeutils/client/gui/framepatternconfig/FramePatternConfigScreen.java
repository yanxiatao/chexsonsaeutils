package git.chexson.chexsonsaeutils.client.gui.framepatternconfig;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.NumberEntryType;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.client.gui.widgets.Scrollbar;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigMenu;

/**
 * 框架样板配置屏幕。
 * <p>
 * 布局由 {@code assets/ae2/screens/frame_pattern_config.json} 定义：左上为
 * 输入处理样板槽与输出框架样板槽；下方 3 行可见的稀疏输入列表（每行一个
 * NumberEntryWidget 输入机器槽位，-1 = 未指定），右侧滚动条；中部为
 * 抽取槽位文本框（逗号分隔 CSV）；确认按钮用当前映射重新编码输出槽。
 * <p>
 * 数据流：服务端 Menu 推送 {@code FramePatternConfigUpdatePayload} →
 * menu.updateFromServer → 本屏幕在 updateBeforeRender 回显；用户输入经
 * menu.setSlotMapping/setExtractSlots/confirm 客户端动作回传服务端。
 */
public class FramePatternConfigScreen extends AEBaseScreen<FramePatternConfigMenu> {

    /** 可见行数与行距（与布局 json 的 widget 位置保持一致）。 */
    private static final int VISIBLE_ROWS = 3;
    private static final int ROW_HEIGHT = 64;
    private static final int ROW_X = 8;
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

    public FramePatternConfigScreen(FramePatternConfigMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/frame_pattern_config.json"));
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

        IconButton confirmButton = new IconButton(btn -> this.menu.confirm()) {
            @Override
            protected Icon getIcon() {
                return Icon.ENTER;
            }
        };
        confirmButton.setMessage(Component.translatable("gui.chexsonsaeutils.frame_pattern_config.confirm"));
        widgets.add("confirm", confirmButton);
    }

    /** 行内 NumberEntryWidget 变更：把可见行号换算为稀疏输入序号后回传服务端。 */
    private void saveSlotMapping(int row) {
        if (this.suppressMappingUpdate) {
            return;
        }
        int index = this.scrollbar.getCurrentScroll() + row;
        if (index >= this.menu.getSparseInputs().size()) {
            return;
        }
        this.inputEntries[row].getIntValue().ifPresent(value -> this.menu.setSlotMapping(index, value));
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
            guiGraphics.renderItem(input.what().wrapForDisplayOrFilter(), ROW_X, rowY + 2);
            guiGraphics.drawString(this.font, "x" + input.amount(), ROW_X + 2, rowY + 22, 0x404040);
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