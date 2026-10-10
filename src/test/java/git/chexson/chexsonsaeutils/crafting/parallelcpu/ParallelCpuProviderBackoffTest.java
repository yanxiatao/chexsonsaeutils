package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ParallelCpuProviderBackoffTest {

    @Test
    void rejectedPatternDoesNotBackOffAnotherPatternOnSameReadyProvider() {
        var rejected = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
        var other = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.GOLD_INGOT), 1L, java.util.Map.of());
        var provider = new AlwaysReadyProvider(List.of(rejected, other));
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        backoff.recordPushRejected(provider, rejected, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, rejected, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L));
        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.READY,
                backoff.checkProvider(provider, other, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L));
    }

    @Test
    void busyProviderStillBacksOffEveryPattern() {
        var first = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
        var second = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.GOLD_INGOT), 1L, java.util.Map.of());
        var provider = new BusyProvider(List.of(first, second));
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BUSY,
                backoff.checkProvider(provider, first, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L));
        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, second, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L));
    }

    /**
     * F1-②：申报腿自己被拒 ⇒ <b>下一窗申报腿确实退避</b>。
     *
     * <p>防的是把"按腿分桶"做成"申报腿永不退避"：分掉的是 holder 腿那一桶，不是申报腿这本账。
     */
    @Test
    void declaredLegRejectionStillBacksOffTheDeclaredLegNextWindow() {
        var pattern = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
        var provider = new AlwaysReadyProvider(List.of(pattern));
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        backoff.recordPushRejected(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED, 10L);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED, 11L),
                "申报腿拒收后下一窗照旧退避：分桶不许把这一腿的账一并抹掉");
    }

    /**
     * F1-③：holder 腿自己推被拒 ⇒ 退避<b>只作用于 holder 腿</b>，申报腿不受影响。
     */
    @Test
    void holderLegRejectionDoesNotBackOffTheDeclaredLeg() {
        var pattern = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
        var provider = new AlwaysReadyProvider(List.of(pattern));
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        backoff.recordPushRejected(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L),
                "holder 腿那一桶照旧得退避（旧语义不许漂）");
        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.READY,
                backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED, 11L),
                "两条腿分开记：holder 腿被拒不许堵申报批那条路");
    }

    /**
     * F1-④：{@code recordPushAccepted} 的清账<b>只清同一条腿</b>那一桶。
     */
    @Test
    void acceptedPushClearsOnlyTheSameLeg() {
        var pattern = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
        var provider = new AlwaysReadyProvider(List.of(pattern));
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        backoff.recordPushRejected(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 10L);
        backoff.recordPushAccepted(provider, pattern, ParallelCpuProviderBackoff.PushLeg.DECLARED);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L),
                "申报腿接受一分没解 holder 腿的账：反向也一样");

        backoff.recordPushAccepted(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.READY,
                backoff.checkProvider(provider, pattern, ParallelCpuProviderBackoff.PushLeg.HOLDER, 11L),
                "同一条腿的接受即清账（与改前同语义）");
    }

    private record AlwaysReadyProvider(List<IPatternDetails> patterns) implements ICraftingProvider {
        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return patterns;
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return true;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    private record BusyProvider(List<IPatternDetails> patterns) implements ICraftingProvider {
        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return patterns;
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return false;
        }

        @Override
        public boolean isBusy() {
            return true;
        }
    }
}
