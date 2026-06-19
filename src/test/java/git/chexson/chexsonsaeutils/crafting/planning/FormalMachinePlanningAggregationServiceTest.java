package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FormalMachinePlanningAggregationServiceTest {

    @Test
    void aggregationFutureDoesNotCacheDelegatePlan() throws Exception {
        MutablePlanFuture delegate = new MutablePlanFuture();
        ICraftingPlan firstPlan = plan(Items.DIAMOND);
        ICraftingPlan secondPlan = plan(Items.EMERALD);
        Future<ICraftingPlan> wrapped = FormalMachinePlanningAggregationService.wrapNativeFuture(
                null,
                null,
                AEItemKey.of(Items.DIAMOND),
                1L,
                delegate
        );

        delegate.setPlan(firstPlan);
        assertTrue(wrapped.isDone());
        assertSame(firstPlan, wrapped.get());

        delegate.setPlan(secondPlan);
        assertSame(secondPlan, wrapped.get());
    }

    private static ICraftingPlan plan(Item item) {
        return new CraftingPlan(
                new GenericStack(AEItemKey.of(item), 1L),
                1L,
                false,
                false,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                Map.of()
        );
    }

    private static final class MutablePlanFuture implements Future<ICraftingPlan> {
        private ICraftingPlan plan;
        private boolean cancelled;

        private void setPlan(ICraftingPlan plan) {
            this.plan = plan;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public ICraftingPlan get() {
            return plan;
        }

        @Override
        public ICraftingPlan get(long timeout, TimeUnit unit) throws TimeoutException {
            if (plan == null) {
                throw new TimeoutException("No plan prepared");
            }
            return plan;
        }
    }
}
