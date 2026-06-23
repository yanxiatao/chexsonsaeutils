package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.blockentity.crafting.CompiledTask;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FormalMachineAggregationRemainderHelper {

    private FormalMachineAggregationRemainderHelper() {
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

        KeyCounter[] inputHolders = createSingleRunInputHolders(supportedPattern);
        if (inputHolders == null) {
            return null;
        }

        CompiledTask compiledTask = CompiledTask.compile(supportedPattern, inputHolders, 1, 1);
        if (compiledTask == null) {
            return null;
        }

        ItemStack[] craftingGrid = compiledTask.getCraftingGridCopies();
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            if (craftingGrid[slot] == null) {
                craftingGrid[slot] = ItemStack.EMPTY;
            }
        }

        AbstractContainerMenu dummyMenu = new AbstractContainerMenu(null, 0) {
            @Override
            public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };
        TransientCraftingContainer craftingContainer = new TransientCraftingContainer(dummyMenu, 3, 3);
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            craftingContainer.setItem(slot, craftingGrid[slot]);
        }

        Map<AEItemKey, Long> remainders = new LinkedHashMap<>();
        for (ItemStack remainder : supportedPattern.getRemainingItems(craftingContainer)) {
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
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
