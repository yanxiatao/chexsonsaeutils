package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import git.chexson.chexsonsaeutils.blockentity.crafting.CompiledTask;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScaledCraftingPatternEligibilityService {

    private ScaledCraftingPatternEligibilityService() {
    }

    public static @Nullable Eligibility analyze(@Nullable Level level, @Nullable IPatternDetails patternDetails) {
        if (!(patternDetails instanceof AECraftingPattern pattern)) {
            return null;
        }
        if (pattern.canSubstitute() || pattern.canSubstituteFluids()) {
            return null;
        }
        ItemStack[] singleGrid = buildScaledCraftingGrid(pattern, 1);
        CompiledTask probeTask = CompiledTask.compileWithCraftingGrid(
                pattern.getDefinition().toStack(),
                singleGrid,
                1,
                1,
                pattern
        );
        if (probeTask == null) {
            return null;
        }
        AbstractHighCapacityCraftingHostBlockEntity.CompletionTemplate template =
                AbstractHighCapacityCraftingHostBlockEntity.probeStableCompletionTemplate(level, pattern, probeTask);
        if (template == null) {
            return null;
        }
        int maxMultiplier = computeMaxMultiplier(pattern);
        if (maxMultiplier < 1) {
            return null;
        }
        return new Eligibility(
                pattern,
                maxMultiplier,
                new GenericStack(template.primary().what(), template.primary().amount()),
                copyRemainders(template.remainders())
        );
    }

    public static ItemStack[] buildScaledCraftingGrid(AECraftingPattern pattern, int multiplier) {
        return new ScaledCraftingPattern(pattern, multiplier, null, Map.of()).getScaledCraftingGridCopies();
    }

    public static int capMultiplier(Eligibility eligibility, long remainingCrafts) {
        if (eligibility == null || remainingCrafts <= 0L) {
            return 0;
        }
        long capped = Math.min(remainingCrafts, eligibility.maxMultiplier());
        if (capped <= 0L) {
            return 0;
        }
        if (capped > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) capped;
    }

    public static ScaledCraftingPattern createScaledPattern(Eligibility eligibility, int multiplier) {
        return new ScaledCraftingPattern(
                eligibility.basePattern(),
                multiplier,
                eligibility.templatePrimary(),
                eligibility.templateRemainders()
        );
    }

    private static int computeMaxMultiplier(AECraftingPattern pattern) {
        long maxMultiplier = Integer.MAX_VALUE;
        List<GenericStack> sparseInputs = pattern.getSparseInputs();
        for (GenericStack sparseInput : sparseInputs) {
            if (sparseInput == null) {
                continue;
            }
            long amount = Math.max(1L, sparseInput.amount());
            maxMultiplier = Math.min(maxMultiplier, Integer.MAX_VALUE / amount);
        }
        if (maxMultiplier <= 0L) {
            return 0;
        }
        if (maxMultiplier > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) maxMultiplier;
    }

    private static Map<AEItemKey, Long> copyRemainders(Map<AEItemKey, Long> remainders) {
        Map<AEItemKey, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<AEItemKey, Long> entry : remainders.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(copy);
    }

    public record Eligibility(
            AECraftingPattern basePattern,
            int maxMultiplier,
            GenericStack templatePrimary,
            Map<AEItemKey, Long> templateRemainders
    ) {
    }
}
