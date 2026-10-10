package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HostBatchRemainders {

    private HostBatchRemainders() {
    }

    public static @Nullable Map<AEItemKey, Long> computeSingleRunRemainders(
            @Nullable Level level,
            @Nullable IPatternDetails patternDetails
    ) {
        if (level == null || patternDetails == null) {
            return null;
        }
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern)) {
            return Map.of();
        }
        // 快路径：全部输入槽都无容器回流键时余料必为空，跳过合成容器仿真。
        // 实机 500+ 样板里绝大多数无回流，每次仿真建容器+跑配方是重写 236ms 的主因。
        if (!hasAnyRemainderKey(supportedPattern)) {
            return Map.of();
        }

        KeyCounter[] inputHolders = createSingleRunInputHolders(supportedPattern);
        if (inputHolders == null) {
            return null;
        }

        ItemStack[] craftingGrid = new ItemStack[9];
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            craftingGrid[slot] = ItemStack.EMPTY;
        }
        supportedPattern.fillCraftingGrid(inputHolders, (slot, stack) -> craftingGrid[slot] = stack.copy());
        for (KeyCounter inputHolder : inputHolders) {
            inputHolder.removeZeros();
            if (!inputHolder.isEmpty()) {
                return null;
            }
        }

        net.minecraft.world.inventory.TransientCraftingContainer craftingContainer =
                new net.minecraft.world.inventory.TransientCraftingContainer(new appeng.menu.AutoCraftingMenu(), 3, 3);
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            craftingContainer.setItem(slot, craftingGrid[slot]);
        }

        var craftingInput = craftingContainer.asPositionedCraftInput().input();
        Map<AEItemKey, Long> remainders = new LinkedHashMap<>();
        for (ItemStack remainder : supportedPattern.getRemainingItems(craftingInput)) {
            if (remainder.isEmpty()) {
                continue;
            }
            GenericStack genericRemainder = GenericStack.fromItemStack(remainder.copy());
            if (genericRemainder == null || !(genericRemainder.what() instanceof AEItemKey itemKey)) {
                return null;
            }
            remainders.merge(itemKey, genericRemainder.amount(), Long::sum);
        }
        return Map.copyOf(remainders);
    }

    /** 声明输入没有任何回流容器时，仿真结果必为空，可省略构造合成容器。 */
    private static boolean hasAnyRemainderKey(IPatternDetails patternDetails) {
        for (var input : patternDetails.getInputs()) {
            if (input == null || input.getPossibleInputs() == null) {
                continue;
            }
            for (var possible : input.getPossibleInputs()) {
                if (possible == null || possible.what() == null) {
                    continue;
                }
                if (slotReturnsContainer(input, possible.what())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 判断候选是否具有容器回流，用于仿真快路径；执行侧的整批回流账保持宽域精确值。 */
    public static boolean slotReturnsContainer(@Nullable IPatternDetails.IInput slot,
            @Nullable AEKey candidate) {
        if (slot == null || candidate == null) {
            return false;
        }
        try {
            return slot.getRemainingKey(candidate) != null;
        } catch (RuntimeException ignored) {
            // 配方求值异常则按有回流处理：仿真那条路交给上游判定，申报那条路照"有回流"夹住。
            return true;
        }
    }

    private static @Nullable KeyCounter[] createSingleRunInputHolders(
            IMolecularAssemblerSupportedPattern supportedPattern
    ) {
        var inputs = supportedPattern.getInputs();
        KeyCounter[] inputHolders = new KeyCounter[inputs.length];
        for (int index = 0; index < inputs.length; index++) {
            KeyCounter holder = new KeyCounter();
            var possibleInputs = inputs[index].getPossibleInputs();
            if (possibleInputs.length != 1 || possibleInputs[0] == null) {
                return null;
            }
            GenericStack possibleInput = possibleInputs[0];
            AEKey key = possibleInput.what();
            long multiplier = inputs[index].getMultiplier();
            long amount = multiply(possibleInput.amount(), multiplier);
            if (!(key instanceof AEItemKey) || amount <= 0L) {
                return null;
            }
            holder.add(key, amount);
            inputHolders[index] = holder;
        }
        return inputHolders;
    }

    private static long multiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        return WideMath.wideMul(left, right);
    }
}
