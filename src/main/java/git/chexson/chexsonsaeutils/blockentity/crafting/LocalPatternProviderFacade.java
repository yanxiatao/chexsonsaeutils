package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalPatternProviderFacade {

    private final AbstractHighCapacityCraftingHostBlockEntity host;
    private final PagedPatternInventory pagedPatternInventory;
    private final DirtySlotPatternRefreshScheduler refreshScheduler;
    private final DecodedPatternEntryCache decodedPatternEntryCache;
    private final Map<Integer, IMolecularAssemblerSupportedPattern> supportedPatternsBySlot = new LinkedHashMap<>();
    private List<IPatternDetails> cachedAvailablePatterns = List.of();
    private boolean providerVisibleSetUpdatePending;

    public LocalPatternProviderFacade(
            AbstractHighCapacityCraftingHostBlockEntity host,
            PagedPatternInventory pagedPatternInventory,
            DirtySlotPatternRefreshScheduler refreshScheduler,
            DecodedPatternEntryCache decodedPatternEntryCache
    ) {
        this.host = host;
        this.pagedPatternInventory = pagedPatternInventory;
        this.refreshScheduler = refreshScheduler;
        this.decodedPatternEntryCache = decodedPatternEntryCache;
    }

    public void markAllDirty() {
        refreshScheduler.markRangeDirty(0, pagedPatternInventory.getTotalSlots());
    }

    public void clear() {
        supportedPatternsBySlot.clear();
        cachedAvailablePatterns = List.of();
    }

    public void refreshDirtyPatterns() {
        if (!refreshScheduler.hasPendingWork()) {
            return;
        }
        Level level = host.getLevel();
        if (level == null) {
            return;
        }
        boolean changed = false;
        boolean localOptimizationEnabled = host.isLocalOptimizationEnabled();
        List<Integer> dirtySlots = refreshScheduler.drainDirtySlots();
        List<Integer> slotsToRefresh = dirtySlots;
        if (!localOptimizationEnabled) {
            slotsToRefresh = new ArrayList<>(pagedPatternInventory.getTotalSlots());
            for (int globalSlot = 0; globalSlot < pagedPatternInventory.getTotalSlots(); globalSlot++) {
                slotsToRefresh.add(globalSlot);
            }
        }
        host.recordDirtyRefreshScan(slotsToRefresh.size());
        for (int globalSlot : slotsToRefresh) {
            changed |= refreshSingleSlot(level, globalSlot, localOptimizationEnabled);
        }
        if (changed) {
            List<IPatternDetails> rebuiltPatterns = rebuildUniqueAvailablePatterns();
            boolean providerVisibleSetChanged = !sameAvailablePatterns(cachedAvailablePatterns, rebuiltPatterns);
            cachedAvailablePatterns = rebuiltPatterns;
            if (providerVisibleSetChanged) {
                host.recordProviderUpdate();
                providerVisibleSetUpdatePending = true;
            }
        }
    }

    public List<IPatternDetails> getAvailablePatterns() {
        refreshDirtyPatterns();
        return cachedAvailablePatterns;
    }

    public boolean consumeProviderVisibleSetUpdatePending() {
        boolean pending = providerVisibleSetUpdatePending;
        providerVisibleSetUpdatePending = false;
        return pending;
    }

    public boolean contains(IPatternDetails patternDetails) {
        for (IPatternDetails cached : cachedAvailablePatterns) {
            if (samePatternIdentity(cached, patternDetails)) {
                return true;
            }
        }
        return false;
    }

    public int activePatternCount() {
        return supportedPatternsBySlot.size();
    }

    private boolean refreshSingleSlot(Level level, int globalSlot, boolean localOptimizationEnabled) {
        ItemStack stack = pagedPatternInventory.getVirtualSlot(globalSlot);
        if (stack.isEmpty()) {
            decodedPatternEntryCache.invalidate(globalSlot);
            return supportedPatternsBySlot.remove(globalSlot) != null;
        }

        @Nullable DecodedPatternEntryCache.Entry cacheEntry = localOptimizationEnabled
                ? decodedPatternEntryCache.get(globalSlot)
                : null;
        if (localOptimizationEnabled && cacheEntry != null && decodedPatternEntryCache.matches(globalSlot, stack)) {
            host.recordDecodeCacheHit();
            host.recordLocalOptimizationHit();
            IMolecularAssemblerSupportedPattern cachedPattern = asAssemblerPattern(cacheEntry.patternDetails());
            if (cachedPattern != null) {
                IMolecularAssemblerSupportedPattern previous = supportedPatternsBySlot.put(globalSlot, cachedPattern);
                return !samePattern(previous, cachedPattern);
            }
            return supportedPatternsBySlot.remove(globalSlot) != null;
        }

        host.recordDecodeCall();
        IPatternDetails decodedPattern = PatternDetailsHelper.decodePattern(stack, level);
        decodedPatternEntryCache.put(globalSlot, stack, decodedPattern);
        IMolecularAssemblerSupportedPattern supportedPattern = asAssemblerPattern(decodedPattern);
        if (supportedPattern != null) {
            IMolecularAssemblerSupportedPattern previous = supportedPatternsBySlot.put(globalSlot, supportedPattern);
            return !samePattern(previous, supportedPattern);
        }
        return supportedPatternsBySlot.remove(globalSlot) != null;
    }

    private List<IPatternDetails> rebuildUniqueAvailablePatterns() {
        Map<AEItemKey, IMolecularAssemblerSupportedPattern> uniquePatterns = new LinkedHashMap<>();
        for (IMolecularAssemblerSupportedPattern pattern : supportedPatternsBySlot.values()) {
            uniquePatterns.putIfAbsent(pattern.getDefinition(), pattern);
        }
        return List.copyOf(uniquePatterns.values());
    }

    private static boolean sameAvailablePatterns(List<IPatternDetails> left, List<IPatternDetails> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!samePatternIdentity(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePattern(
            @Nullable IMolecularAssemblerSupportedPattern left,
            @Nullable IMolecularAssemblerSupportedPattern right
    ) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return samePatternIdentity(left, right);
    }

    private static boolean samePatternIdentity(IPatternDetails left, IPatternDetails right) {
        return left.getDefinition().equals(right.getDefinition());
    }

    @Nullable
    private static IMolecularAssemblerSupportedPattern asAssemblerPattern(@Nullable IPatternDetails patternDetails) {
        if (patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern) {
            return supportedPattern;
        }
        return null;
    }
}
