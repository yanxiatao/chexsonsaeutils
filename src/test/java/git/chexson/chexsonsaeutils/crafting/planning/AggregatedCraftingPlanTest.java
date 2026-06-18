package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AggregatedCraftingPlanTest {

    @Test
    void preservesDyeableRecursivePlanMarkerAndInitialItems() {
        AEKey output = AEItemKey.of(Items.PAPER);
        AEKey seed = AEItemKey.of(Items.MAP);
        KeyCounter usedItems = new KeyCounter();
        usedItems.add(seed, 1L);
        KeyCounter recursiveInitialItems = new KeyCounter();
        recursiveInitialItems.add(seed, 1L);

        AggregatedCraftingPlan plan = new AggregatedCraftingPlan(
                new GenericStack(output, 5L),
                1L,
                false,
                false,
                usedItems,
                new KeyCounter(),
                new KeyCounter(),
                Map.of(new TestPatternDetails(output), 5L),
                true,
                recursiveInitialItems,
                recursiveInitialItems,
                5L
        );

        assertTrue(plan.chexsonsaeutils$usesDyeableRecursivePlanning());
        assertEquals(5L, plan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount());
        KeyCounter initialItems = plan.chexsonsaeutils$dyeableRecursiveInitialItems();
        initialItems.add(seed, 10L);

        assertEquals(1L, plan.chexsonsaeutils$dyeableRecursiveInitialItems().get(seed));
        assertEquals(1L, plan.chexsonsaeutils$dyeableRecursiveInternalItems().get(seed));
    }

    private record TestPatternDetails(AEKey output) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(Items.PAPER);
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(output, 1L));
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
