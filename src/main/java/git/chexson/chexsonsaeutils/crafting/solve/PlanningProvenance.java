package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.crafting.ICraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Records which solving level produced a crafting plan and how long the
 * calculation took, so the server thread building the client-bound
 * {@code CraftingPlanSummary} can surface it in the confirm dialog.
 *
 * <p>Calculations run on the AE crafting pool while the summary is built on
 * the server thread, so the provenance travels with the plan object through a
 * weak side table instead of a thread-local.
 *
 * <p>Keys are compared by identity: AE2's {@code CraftingPlan} is a record
 * whose components include mutable {@code KeyCounter} maps, so a value-hashed
 * table silently loses entries when those maps change after tagging. Entries
 * are reclaimed with the plan and stale references are expunged on access.
 */
public final class PlanningProvenance {

    public static final String GRAPH = "graph";
    public static final String NATIVE = "native";

    public record Info(long millis, String solver, long requestStartNanos, long calculationCompletedNanos) {
        public Info(long millis, String solver) {
            this(millis, solver, 0L, 0L);
        }

        public long elapsedMillisAt(long completedNanos) {
            if (this.requestStartNanos <= 0L) {
                return this.millis;
            }
            return Math.max(0L, completedNanos - this.requestStartNanos) / 1_000_000L;
        }
    }

    private static final Map<PlanRef, Info> TABLE = new HashMap<>();

    private PlanningProvenance() {
    }

    public static synchronized void tag(@Nullable ICraftingPlan plan, String solver, long startNanos) {
        tag(plan, solver, startNanos, null);
    }

    /**
     * 记录归属并（有 requester 时）直接把 provenance 写回该玩家的活跃确认
     * 菜单——绕开 summary 与 plan packet 两条会被其他规划 mod 接管的链路。
     */
    public static synchronized void tag(@Nullable ICraftingPlan plan, String solver, long startNanos,
            @Nullable appeng.api.networking.crafting.ICraftingSimulationRequester requester) {
        if (plan == null) {
            return;
        }
        long completedNanos = System.nanoTime();
        Info info = new Info((completedNanos - startNanos) / 1_000_000L, solver,
                startNanos, completedNanos);
        expungeStale();
        put(plan, info);
        if (requester != null) {
            requester.getActionSource().player().ifPresent(p -> ConfirmMenuProvenanceRegistry.publish(
                    p.getUUID(), info.millis(), info.solver()));
        }
    }

    /**
     * Transfers provenance to a replacement plan built from {@code source}
     * (formal-machine aggregation rewrites the plan after the solver tagged
     * it), so the summary still reports the level that actually solved it.
     */

    @Nullable
    public static synchronized Info peek(@Nullable ICraftingPlan plan) {
        return plan == null ? null : find(plan);
    }

    @Nullable
    private static Info find(ICraftingPlan plan) {
        Info info = TABLE.get(new PlanRef(plan));
        if (info != null) {
            return info;
        }
        // The confirm menu unwraps recursive wrappers before reading the plan.
        if (plan instanceof RecursivePlanContract recursive) {
            ICraftingPlan delegate = recursive.chexsonsaeutils$getDelegate();
            if (delegate != null && delegate != plan) {
                return TABLE.get(new PlanRef(delegate));
            }
        }
        return null;
    }

    private static void put(ICraftingPlan plan, Info info) {
        TABLE.put(new PlanRef(plan), info);
        // The confirm menu unwraps recursive wrappers before reading the plan,
        // so the delegate must carry the same provenance as the outer shell.
        if (plan instanceof RecursivePlanContract recursive) {
            ICraftingPlan delegate = recursive.chexsonsaeutils$getDelegate();
            if (delegate != null && delegate != plan) {
                TABLE.put(new PlanRef(delegate), info);
            }
        }
    }

    private static void expungeStale() {
        TABLE.keySet().removeIf(ref -> ref.get() == null);
    }

    public static String solverTranslationKey(String solver) {
        return switch (solver) {
            case GRAPH -> "gui.chexsonsaeutils.solver.graph";
            default -> "gui.chexsonsaeutils.solver.native";
        };
    }

    /** Weak identity key: the plan stays collectable while lookups ignore its mutable contents. */
    private static final class PlanRef extends WeakReference<ICraftingPlan> {

        private final int hash;

        PlanRef(ICraftingPlan plan) {
            super(plan);
            this.hash = System.identityHashCode(plan);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PlanRef ref)) {
                return false;
            }
            ICraftingPlan mine = get();
            return mine != null && mine == ref.get();
        }
    }
}
