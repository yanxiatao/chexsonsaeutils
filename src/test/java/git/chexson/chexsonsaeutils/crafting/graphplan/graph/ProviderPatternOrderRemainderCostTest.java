package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账 #143 T4 —— 同一张样板在<b>一次编译</b>里退料段只走一趟网格。
 *
 * <p>稳定键的退料段按候选逐个求 {@code getRemainingKey}（#143 MF-1），而 AE2 的实现每调一次就
 * 重排一遍网格并跑 {@code recipe.getRemainingItems}（{@code AECraftingPattern.java:281-298}，
 * 它自己注明"Consider making this more efficient in the future? (e.g. cache the produced
 * remainders)"）。{@code stabilized} 里那只 {@code keys.computeIfAbsent} 只保证<b>一次排序</b>内
 * 每张样板算一次；而一次编译里同一张样板会出现在<b>每一个</b>它参与的键的那份清单上
 * （{@code GraphCompiler.craftingFor} 的记忆化是按键存的），于是这一张样板的整段退料被一遍遍重算。
 *
 * <p>判据：拿计数型 fake 样板量"一次编译内每张样板的 {@code getRemainingKey} 调用次数"。
 * 四张样板各三个候选、连排两次序 ⇒ 每张仍是 3 次（＝候选数一次），<b>不是</b>每比较一次、
 * 也不是每排一次序一次。未修的树上这条红：两趟排序就是 6 次。
 */
class ProviderPatternOrderRemainderCostTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);

    /** 一张样板一个槽三个候选：稳定键算一次正好读三次退料。 */
    private static final GenericStack[] CANDIDATES = {
            new GenericStack(AEItemKey.of(Items.WATER_BUCKET), 1L),
            new GenericStack(AEItemKey.of(Items.LAVA_BUCKET), 1L),
            new GenericStack(AEItemKey.of(Items.MILK_BUCKET), 1L),
    };

    /** 四张并列样板的钉值次序：definition 注册表 id 的字典序。 */
    private static final List<Item> DEFINITIONS_IN_ORDER = List.of(
            Items.COAL, Items.DIAMOND, Items.EMERALD, Items.FEATHER);

    @Test
    void remaindersAreReadOncePerPatternAcrossRepeatedStabilisation() {
        var patterns = DEFINITIONS_IN_ORDER.stream().map(CountingPattern::new).toList();
        var grid = FakeGrid.builder().pattern(TOP, patterns.toArray(new IPatternDetails[0])).build();
        assertTrue(ProviderPatternOrder.priorityReadable(grid.crafting),
                "夹具前提：读不到优先级时 stabilized 原样交回，一个稳定键都不算");

        var asDetails = patterns.stream().map(IPatternDetails.class::cast).toList();
        // 一次编译里这张样板会进它参与的每一个键的那份清单：这里以两次定序代表两趟读取点
        var reversed = ProviderPatternOrder.stabilized(grid.crafting, asDetails.reversed());
        var forward = ProviderPatternOrder.stabilized(grid.crafting, asDetails);

        assertEquals(forward, reversed, "次序由稳定键定死、与来序无关是 stabilized 自己的承诺");
        assertEquals(patterns, forward, "钉值：四张并列样板按 definition 注册表 id 升序");
        for (var pattern : patterns) {
            assertEquals(CANDIDATES.length, pattern.reads.get(),
                    () -> "一次编译内这张样板的退料段只许走一趟候选表（候选数＝" + CANDIDATES.length
                            + "）；每排序一次、每比较一次都算重走网格：" + pattern.definition);
        }
    }

    /** 数着 {@code getRemainingKey} 调用次数的样板替身。 */
    private static final class CountingPattern implements IPatternDetails {
        private final AEItemKey definition;
        private final AtomicInteger reads = new AtomicInteger();

        private CountingPattern(Item item) {
            this.definition = AEItemKey.of(new ItemStack(item));
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] { new CountingSlot(reads) };
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(TOP, 1L));
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }

    /** 三个候选、无退料的槽：每读一个候选的退料键就记一笔。 */
    private record CountingSlot(AtomicInteger reads) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return CANDIDATES.clone();
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
            return true;
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            reads.incrementAndGet();
            return null;
        }
    }
}
