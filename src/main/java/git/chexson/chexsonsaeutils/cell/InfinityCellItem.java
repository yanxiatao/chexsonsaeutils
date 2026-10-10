package git.chexson.chexsonsaeutils.cell;

import appeng.api.config.FuzzyMode;
import appeng.api.ids.AEComponents;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.List;

public class InfinityCellItem extends Item implements ICellWorkbenchItem {

    public static final String CELL_UUID = "aeus_cell_uuid";
    public static final String CELL_CACHED_TYPES = "aeus_cached_types";
    public static final String CELL_CACHED_TOTAL = "aeus_cached_total";

    public InfinityCellItem() {
        super(new Properties().stacksTo(1).fireResistant());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.chexsonsaeutils.infinity_cell.line1"));
        tooltip.add(Component.translatable("tooltip.chexsonsaeutils.infinity_cell.line2"));

        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.isEmpty()) {
            return;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(CELL_UUID)) {
            return;
        }
        var uuid = tag.getUUID(CELL_UUID);
        tooltip.add(Component.literal("UUID: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(uuid.toString())
                        .withStyle(ChatFormatting.YELLOW)));
        if (tag.contains(CELL_CACHED_TYPES)) {
            int types = tag.getInt(CELL_CACHED_TYPES);
            tooltip.add(Component.literal("Types: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(types))
                            .withStyle(ChatFormatting.GREEN)));
        }
        if (tag.contains(CELL_CACHED_TOTAL)) {
            byte[] bytes = tag.getByteArray(CELL_CACHED_TOTAL);
            BigInteger total = new BigInteger(bytes);
            tooltip.add(Component.literal("Total: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(formatBigInteger(total))
                            .withStyle(ChatFormatting.AQUA)));
        }
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack itemStack) {
        return UpgradeInventories.forItem(itemStack, 4);
    }

    @Override
    public ConfigInventory getConfigInventory(ItemStack itemStack) {
        return CellConfig.create(itemStack);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack itemStack) {
        return itemStack.getOrDefault(AEComponents.STORAGE_CELL_FUZZY_MODE, FuzzyMode.IGNORE_ALL);
    }

    @Override
    public void setFuzzyMode(ItemStack itemStack, FuzzyMode fuzzyMode) {
        itemStack.set(AEComponents.STORAGE_CELL_FUZZY_MODE, fuzzyMode);
    }

    public static String formatBigInteger(BigInteger value) {
        DecimalFormat df = new DecimalFormat("#.##");
        BigDecimal bd = new BigDecimal(value);
        BigDecimal thousand = new BigDecimal(1000);
        String[] units = {"", "K", "M", "G", "T", "P", "E", "Z", "Y"};
        int idx = 0;
        while (bd.compareTo(thousand) >= 0 && idx < units.length - 1) {
            bd = bd.divide(thousand, 2, RoundingMode.HALF_UP);
            idx++;
        }
        if (idx == 0) {
            return bd.setScale(0, RoundingMode.DOWN).toPlainString();
        }
        return df.format(bd.doubleValue()) + units[idx];
    }
}
