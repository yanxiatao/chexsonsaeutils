package git.chexson.chexsonsaeutils.blockentity.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

class LocalPatternProviderFacadeTest {

    @Test
    void largePatternRosterDoesNotReinterpretRegisteredDefinitionsOnEveryAdmission() {
        var facade = new LocalPatternProviderFacade(null, null, null, null);
        var patterns = new java.util.ArrayList<appeng.api.crafting.IPatternDetails>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        for (int index = 0; index < 1468; index++) {
            var definition = definition(index, 0x123456);
            patterns.add(countingPattern(definition, reads));
        }
        facade.replaceAvailablePatterns(patterns);
        reads.set(0);
        for (int round = 0; round < 4; round++) {
            for (var pattern : patterns) assertTrue(facade.contains(pattern));
        }
        assertEquals(0, reads.get(), "已登记对象的准入不应复制或规范化任何定义");
        var equivalent = countingPattern(definition(1467, 0x654321), reads);
        assertTrue(facade.contains(equivalent), "去色后完整定义相同的壳仍可用");
        assertEquals(1, reads.get(), "等价壳只读取自身定义，不遍历登记表");
        assertFalse(facade.contains(countingPattern(definition(2000, 0), reads)));
        facade.replaceAvailablePatterns(List.of(patterns.getFirst()));
        assertFalse(facade.contains(equivalent), "删样板后身份索引不能留旧条目");
        assertFalse(facade.contains(patterns.getLast()));
        facade.clear();
        assertFalse(facade.contains(patterns.getFirst()));
    }

    private static AEItemKey definition(int tier, int color) {
        var stack = new net.minecraft.world.item.ItemStack(Items.PAPER);
        var data = new net.minecraft.nbt.CompoundTag();
        data.putInt("compressionTier", tier);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(data));
        stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                new net.minecraft.world.item.component.DyedItemColor(color, false));
        return AEItemKey.of(stack);
    }

    private static appeng.api.crafting.IPatternDetails countingPattern(AEItemKey definition,
            java.util.concurrent.atomic.AtomicInteger reads) {
        return new appeng.api.crafting.IPatternDetails() {
            @Override public AEItemKey getDefinition() { reads.incrementAndGet(); return definition; }
            @Override public IInput[] getInputs() { return new IInput[0]; }
            @Override public List<GenericStack> getOutputs() {
                return List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1));
            }
        };
    }

    @Test
    void colorDoesNotChangeUniqueProducerSetOrOrder() {
        var output = AEItemKey.of(Items.IRON_INGOT);
        var uncolored = FakePattern.colored(
                -1,
                AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(output, 1)));
        var red = FakePattern.colored(
                0xFFFF0000,
                FakePlanGrid.dyedKey(Items.PAPER, 0xFFFF0000),
                List.of(new GenericStack(output, 1)));
        var blue = FakePattern.colored(
                0xFF0000FF,
                FakePlanGrid.dyedKey(Items.PAPER, 0xFF0000FF),
                List.of(new GenericStack(output, 1)));
        var distinct = FakePattern.colored(
                -1,
                AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(output, 1)));

        var unique = LocalPatternProviderFacade.uniquePatternsInOrder(
                List.of(red, uncolored, distinct, blue));

        assertEquals(2, unique.size());
        assertSame(red, unique.get(0));
        assertSame(distinct, unique.get(1));
    }
}
