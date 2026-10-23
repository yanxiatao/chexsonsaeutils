package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.menu.matrix.DecodedPatternEntryCache;
import git.chexson.chexsonsaeutils.menu.matrix.DirtySlotPatternRefreshScheduler;
import git.chexson.chexsonsaeutils.menu.matrix.PatternSlotStore;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Collections;

public final class LocalPatternProviderFacade {

    private final AbstractExtremeAssemblerHostBlockEntity host;
    private final PatternSlotStore patternStore;
    private final DirtySlotPatternRefreshScheduler refreshScheduler;
    private final DecodedPatternEntryCache decodedPatternEntryCache;
    private final Map<Integer, IMolecularAssemblerSupportedPattern> supportedPatternsBySlot = new LinkedHashMap<>();
    private List<IPatternDetails> cachedAvailablePatterns = List.of();
    /** 提供者通常回传登记的同一个样板对象；接单检查不应重复解读其组件。 */
    private final Set<IPatternDetails> availablePatternInstances = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 等价样板壳仍沿用去色后的完整定义身份，不能只按物品或输出判断。 */
    private final Set<PatternIdentity> availablePatternIdentities = new HashSet<>();
    private boolean providerVisibleSetUpdatePending;

    public LocalPatternProviderFacade(
            AbstractExtremeAssemblerHostBlockEntity host,
            PatternSlotStore patternStore,
            DirtySlotPatternRefreshScheduler refreshScheduler,
            DecodedPatternEntryCache decodedPatternEntryCache
    ) {
        this.host = host;
        this.patternStore = patternStore;
        this.refreshScheduler = refreshScheduler;
        this.decodedPatternEntryCache = decodedPatternEntryCache;
    }

    public void markAllDirty() {
        refreshScheduler.markRangeDirty(0, patternStore.getTotalSlots());
    }

    public void clear() {
        supportedPatternsBySlot.clear();
        replaceAvailablePatterns(List.of());
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
            slotsToRefresh = new ArrayList<>(patternStore.getTotalSlots());
            for (int globalSlot = 0; globalSlot < patternStore.getTotalSlots(); globalSlot++) {
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
            replaceAvailablePatterns(rebuiltPatterns);
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
        return availablePatternInstances.contains(patternDetails)
                || availablePatternIdentities.contains(PatternIdentity.capture(patternDetails));
    }

    /** 样板刷新与清空共用的原子替换口；索引和公开列表必须来自同一份已解码名单。 */
    void replaceAvailablePatterns(List<IPatternDetails> patterns) {
        var snapshot = List.copyOf(patterns);
        var identities = new HashSet<PatternIdentity>();
        for (var pattern : snapshot) identities.add(PatternIdentity.capture(pattern));
        availablePatternInstances.clear();
        availablePatternIdentities.clear();
        availablePatternInstances.addAll(snapshot);
        availablePatternIdentities.addAll(identities);
        cachedAvailablePatterns = snapshot;
    }

    public int activePatternCount() {
        return supportedPatternsBySlot.size();
    }

    private boolean refreshSingleSlot(Level level, int globalSlot, boolean localOptimizationEnabled) {
        ItemStack stack = patternStore.getStackInSlot(globalSlot);
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
        return uniquePatternsInOrder(supportedPatternsBySlot.values());
    }

    static List<IPatternDetails> uniquePatternsInOrder(
            Iterable<? extends IPatternDetails> patterns
    ) {
        Map<PatternIdentity, IPatternDetails> uniquePatterns = new LinkedHashMap<>();
        for (IPatternDetails pattern : patterns) {
            uniquePatterns.putIfAbsent(PatternIdentity.capture(pattern), pattern);
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
        return PatternIdentity.capture(left).equals(PatternIdentity.capture(right));
    }

    @Nullable
    private static IMolecularAssemblerSupportedPattern asAssemblerPattern(@Nullable IPatternDetails patternDetails) {
        if (patternDetails instanceof IMolecularAssemblerSupportedPattern supportedPattern) {
            return supportedPattern;
        }
        return null;
    }

    private record PatternIdentity(AEItemKey definition) {
        private static PatternIdentity capture(IPatternDetails pattern) {
            ItemStack definitionStack = pattern.getDefinition().toStack(1);
            definitionStack.remove(DataComponents.DYED_COLOR);
            removeLegacyDisplayColor(definitionStack);
            return new PatternIdentity(AEItemKey.of(definitionStack));
        }

        private static void removeLegacyDisplayColor(ItemStack stack) {
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData == null) {
                return;
            }
            var tag = customData.copyTag();
            if (!tag.contains("display")) {
                return;
            }
            var displayTag = tag.getCompound("display");
            displayTag.remove("color");
            if (displayTag.isEmpty()) {
                tag.remove("display");
            } else {
                tag.put("display", displayTag);
            }
            if (tag.isEmpty()) {
                stack.remove(DataComponents.CUSTOM_DATA);
            } else {
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            }
        }
    }
}
