package git.chexson.chexsonsaeutils.parts;

import appeng.api.config.FuzzyMode;
import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Multi-Level Emitter 配置界面
 * 使用 AE2 标准样式系统，同时支持动态组件和滚动条
 */
public class MultiLevelEmitterScreen extends UpgradeableScreen<MultiLevelEmitterMenu> {

    // 内容区域配置
    private static final int CONTENT_TOP = 15;  // 输入框和配置槽位的顶部位置
    private static final int CONTENT_HEIGHT = 76; // 限制在玩家背包栏上方
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_VISIBLE_ROWS = CONTENT_HEIGHT / ROW_HEIGHT;
    
    // 位置配置
    private static final int COMPARISON_X = 10;  // 比较模式按钮 X 位置（输入框左方）
    private static final int COMPARISON_WIDTH = 12;  // 比较模式按钮宽度
    private static final int SLOT_X = 137;  // 配置槽位 X 位置
    private static final int INPUT_X = 23;  // 输入框 X 位置
    private static final int INPUT_WIDTH = 103;
    private static final int LOGIC_X = 126;  // 逻辑按钮 X 位置（输入框后面）
    private static final int LOGIC_WIDTH = 10;  // 逻辑按钮宽度（缩小以避免与槽位重合）
    
    // 滚动条配置
    private static final int SCROLLBAR_X = 155;  // 配置槽位右边（137 + 18）
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_HEIGHT = CONTENT_HEIGHT;
    private static final int SCROLLBAR_TOP = CONTENT_TOP;
    
    // 动态控件列表
    private final List<EditBox> thresholdInputs = new ArrayList<>();
    private final List<Button> comparisonButtons = new ArrayList<>();
    private final List<Button> logicButtons = new ArrayList<>();
    private final List<ScrollableRow> rows = new ArrayList<>();
    
    // 滚动状态
    private int scrollOffset = 0;
    private boolean isDraggingScroll = false;
    private int lastConfiguredItemCount = -1;  // 用于检测配置物品数量变化
    private int framesSinceDataSync = 0;  // 数据同步后的帧计数器
    
    // 工具栏按钮
    private final SettingToggleButton<RedstoneMode> redstoneMode;
    private final SettingToggleButton<FuzzyMode> fuzzyMode;
    private final SettingToggleButton<YesNo> craftingMode;

    public MultiLevelEmitterScreen(MultiLevelEmitterMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 创建工具栏按钮
        // 使用自定义的红石模式按钮，以支持自定义的 tooltip
        this.redstoneMode = new CustomRedstoneModeButton(
                Settings.REDSTONE_EMITTER, RedstoneMode.LOW_SIGNAL);
        this.addToLeftToolbar(this.redstoneMode);

        this.fuzzyMode = new ServerSettingToggleButton<>(
                Settings.FUZZY_MODE, FuzzyMode.IGNORE_ALL);
        this.addToLeftToolbar(this.fuzzyMode);

        this.craftingMode = new ServerSettingToggleButton<>(
                Settings.CRAFT_VIA_REDSTONE, YesNo.NO);
        this.addToLeftToolbar(this.craftingMode);
    }
    
    /**
     * 自定义红石模式按钮，支持多物品发信器的自定义 tooltip
     */
    private static class CustomRedstoneModeButton extends ServerSettingToggleButton<RedstoneMode> {
        public CustomRedstoneModeButton(appeng.api.config.Setting<RedstoneMode> setting, RedstoneMode val) {
            super(setting, val);
        }
        
        @Override
        public java.util.List<net.minecraft.network.chat.Component> getTooltipMessage() {
            var currentValue = getCurrentValue();
            var tooltip = super.getTooltipMessage();
            
            // 替换为多物品发信器的自定义 tooltip
            if (currentValue == RedstoneMode.HIGH_SIGNAL) {
                return java.util.List.of(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.redstone_mode.high_signal.tooltip"));
            } else if (currentValue == RedstoneMode.LOW_SIGNAL) {
                return java.util.List.of(Component.translatable("gui.chexsonsaeutils.multi_level_emitter.redstone_mode.low_signal.tooltip"));
            }
            
            return tooltip;
        }
    }

    @Override
    protected void init() {
        super.init();
        
        // 不在这里创建控件，等待数据同步后在 updateBeforeRender 中创建
    }
    
    /**
     * 计算配置槽中实际配置的物品数量
     * @return 已配置的物品数量
     */
    private int calculateConfiguredItemCount() {
        return MultiLevelEmitterUtils.calculateConfiguredItemCount(menu.getHost().getConfig());
    }
    
    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();

        // 计算实际的配置物品数量（从配置槽位中计算）
        int actualConfiguredItemCount = calculateConfiguredItemCount();
        
        // 重新计算期望的行数（使用实际数量）
        int expectedRows = actualConfiguredItemCount + 1;
        

        
        // 检查数据是否已同步
        boolean dataSynced = menu.dataSynced;
        
        // 延迟刷新逻辑：在数据同步后等待几帧再刷新控件
        if (dataSynced) {
            framesSinceDataSync++;
        }
        
        // 在数据同步后的前几帧，强制刷新控件
        boolean initialRefresh = dataSynced && framesSinceDataSync <= 3 && actualConfiguredItemCount > 0;
        
        // 检测强制刷新标志
        if (menu.forceRefresh) {
    
            menu.forceRefresh = false;
            // 直接更新所有输入框的值（从 Part 中读取）
            for (int i = 0; i < rows.size(); i++) {
                ScrollableRow row = rows.get(i);
                if (row != null && row.input != null) {
                                      long thresholdFromPart = menu.getHost().getReportingValueForSlot(i);
                                      String newValue = String.valueOf(thresholdFromPart);
                                      String currentValue = row.input.getValue();
                                      if (!currentValue.equals(newValue)) {
                                          row.input.setValue(newValue);
                                          row.input.setHighlightPos(newValue.length());
                                      }
                                      // 更新 Menu 的 thresholds 映射（避免触发 setResponder）
                                      menu.thresholds.put(i, thresholdFromPart);
                                      }
            }
            return;
        }
        
                                // 检测配置物品数量是否减少（通过比较实际数量和上次记录的数量）
        
                                boolean itemCountDecreased = (actualConfiguredItemCount < lastConfiguredItemCount && lastConfiguredItemCount > 0);
        
                                if (itemCountDecreased) {
        

        
                                    // 由于删除物品时会移动后面的物品，所以需要更新所有有效的输入框
        
                                    // 但只更新到新的物品数量+1（n+1行）
        
                                    int rowsToUpdate = Math.min(rows.size(), actualConfiguredItemCount + 1);
        
                                    for (int i = 0; i < rowsToUpdate; i++) {
        
                                        ScrollableRow row = rows.get(i);
        
                                        if (row != null && row.input != null) {
        
                                            long thresholdFromPart = menu.getHost().getReportingValueForSlot(i);
        
                                            String newValue = String.valueOf(thresholdFromPart);
        
                                            String currentValue = row.input.getValue();
        
                                            if (!currentValue.equals(newValue)) {
        
                                                row.input.setValue(newValue);
        
                                                row.input.setHighlightPos(newValue.length());
        

        
                                            }
        
                                                                // 更新 Menu 的 thresholds 映射（确保下次刷新时使用正确的值）
        
                                                                if (!Objects.equals(menu.thresholds.get(i), thresholdFromPart)) {
        
                                                                    menu.thresholds.put(i, thresholdFromPart);
        
    
        
                                                                }
        
                                        }
        
                                    }
        
                                    lastConfiguredItemCount = actualConfiguredItemCount;
        
                                }
        
                                
        
                                // 重新计算期望的行数（使用实际数量）
        
                                expectedRows = actualConfiguredItemCount + 1;
        
                                
        
                                // 检查是否需要刷新控件
        
                                if (!dataSynced || actualConfiguredItemCount != lastConfiguredItemCount || rows.size() != expectedRows || initialRefresh) {
        
                            
        
                                    lastConfiguredItemCount = actualConfiguredItemCount;
        
                                    updateDynamicControls();
        
                                } else if (!itemCountDecreased) {
        
                                    // 只有在物品数量没有减少时，才执行常规的更新逻辑
        
                                    // 行数没有变化，更新现有输入框的值（只更新实际需要的行）
        
                                    int rowsToUpdate = Math.min(rows.size(), actualConfiguredItemCount + 1);
        
                                    for (int i = 0; i < rowsToUpdate; i++) {
        
                                        ScrollableRow row = rows.get(i);
        
                                        if (row != null && row.input != null) {
        
                                            // 从 Part 中读取最新的阈值（而不是从 Menu 的 thresholds 数组）
        
                                            long latestThreshold = menu.getThreshold(i);
        
                                            String currentValue = row.input.getValue();
        
                                            String newValue = String.valueOf(latestThreshold);
        

        
                                            if (!currentValue.equals(newValue)) {
        
                                                row.input.setValue(newValue);
        
                                                // 更新光标位置，确保文本正确显示
        
                                                row.input.setHighlightPos(newValue.length());
        
                                            }
        
                                                                                        // 更新 Menu 的 thresholds 映射（确保下次刷新时使用正确的值）
                                            
                                                                                        if (!Objects.equals(menu.thresholds.get(i), latestThreshold)) {
                                            
                                                                                            menu.thresholds.put(i, latestThreshold);
                                            
    
                                            
                                                                                        }        
                                        }
        
                                    }
        
                                }
        
        // 每帧都更新配置槽位的位置（确保槽位位置正确）
        updateScrollPositions();

        // 更新工具栏按钮状态
        this.redstoneMode.active = true;
        this.redstoneMode.set(menu.getRedStoneMode());

        // 模糊模式按钮：只有在支持模糊搜索时才显示
        boolean supportsFuzzy = menu.supportsFuzzySearch();
        this.fuzzyMode.visible = supportsFuzzy;
        this.fuzzyMode.active = supportsFuzzy;
        if (supportsFuzzy) {
            this.fuzzyMode.set(menu.getFuzzyMode());
        }

        // 合成模式按钮：只有在安装了合成卡时才显示
        boolean hasCraftingCard = menu.hasUpgrade(AEItems.CRAFTING_CARD);
        this.craftingMode.visible = hasCraftingCard;
        this.craftingMode.active = hasCraftingCard;
        if (hasCraftingCard) {
            this.craftingMode.set(menu.getCraftingMode());
        }
    }
    
    /**
     * 更新动态控件
     */
    private void updateDynamicControls() {
        // 计算实际的配置物品数量
        int itemCount = calculateConfiguredItemCount();
        int totalRows = itemCount + 1; // 显示 n+1 行
        
        // 如果行数没有变化，直接返回（值已在updateBeforeRender中更新）
        if (rows.size() == totalRows) {
            return;
        }
        
        // 清空所有旧的控件
        this.children().removeIf(child -> thresholdInputs.contains(child) || logicButtons.contains(child) || comparisonButtons.contains(child));
        this.renderables.removeIf(child -> thresholdInputs.contains(child) || logicButtons.contains(child) || comparisonButtons.contains(child));
        
        // 清除旧控件列表
        thresholdInputs.clear();
        logicButtons.clear();
        comparisonButtons.clear();
        rows.clear();
        
        // 创建每行的控件
        for (int i = 0; i < totalRows; i++) {
            final int rowIndex = i;
            
            // 比较模式按钮（放在输入框左方）
            MultiLevelEmitterPart.ComparisonMode comparisonMode = menu.getComparisonMode(rowIndex);
            Button comparisonButton = Button.builder(
                Component.literal(comparisonMode == MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL ? "≥" : "<"),
                btn -> {
                    MultiLevelEmitterPart.ComparisonMode current = menu.getComparisonMode(rowIndex);
                    MultiLevelEmitterPart.ComparisonMode next = current == MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL 
                        ? MultiLevelEmitterPart.ComparisonMode.LESS_THAN 
                        : MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL;
                    
                    // 使用客户端操作同步比较模式
                    menu.setComparisonMode(rowIndex, next);
                    
                    // 立即更新按钮显示
                    btn.setMessage(next == MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL 
                        ? Component.literal("≥").withStyle(ChatFormatting.YELLOW)
                        : Component.literal("<").withStyle(ChatFormatting.RED));
                }
            ).bounds(
                this.leftPos + COMPARISON_X,
                this.topPos + CONTENT_TOP + i * ROW_HEIGHT,
                COMPARISON_WIDTH,
                12
            ).build();
            comparisonButton.setMessage(
                comparisonMode == MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL 
                    ? Component.literal("≥").withStyle(ChatFormatting.YELLOW)
                    : Component.literal("<").withStyle(ChatFormatting.RED)
            );
            comparisonButtons.add(comparisonButton);
            this.addRenderableWidget(comparisonButton);
            
            // 阈值输入框（坐标是相对于窗口的绝对位置）
            EditBox input = new EditBox(
                this.font,
                this.leftPos + INPUT_X,
                this.topPos + CONTENT_TOP + i * ROW_HEIGHT,
                INPUT_WIDTH,
                12,
                Component.literal("Threshold")
            );
            // 从 Part 中读取最新的阈值（而不是从 Menu 的 thresholds 数组）
            long thresholdValue = menu.getHost().getReportingValueForSlot(i);
            input.setValue(String.valueOf(thresholdValue));

            input.setResponder(value -> {
                // 检查输入是否为有效数字
                if (value.isEmpty()) {
                    // 空字符串允许，设置为0
                    menu.setReportingValue(0, rowIndex);
                    // 确保Part被标记为需要保存
                    menu.getHost().getHost().markForSave();
                    return;
                }
                try {
                    long threshold = Long.parseLong(value);
                    // 只有当值真正改变时才更新
                    if (threshold != menu.getThreshold(rowIndex)) {
                        menu.setReportingValue(threshold, rowIndex);
                        // 确保Part被标记为需要保存
                        menu.getHost().getHost().markForSave();
                        
                        // 添加额外的同步，防止快速操作时数据不同步
                        menu.broadcastChanges();
                    }
                } catch (NumberFormatException e) {
                    // 无效输入，不修改
                }
            });
            // 设置光标位置，确保文本正确显示
            input.setHighlightPos(input.getValue().length());
            thresholdInputs.add(input);
            this.addRenderableWidget(input);
            
            // 逻辑关系按钮（放在上下两个输入框之间，最后一行不显示）
            if (i < totalRows - 1) {
                final int buttonIndex = i;
                Button logicButton = Button.builder(Component.literal("AND"), btn -> {
                    List<MultiLevelEmitterPart.LogicRelation> relations = menu.getLogicRelations();
                    MultiLevelEmitterPart.LogicRelation current = relations.size() > buttonIndex
                        ? relations.get(buttonIndex)
                        : MultiLevelEmitterPart.LogicRelation.AND;
                    MultiLevelEmitterPart.LogicRelation next = current == MultiLevelEmitterPart.LogicRelation.AND
                        ? MultiLevelEmitterPart.LogicRelation.OR
                        : MultiLevelEmitterPart.LogicRelation.AND;

                    // 使用客户端操作同步逻辑关系
                    menu.setLogicRelation(buttonIndex, next);
                    
                    // 立即更新按钮显示
                    btn.setMessage(next == MultiLevelEmitterPart.LogicRelation.AND 
                        ? Component.literal("A").withStyle(ChatFormatting.YELLOW)
                        : Component.literal("O").withStyle(ChatFormatting.GREEN));
                }).bounds(
                    this.leftPos + LOGIC_X,
                    this.topPos + CONTENT_TOP + (i + 1) * ROW_HEIGHT - 6,  // 放在上下两个输入框之间
                    LOGIC_WIDTH,
                    12
                ).build();
                logicButtons.add(logicButton);
                this.addRenderableWidget(logicButton);
                
                // 更新按钮状态
                List<MultiLevelEmitterPart.LogicRelation> relations = menu.getLogicRelations();
                MultiLevelEmitterPart.LogicRelation relation = buttonIndex < relations.size() 
                    ? relations.get(buttonIndex) 
                    : MultiLevelEmitterPart.LogicRelation.AND;
                logicButton.setMessage(
                    relation == MultiLevelEmitterPart.LogicRelation.AND 
                        ? Component.literal("A").withStyle(ChatFormatting.YELLOW)
                        : Component.literal("O").withStyle(ChatFormatting.GREEN)
                );
            }
            
            rows.add(new ScrollableRow(i, input, comparisonButton, i < logicButtons.size() ? logicButtons.get(i) : null));
        }
        
        // 更新滚动位置（包括配置槽位）
        updateScrollPositions();
    }

    /**
     * 更新滚动位置
     */
    private void updateScrollPositions() {
        int totalRows = rows.size();
        if (totalRows <= MAX_VISIBLE_ROWS) {
            scrollOffset = 0;
        } else {
            scrollOffset = Math.min(scrollOffset, totalRows - MAX_VISIBLE_ROWS);
        }
        
        // 更新控件的可见性和位置
        for (int i = 0; i < rows.size(); i++) {
            ScrollableRow row = rows.get(i);
            int visibleIndex = i - scrollOffset;
            
            if (visibleIndex >= 0 && visibleIndex < MAX_VISIBLE_ROWS) {
                // 可见（控件坐标是相对于窗口的绝对位置）
                row.input.visible = true;
                row.input.active = true;
                row.input.setY(this.topPos + CONTENT_TOP + visibleIndex * ROW_HEIGHT);
                
                if (row.logicButton != null) {
                    row.logicButton.visible = true;
                    row.logicButton.active = true;
                    row.logicButton.setY(this.topPos + CONTENT_TOP + (visibleIndex + 1) * ROW_HEIGHT - 6);
                }
            } else {
                // 不可见
                row.input.visible = false;
                row.input.active = false;
                
                if (row.logicButton != null) {
                    row.logicButton.visible = false;
                    row.logicButton.active = false;
                }
            }
        }
        
        // 更新配置槽位的位置（与输入框同步滚动）
        var configSlots = menu.getSlots(SlotSemantics.CONFIG);
        
        int itemCount = calculateConfiguredItemCount();
        int configTotalRows = itemCount + 1; // 显示 n+1 行
        
        for (int i = 0; i < configSlots.size(); i++) {
            int visibleIndex = i - scrollOffset;
            // 只显示已配置的槽位和下一个空槽位（用于添加新物品）
            if (i < configTotalRows && visibleIndex >= 0 && visibleIndex < MAX_VISIBLE_ROWS) {
                // 显示配置槽位（槽位坐标是相对于 GUI 内部的，不需要加 leftPos 和 topPos）
                configSlots.get(i).x = SLOT_X;
                configSlots.get(i).y = CONTENT_TOP + visibleIndex * ROW_HEIGHT;
            } else {
                // 隐藏配置槽位
                configSlots.get(i).x = -9999;
                configSlots.get(i).y = -9999;
            }
        }
    }
    
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.updateBeforeRender();
        this.widgets.updateBeforeRender();

        super.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 绘制滚动条
        renderScrollbar(guiGraphics);
    }
    
    /**
     * 绘制滚动条
     */
    private void renderScrollbar(GuiGraphics guiGraphics) {
        int totalRows = rows.size();
        if (totalRows <= MAX_VISIBLE_ROWS) {
            return; // 不需要滚动条
        }
        
        int scrollbarX = this.leftPos + SCROLLBAR_X;
        int scrollbarY = this.topPos + SCROLLBAR_TOP;
        
        // 绘制滚动条背景
        guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, 
             scrollbarY + SCROLLBAR_HEIGHT, 0xFF000000);
        
        // 计算滚动条滑块大小和位置
        float scrollPercent = (float) scrollOffset / (totalRows - MAX_VISIBLE_ROWS);
        int thumbHeight = (SCROLLBAR_HEIGHT * MAX_VISIBLE_ROWS) / totalRows;
        int thumbY = scrollbarY + (int) ((SCROLLBAR_HEIGHT - thumbHeight) * scrollPercent);
        
        // 绘制滚动条滑块
        guiGraphics.fill(scrollbarX, thumbY, scrollbarX + SCROLLBAR_WIDTH, 
             thumbY + thumbHeight, 0xFF808080);
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int totalRows = rows.size();
        // 只在内容区域滚动
        if (totalRows > MAX_VISIBLE_ROWS && mouseX >= this.leftPos && mouseX <= this.leftPos + this.imageWidth
            && mouseY >= this.topPos + CONTENT_TOP && mouseY <= this.topPos + CONTENT_TOP + CONTENT_HEIGHT) {
            
            scrollOffset -= (int) delta;
            scrollOffset = Math.max(0, Math.min(scrollOffset, totalRows - MAX_VISIBLE_ROWS));
            updateScrollPositions();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDraggingScroll && button == 0) {
            int totalRows = rows.size();
            if (totalRows > MAX_VISIBLE_ROWS) {
                int scrollbarY = this.topPos + SCROLLBAR_TOP;
                float relativeY = (float) (mouseY - scrollbarY) / SCROLLBAR_HEIGHT;
                scrollOffset = (int) (relativeY * (totalRows - MAX_VISIBLE_ROWS));
                scrollOffset = Math.max(0, Math.min(scrollOffset, totalRows - MAX_VISIBLE_ROWS));
                updateScrollPositions();
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 检查是否点击滚动条
        int scrollbarX = this.leftPos + SCROLLBAR_X;
        int scrollbarY = this.topPos + SCROLLBAR_TOP;
        int totalRows = rows.size();
        
        if (totalRows > MAX_VISIBLE_ROWS && mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH
            && mouseY >= scrollbarY && mouseY <= scrollbarY + SCROLLBAR_HEIGHT) {
            
            isDraggingScroll = true;
            float relativeY = (float) (mouseY - scrollbarY) / SCROLLBAR_HEIGHT;
            scrollOffset = (int) (relativeY * (totalRows - MAX_VISIBLE_ROWS));
            scrollOffset = Math.max(0, Math.min(scrollOffset, totalRows - MAX_VISIBLE_ROWS));
            updateScrollPositions();
            return true;
        }
        
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        isDraggingScroll = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
         * 可滚动行数据类
         */
        private record ScrollableRow(int index, EditBox input, Button comparisonButton, Button logicButton) {
    }
}
