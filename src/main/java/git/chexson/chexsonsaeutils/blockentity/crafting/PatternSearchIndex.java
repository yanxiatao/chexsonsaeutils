package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PatternSearchIndex {

    public List<Integer> findAllMatches(PagedPatternInventory inventory, String rawQuery, @Nullable Level level) {
        String query = normalize(rawQuery);
        if (query.isEmpty() || level == null) {
            return List.of();
        }
        List<Integer> matches = new ArrayList<>();
        for (int slot = 0; slot < inventory.getTotalSlots(); slot++) {
            ItemStack stack = inventory.getVirtualSlot(slot);
            if (!stack.isEmpty() && matches(stack, query, level)) {
                matches.add(slot);
            }
        }
        return List.copyOf(matches);
    }

    @Nullable
    public Integer findFirstMatch(PagedPatternInventory inventory, String rawQuery, @Nullable Level level) {
        List<Integer> matches = findAllMatches(inventory, rawQuery, level);
        return matches.isEmpty() ? null : matches.get(0);
    }

    public int countMatches(PagedPatternInventory inventory, String rawQuery, @Nullable Level level) {
        return findAllMatches(inventory, rawQuery, level).size();
    }

    private boolean matches(ItemStack encodedPattern, String query, Level level) {
        if (matchesItemStack(encodedPattern, query)) {
            return true;
        }
        IPatternDetails patternDetails = PatternDetailsHelper.decodePattern(encodedPattern, level);
        if (patternDetails == null) {
            return false;
        }
        for (GenericStack output : patternDetails.getOutputs()) {
            if (output != null && matchesGenericStack(output, query)) {
                return true;
            }
        }
        for (IPatternDetails.IInput input : patternDetails.getInputs()) {
            for (GenericStack possibleInput : input.getPossibleInputs()) {
                if (possibleInput != null && matchesGenericStack(possibleInput, query)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesGenericStack(GenericStack stack, String query) {
        if (!(stack.what() instanceof appeng.api.stacks.AEItemKey itemKey)) {
            return false;
        }
        return matchesItemStack(itemKey.toStack(), query);
    }

    private boolean matchesItemStack(ItemStack stack, String query) {
        if (stack.isEmpty()) {
            return false;
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalize(Component.translatable(stack.getDescriptionId()).getString()));
        candidates.add(normalize(stack.getHoverName().getString()));
        var key = stack.getItemHolder().unwrapKey();
        key.ifPresent(resourceKey -> candidates.add(normalize(resourceKey.location().toString())));
        for (String candidate : candidates) {
            if (!candidate.isEmpty() && candidate.contains(query)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
