package git.chexson.chexsonsaeutils.menu.directprocessing;

import appeng.api.inventories.InternalInventory;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSource;
import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixSearch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import java.util.Arrays;
import java.util.Locale;

/** 将机器标记库存接入样板矩阵交互，放入校验仍由宿主的整合包规则负责。 */
public final class MachineBindingMatrixSourceImpl implements PatternMatrixSource {
    private final AEDirectProcessingMachineBlockEntity host;
    public MachineBindingMatrixSourceImpl(AEDirectProcessingMachineBlockEntity host) { this.host = host; }
    @Override public InternalInventory patternMatrixInventory() { return host.getMachineBindingInventory(); }
    @Override public int patternMatrixTotalSlots() { return patternMatrixInventory().size(); }
    @Override public ItemStack patternMatrixDisplayStack(int slot) { return patternMatrixInventory().getStackInSlot(slot); }
    // 标记展示自身，不使用样板兼容状态或派生详情版本。
    @Override public int patternMatrixStatusCode(int slot) { return 0; }
    @Override public int patternMatrixReasonCode(int slot) { return 0; }
    @Override public long patternMatrixDetailVersion() { return 0; }
    @Override public boolean patternMatrixMayPlace(ItemStack stack) { return host.isSupportedBindingMachine(stack); }
    @Override public int patternMatrixSearchMatches(String query, long[] bits) {
        Arrays.fill(bits, 0);
        var tokens = PatternMatrixSearch.tokenize(query);
        if (tokens.isEmpty()) return 0;
        int count = 0;
        for (int slot = 0; slot < patternMatrixTotalSlots(); slot++) {
            var stack = patternMatrixDisplayStack(slot);
            if (stack.isEmpty()) continue;
            String text = (stack.getHoverName().getString() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .toLowerCase(Locale.ROOT);
            if (tokens.stream().allMatch(text::contains)) { bits[slot >>> 6] |= 1L << (slot & 63); count++; }
        }
        return count;
    }
}
