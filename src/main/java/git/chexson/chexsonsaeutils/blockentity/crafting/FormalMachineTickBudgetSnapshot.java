package git.chexson.chexsonsaeutils.blockentity.crafting;

public record FormalMachineTickBudgetSnapshot(
        long softBudgetNanos,
        long hardBudgetNanos,
        long absoluteBudgetNanos,
        long elapsedNanos,
        boolean hardStop
) {
}
