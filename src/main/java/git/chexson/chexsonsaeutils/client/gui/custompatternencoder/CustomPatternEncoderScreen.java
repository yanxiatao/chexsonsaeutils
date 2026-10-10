package git.chexson.chexsonsaeutils.client.gui.custompatternencoder;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.stacks.GenericStack;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.NumberEntryType;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.AECheckbox;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.client.gui.widgets.Scrollbar;
import git.chexson.chexsonsaeutils.menu.custompatternencoder.CustomPatternEncoderMenu;
import git.chexson.chexsonsaeutils.network.custompatternencoder.CustomPatternSlotChangePacket;

/**
 * 框架样板编码屏幕（原地编辑供应器原样板）。
 * <p>
 * 布局由 {@code assets/ae2/screens/custom_pattern_encoder.json} 定义：无输入/输出槽
 * （直接编辑供应器样板槽中的原样板）；下方 3 行可见的稀疏输入列表（每行 = 输入
 * 物品图标 + NumberEntryWidget 机器槽位输入，-1 = 未指定），右侧滚动条；中部为
 * 抽取槽位文本框（逗号分隔 CSV）。
 * <p>
 * 交互模式（照 advancedae）：行列表 + 每行输入控件 + 实时生效、无保存按钮——
 * 槽位修改经 onChange 立即发送 {@code CustomPatternSlotChangePacket} 回传服务端
 * 并写回供应器原样板，不存在"点了保存没反应"的中间态。
 * <p>
 * 数据流：服务端 Menu 推送 {@code CustomPatternEncoderUpdatePayload} →
 * menu.updateFromServer → 本屏幕在 updateBeforeRender 回显；用户输入经
 * CustomPatternSlotChangePacket / menu.setExtractSlots 回传服务端。
 */
public class CustomPatternEncoderScreen extends AEBaseScreen<CustomPatternEncoderMenu> {

    /** 可见行数与行距（行列表模式照 advancedae；2 行避免与下方抽取槽位区拥挤）。 */
    private static final int VISIBLE_ROWS = 2;

    private final Scrollbar scrollbar;
    private final NumberEntryWidget[] inputEntries = new NumberEntryWidget[VISIBLE_ROWS];
    private final AETextField extractSlotsField;
    /** 「突破堆叠上限」勾选框（init 创建：位置依赖 leftPos/topPos，resize 后重建）。 */
    private AECheckbox overflowStacksCheckbox;

    /** 上次回显的抽取槽位 CSV（服务端数据变化时才刷新文本框，避免输入循环）。 */
    private String lastSyncedExtractSlots = "";
    private boolean suppressExtractSlotsUpdate = false;
    /** B3 修复：每行上次回显的机器槽位值（回显 setLongValue 期间抑制 onChange，避免每帧发包循环）。 */
    private final int[] lastRenderedMapping = new int[VISIBLE_ROWS];
    private boolean suppressMappingUpdate = false;

    /** 上次刷新展示槽时的滚动偏移与稀疏输入快照（变化才回填，避免每帧写库存）。 */
    private int lastRefreshedScroll = -1;
    private List<GenericStack> lastRefreshedInputs = List.of();

    public CustomPatternEncoderScreen(CustomPatternEncoderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/custom_pattern_encoder.json"));
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

        // 返回按钮（需求：配置完成后返回来源供应器界面），置于左侧工具栏首位
        var backButton = new appeng.client.gui.widgets.IconButton(btn -> this.menu.backToProviderClient()) {
            @Override
            protected appeng.client.gui.Icon getIcon() {
                return appeng.client.gui.Icon.BACK;
            }
        };
        backButton.setMessage(Component.translatable("gui.chexsonsaeutils.custom_pattern_encoder.back"));
        this.addToLeftToolbar(backButton);
    }

    @Override
    public void init() {
        super.init();
        // 包序处理：OpenScreenPacket 已先到达，请求服务端立即同步（pendingInitialUpdate
        // 标志保证 resize 等重复 init 不会重新解码，避免重置玩家已配置的映射）
        this.menu.onUpdateRequested();

        // 「突破堆叠上限」勾选框：slot_hint 行右侧空白区（面板宽 200，x=96 起右侧留白）。
        // y=152 与 json 的 slot_hint（top 156）同行对齐；宽度按标签文本动态计算
        // （照 AE2 KeyTypeSelectionScreen 先例），高度用控件标准 SIZE。
        var label = Component.translatable("gui.chexsonsaeutils.custom_pattern_encoder.overflow_stacks");
        this.overflowStacksCheckbox = new AECheckbox(this.leftPos + 96, this.topPos + 152,
                24 + this.font.width(label), AECheckbox.SIZE, getStyle(), label);
        this.overflowStacksCheckbox.setChangeListener(() ->
                this.menu.setOverflowStacks(this.overflowStacksCheckbox.isSelected()));
        addRenderableWidget(this.overflowStacksCheckbox);
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
                PacketDistributor.sendToServer(new CustomPatternSlotChangePacket(input.what(), value)));
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

        // 有效原料行数：稀疏输入列表含 null 占位（长度 = 编码格数，尾部大量 null），
        // 空占位不构成列表行——取最后一个非 null 原料的索引+1，滚动范围与行可见性
        // 均按此值计算，否则原料之后会显示大量空行。
        int effectiveSize = 0;
        for (int i = 0; i < sparseInputs.size(); i++) {
            if (sparseInputs.get(i) != null) {
                effectiveSize = i + 1;
            }
        }

        int maxScroll = Math.max(0, effectiveSize - VISIBLE_ROWS);
        this.scrollbar.setRange(0, maxScroll, 2);
        if (this.scrollbar.getCurrentScroll() > maxScroll) {
            this.scrollbar.setCurrentScroll(maxScroll);
        }
        int scroll = this.scrollbar.getCurrentScroll();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int index = scroll + i;
            var entry = this.inputEntries[i];
            boolean visible = index < effectiveSize;
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

        // 突破开关回显：仅与服务端状态不同才 setSelected（防回显循环；
        // setSelected 不触发 changeListener，不会反向发包）
        if (this.overflowStacksCheckbox != null
                && this.overflowStacksCheckbox.isSelected() != this.menu.isOverflowStacks()) {
            this.overflowStacksCheckbox.setSelected(this.menu.isOverflowStacks());
        }

        // 输入展示槽刷新：滚动偏移或稀疏输入数据变化时回填虚拟只读槽
        // （图标/数量/悬停 tooltip 均由原生槽位渲染链处理）
        if (scroll != this.lastRefreshedScroll || !sparseInputs.equals(this.lastRefreshedInputs)) {
            this.lastRefreshedScroll = scroll;
            this.lastRefreshedInputs = sparseInputs;
            this.menu.refreshDisplaySlots(scroll);
        }
    }

    /**
     * 补画玩家物品栏槽位格子背景。
     * <p>
     * 动机：本界面布局使用 generatedBackground（BackgroundGenerator 只平铺底板
     * 纹理，不含槽位格子——原版格子在整张 background 贴图里），导致玩家背包/
     * 快捷栏只有物品没有格子。此处按容器类型筛选玩家背包容器的槽位，手绘
     * 原版风格 18x18 格子：中灰内芯 + 暗（左/上）亮（右/下）边。
     */
    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        for (var slot : this.menu.slots) {
            if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                continue;
            }
            int x = offsetX + slot.x;
            int y = offsetY + slot.y;
            guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF); // 右/下亮边底色
            guiGraphics.fill(x - 1, y - 1, x + 16, y + 16, 0xFF373737); // 左/上暗边
            guiGraphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);         // 中灰内芯
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