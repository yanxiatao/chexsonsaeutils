package git.chexson.chexsonsaeutils.crafting.directprocessing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingExecutionBudgetTest {
    @Test
    void wallBudgetStopsExactlyAtDeadlineAndResetsForNextTick() {
        var budget = new ProcessingExecutionBudget(new ProcessingExecutionBudget.Limits(1, 1, 100, 1));
        budget.resetForTick(1000);
        assertTrue(budget.hasTimeBudget(1099));
        assertFalse(budget.hasTimeBudget(1100));
        assertFalse(budget.hasTimeBudget(1101));
        budget.resetForTick(2000);
        assertTrue(budget.hasTimeBudget(2000));
        assertFalse(budget.hasTimeBudget(2100));
    }
}
