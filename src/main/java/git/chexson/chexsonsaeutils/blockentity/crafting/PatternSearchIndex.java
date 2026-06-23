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
        return findAllMatchesWithStats(inventory, null, rawQuery, level).matches();
    }

    public MatchResult findAllMatchesWithStats(
            PagedPatternInventory inventory,
            @Nullable DecodedPatternEntryCache decodedPatternEntryCache,
            String rawQuery,
            @Nullable Level level
    ) {
        String query = normalize(rawQuery);
        if (query.isEmpty() || level == null) {
            return new MatchResult(List.of(), 0, 0, 0);
        }
        List<Integer> matches = new ArrayList<>();
        int scannedSlots = 0;
        int decodeCacheHits = 0;
        int decodeCalls = 0;
        for (int slot = 0; slot < inventory.getTotalSlots(); slot++) {
            scannedSlots++;
            ItemStack stack = inventory.getVirtualSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            MatchState matchState = matches(stack, slot, decodedPatternEntryCache, query, level);
            if (matchState.decodeCacheHit()) {
                decodeCacheHits++;
            }
            if (matchState.decodeCall()) {
                decodeCalls++;
            }
            if (matchState.matched()) {
                matches.add(slot);
            }
        }
        return new MatchResult(List.copyOf(matches), scannedSlots, decodeCacheHits, decodeCalls);
    }

    @Nullable
    public Integer findFirstMatch(PagedPatternInventory inventory, String rawQuery, @Nullable Level level) {
        List<Integer> matches = findAllMatches(inventory, rawQuery, level);
        return matches.isEmpty() ? null : matches.get(0);
    }

    public int countMatches(PagedPatternInventory inventory, String rawQuery, @Nullable Level level) {
        return findAllMatches(inventory, rawQuery, level).size();
    }

    private MatchState matches(
            ItemStack encodedPattern,
            int slot,
            @Nullable DecodedPatternEntryCache decodedPatternEntryCache,
            String query,
            Level level
    ) {
        if (matchesItemStack(encodedPattern, query)) {
            return MatchState.ITEM_MATCH;
        }
        IPatternDetails patternDetails = null;
        boolean decodeCacheHit = false;
        if (decodedPatternEntryCache != null && decodedPatternEntryCache.matches(slot, encodedPattern)) {
            DecodedPatternEntryCache.Entry cacheEntry = decodedPatternEntryCache.get(slot);
            if (cacheEntry != null) {
                patternDetails = cacheEntry.patternDetails();
                decodeCacheHit = true;
            }
        }
        boolean decodeCall = false;
        if (patternDetails == null) {
            patternDetails = PatternDetailsHelper.decodePattern(encodedPattern, level);
            decodeCall = true;
            if (decodedPatternEntryCache != null) {
                decodedPatternEntryCache.put(slot, encodedPattern, patternDetails);
            }
        }
        if (patternDetails == null) {
            return new MatchState(false, decodeCacheHit, decodeCall);
        }
        for (GenericStack output : patternDetails.getOutputs()) {
            if (output != null && matchesGenericStack(output, query)) {
                return new MatchState(true, decodeCacheHit, decodeCall);
            }
        }
        for (IPatternDetails.IInput input : patternDetails.getInputs()) {
            for (GenericStack possibleInput : input.getPossibleInputs()) {
                if (possibleInput != null && matchesGenericStack(possibleInput, query)) {
                    return new MatchState(true, decodeCacheHit, decodeCall);
                }
            }
        }
        return new MatchState(false, decodeCacheHit, decodeCall);
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

    public record MatchResult(
            List<Integer> matches,
            int scannedSlots,
            int decodeCacheHits,
            int decodeCalls
    ) {
    }

    private record MatchState(boolean matched, boolean decodeCacheHit, boolean decodeCall) {
        private static final MatchState ITEM_MATCH = new MatchState(true, false, false);
    }
}
