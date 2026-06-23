package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DirectProcessingPatternProvider {

    private List<IPatternDetails> availablePatterns = List.of();

    public List<IPatternDetails> availablePatterns() {
        return availablePatterns;
    }

    public boolean replaceFromSupportedSlots(Map<Integer, IPatternDetails> supportedPatternsBySlot) {
        List<IPatternDetails> rebuiltPatterns = rebuildAvailablePatterns(supportedPatternsBySlot);
        boolean changed = !sameAvailablePatterns(availablePatterns, rebuiltPatterns);
        availablePatterns = rebuiltPatterns;
        return changed;
    }

    public void clear() {
        availablePatterns = List.of();
    }

    private static List<IPatternDetails> rebuildAvailablePatterns(Map<Integer, IPatternDetails> supportedPatternsBySlot) {
        if (supportedPatternsBySlot == null || supportedPatternsBySlot.isEmpty()) {
            return List.of();
        }
        Map<Object, IPatternDetails> uniquePatterns = new LinkedHashMap<>();
        for (IPatternDetails patternDetails : supportedPatternsBySlot.values()) {
            uniquePatterns.putIfAbsent(patternDetails.getDefinition(), patternDetails);
        }
        return List.copyOf(uniquePatterns.values());
    }

    private static boolean sameAvailablePatterns(List<IPatternDetails> left, List<IPatternDetails> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).getDefinition().equals(right.get(index).getDefinition())) {
                return false;
            }
        }
        return true;
    }
}
