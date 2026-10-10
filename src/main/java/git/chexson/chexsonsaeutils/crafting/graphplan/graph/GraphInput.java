package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

/**
 * Closed-form encoding of a pattern input slot on the compiled graph.
 *
 * <p>{@code key} is the tree node key after the tree planner's
 * {@code findCraftedStack} replacement (bytes, pattern lookup, emit and
 * missing accounting all use it); {@code amountPerCraft} is the first
 * possible input's amount (the node template amount) and {@code multiplier}
 * the slot multiplier: firing the pattern {@code times} times requests
 * {@code multiplier * times} input units, i.e.
 * {@code amountPerCraft * multiplier * times} items.
 *
 * <p>{@code mode} classifies circular (limitQty) inputs for closed-form batch
 * accounting that mirrors {@code FastLimitQtyBatcher}:
 * <ul>
 * <li>{@link Mode#NORMAL} — plain consumption.</li>
 * <li>{@link Mode#RETURNED} — container returned 1:1
 * ({@code remainderKey == key}); the first firing's extract-then-reinsert
 * cycle nets to zero, remaining firings are bytes-only compensation.</li>
 * <li>{@link Mode#FINITE_USE} — durability-style remainder
 * ({@code remainderKey != key}); the input is consumed in one batched request
 * and the remainder key is inserted once per firing.</li>
 * <li>{@link Mode#CONSUMED_RETURNING} — self-feeding catalyst (no remainder);
 * the first firing's extract is the only one, the output correction covers the
 * catalyst returned by the remaining (times-1) firings.</li>
 * </ul>
 *
 * <p>Circular modes require exact-key isolation (single candidate, single
 * class variant). {@code NORMAL} inputs support the tree planner's greedy
 * fuzzy extraction through {@code extractCandidates} and
 * {@code validVariants}: {@code null} means the exact-key fast path (the only
 * valid template is {@code key} itself); otherwise the solver iterates
 * candidates × live class variants, keeping only keys in {@code validVariants}
 * (every template key the pattern's {@code isValid} accepts, precomputed at
 * compile time against the network stock and every pattern output in the
 * graph).
 *
 * <p>{@code selfBlocked} mirrors the tree planner's second-level
 * {@code notRecursive} termination: the slot's node key landed on the
 * expansion path (the pattern's own registration key, or a substitute
 * resolving back onto an ancestor), so the tree node re-entering that key
 * filters every pattern — the slot behaves as a pattern-less leaf (stock and
 * emit only).
 */
public record GraphInput(AEKey key, long amountPerCraft, long multiplier, Mode mode,
        @Nullable AEKey remainderKey,
        @Nullable List<Candidate> extractCandidates,
        @Nullable Set<AEKey> validVariants,
        boolean selfBlocked) {

    public enum Mode {
        NORMAL, RETURNED, FINITE_USE, CONSUMED_RETURNING
    }

    /** One extraction template source: a possible input and its template amount. */
    public record Candidate(AEKey key, long amount) {
    }
}
