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

        backoff.recordPushRejected(provider, rejected, 10L);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, rejected, 11L, null));
        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.READY,
                backoff.checkProvider(provider, other, 11L, null));
    }

    @Test
    void busyProviderStillBacksOffEveryPattern() {
        var first = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.IRON_INGOT), 1L, java.util.Map.of());
        var second = FakePlanGrid.FakePattern.crafting(AEItemKey.of(Items.GOLD_INGOT), 1L, java.util.Map.of());
        var provider = new BusyProvider(List.of(first, second));
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BUSY,
                backoff.checkProvider(provider, first, 10L, null));
        assertEquals(
                ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(provider, second, 11L, null));
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
