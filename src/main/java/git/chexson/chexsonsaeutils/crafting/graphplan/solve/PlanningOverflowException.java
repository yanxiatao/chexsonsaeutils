package git.chexson.chexsonsaeutils.crafting.graphplan.solve;


/**
 * Thrown when a planning number would overflow {@code long} or exceed the
 * planner's amount cap. Stackless by design; the planner converts it into a
 * decline so the caller falls back to the tree path.
 */
public final class PlanningOverflowException extends RuntimeException {

    public PlanningOverflowException(String message) {
        super(message, null, false, false);
    }
}
