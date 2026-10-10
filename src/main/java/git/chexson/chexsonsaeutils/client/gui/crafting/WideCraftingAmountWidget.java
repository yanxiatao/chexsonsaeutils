package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.NumberEntryType;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.client.gui.widgets.ConfirmableTextField;
import appeng.client.gui.widgets.AE2Button;
import appeng.core.localization.GuiText;
import git.chexson.chexsonsaeutils.crafting.request.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

/** 保留 AE2 数量控件布局、单位和步长；替换有损的 long 换算与加减回调。 */
public final class WideCraftingAmountWidget extends NumberEntryWidget {
    private final CraftingAmountExpression expression = new CraftingAmountExpressionImpl();
    private final int normalColor;
    private final int errorColor;
    private final List<Button> originals = new ArrayList<>();
    private final List<Button> replacements = new ArrayList<>();
    private ConfirmableTextField field;
    private NumberEntryType exactType = NumberEntryType.UNITLESS;
    private BigInteger initial = BigInteger.ONE;
    private Runnable confirm;

    public WideCraftingAmountWidget(ScreenStyle style) {
        super(style, NumberEntryType.UNITLESS);
        normalColor = style.getColor(PaletteColor.TEXTFIELD_TEXT).toARGB();
        errorColor = style.getColor(PaletteColor.TEXTFIELD_ERROR).toARGB();
        setHideValidationIcon(true);
    }
    /** 返回已换算为最小单位的精确数量。 */
    public Optional<BigInteger> exactValue() {
        if (field == null) return Optional.of(initial);
        var text = field.getValue();
        return expression.parse(text.startsWith("=") ? text.substring(1) : text, exactType.amountPerUnit());
    }
    /** 回填最小单位数量，支持非终止十进制的自定义单位比例。 */
    public void setExactValue(BigInteger amount) {
        initial = amount;
        if (field != null) field.setValue(expression.format(amount, exactType.amountPerUnit()));
    }
    public void restore(BigInteger amount, boolean missing) {
        setExactValue(amount);
        if (field != null && missing) field.setValue("=" + field.getValue());
    }
    @Override public void setType(NumberEntryType type) { exactType = type; super.setType(type); }
    @Override public void setOnConfirm(Runnable callback) { confirm = callback; }
    @Override public void populateScreen(Consumer<AbstractWidget> addWidget, Rect2i bounds, AEBaseScreen<?> screen) {
        originals.clear(); replacements.clear();
        super.populateScreen(widget -> {
            if (widget instanceof ConfirmableTextField text) {
                field = text;
                addWidget.accept(widget);
            } else if (widget instanceof Button button) {
                int index = replacements.size();
                var replacement = new AE2Button(button.getX(), button.getY(), button.getWidth(), button.getHeight(),
                        button.getMessage(), ignored -> addExact(index));
                originals.add(button); replacements.add(replacement);
                addWidget.accept(replacement);
            } else addWidget.accept(widget);
        }, bounds, screen);
        field.setMaxLength(CraftingAmountExpression.MAX_INPUT_LENGTH);
        field.setResponder(ignored -> validateExact());
        field.setOnConfirm(() -> { if (exactValue().isPresent() && confirm != null) confirm.run(); });
        setExactValue(initial);
        validateExact();
    }
    @Override public void updateBeforeRender() {
        super.updateBeforeRender();
        for (int i = 0; i < replacements.size(); i++) replacements.get(i).setMessage(originals.get(i).getMessage());
        validateExact();
    }
    private void validateExact() {
        var value = exactValue();
        field.setTextColor(value.isPresent() ? normalColor : errorColor);
        field.setTooltipMessage(value.isPresent() ? List.of(
                Component.literal("= " + expression.format(value.get(), exactType.amountPerUnit())
                        + (exactType.unit() == null ? "" : " " + exactType.unit())),
                Component.translatable("gui.chexsonsaeutils.wide_request.exact_amount", value.get().toString()))
                : List.of(GuiText.InvalidNumber.text()));
    }
    private void addExact(int index) {
        int[] steps = Screen.hasShiftDown() || Screen.hasControlDown() ? new int[] {1, 8, 64, 512} : new int[] {1, 10, 100, 1000};
        long step = steps[index % 4];
        if (index >= 4) step = -step;
        var current = exactValue().orElse(BigInteger.ZERO);
        var unit = BigInteger.valueOf(exactType.amountPerUnit());
        var next = current.add(unit.multiply(BigInteger.valueOf(step)));
        if (current.equals(unit) && step > 1) next = next.subtract(unit);
        next = next.max(unit); // 原版按钮按显示单位取整，最小显示数量为一个单位。
        if (next.toString().length() <= CraftingAmountExpression.MAX_LENGTH) setExactValue(next);
    }
}
