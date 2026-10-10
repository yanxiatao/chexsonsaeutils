package git.chexson.chexsonsaeutils.crafting.solve;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Pure ordered-attempt control flow shared by the calculation shell and tests. */
public final class OrderedPlanningSelector {

    private OrderedPlanningSelector() {
    }

    @FunctionalInterface
    public interface Eligibility {
        boolean isEligible(PlanningAlgorithmId algorithm);
    }

    @FunctionalInterface
    public interface Attempt<T> {
        @Nullable
        T run(PlanningAlgorithmId algorithm);
    }

    public record Selection<T>(PlanningAlgorithmId algorithm, T value) {
        public Selection {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Tries eligible entries in snapshot order. Null and non-cancellation
     * failures decline; terminal entries stop by returning or throwing.
     */
    @Nullable
    public static <T> Selection<T> select(
            List<PlanningAlgorithmId> order,
            Eligibility eligibility,
            Attempt<T> attempt
    ) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(attempt, "attempt");
        for (PlanningAlgorithmId algorithm : List.copyOf(order)) {
            if (!eligibility.isEligible(algorithm)) {
                continue;
            }
            try {
                T value = attempt.run(algorithm);
                if (value != null) {
                    return new Selection<>(algorithm, value);
                }
                if (algorithm.isTerminal()) {
                    throw new IllegalStateException("Terminal planning algorithm declined: " + algorithm);
                }
            } catch (RuntimeException failure) {
                if (isCancellation(failure) || algorithm.isTerminal()) {
                    throw failure;
                }
            }
        }
        return null;
    }

    public static boolean isCancellation(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof InterruptedException || cursor instanceof CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }
}
