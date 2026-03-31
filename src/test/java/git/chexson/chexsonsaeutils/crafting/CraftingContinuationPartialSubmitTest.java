package git.chexson.chexsonsaeutils.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationPartialSubmit;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingContinuationPartialSubmitTest {

    @Test
    void extractsBothPlannedAndPreviouslyMissingInitialInputs() {
        DummyKey key = new DummyKey("missing_raw_material");
        KeyCounter usedItems = new KeyCounter();
        usedItems.add(key, 3L);
        KeyCounter missingItems = new KeyCounter();
        missingItems.add(key, 7L);
        ICraftingPlan plan = new TestCraftingPlan(key, usedItems, missingItems);
        TrackingStorage storage = new TrackingStorage(Map.of(key, 8L));
        IGrid grid = createGrid(storage);
        ListCraftingInventory cpuInventory = new ListCraftingInventory(ignored -> {
        });
        IActionSource actionSource = createActionSource();

        KeyCounter waitingItems = extractAvailableInitialItems(
                plan,
                grid,
                cpuInventory,
                actionSource
        );

        assertEquals(8L, cpuInventory.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE));
        assertEquals(2L, waitingItems.get(key));
        assertEquals(8L, storage.getExtracted(key));
        assertEquals(0L, storage.getAvailable(key));
    }

    private static IGrid createGrid(TrackingStorage storage) {
        IStorageService storageService = (IStorageService) Proxy.newProxyInstance(
                CraftingContinuationPartialSubmitTest.class.getClassLoader(),
                new Class<?>[]{IStorageService.class},
                (proxy, method, args) -> {
                    if ("getInventory".equals(method.getName())) {
                        return storage;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (IGrid) Proxy.newProxyInstance(
                CraftingContinuationPartialSubmitTest.class.getClassLoader(),
                new Class<?>[]{IGrid.class},
                (proxy, method, args) -> {
                    if ("getStorageService".equals(method.getName())) {
                        return storageService;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static IActionSource createActionSource() {
        return (IActionSource) Proxy.newProxyInstance(
                CraftingContinuationPartialSubmitTest.class.getClassLoader(),
                new Class<?>[]{IActionSource.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static KeyCounter extractAvailableInitialItems(
            ICraftingPlan plan,
            IGrid grid,
            ListCraftingInventory cpuInventory,
            IActionSource actionSource
    ) {
        try {
            Method method = CraftingContinuationPartialSubmit.class.getDeclaredMethod(
                    "extractAvailableInitialItems",
                    ICraftingPlan.class,
                    IGrid.class,
                    ListCraftingInventory.class,
                    IActionSource.class
            );
            method.setAccessible(true);
            return (KeyCounter) method.invoke(null, plan, grid, cpuInventory, actionSource);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to invoke extractAvailableInitialItems", exception);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private record TestCraftingPlan(
            GenericStack finalOutput,
            KeyCounter usedItems,
            KeyCounter missingItems
    ) implements ICraftingPlan {
        TestCraftingPlan(AEKey key, KeyCounter usedItems, KeyCounter missingItems) {
            this(new GenericStack(key, 10L), usedItems, missingItems);
        }

        @Override
        public long bytes() {
            return 0L;
        }

        @Override
        public boolean simulation() {
            return true;
        }

        @Override
        public boolean multiplePaths() {
            return false;
        }

        @Override
        public KeyCounter emittedItems() {
            return new KeyCounter();
        }

        @Override
        public Map<IPatternDetails, Long> patternTimes() {
            return Map.of();
        }
    }

    private static final class TrackingStorage implements MEStorage {
        private final Map<AEKey, Long> available = new java.util.LinkedHashMap<>();
        private final Map<AEKey, Long> extracted = new java.util.LinkedHashMap<>();

        private TrackingStorage(Map<AEKey, Long> available) {
            this.available.putAll(available);
        }

        @Override
        public long extract(AEKey what, long amount, appeng.api.config.Actionable mode, IActionSource source) {
            long stored = available.getOrDefault(what, 0L);
            long extractedAmount = Math.min(stored, amount);
            if (mode == appeng.api.config.Actionable.MODULATE && extractedAmount > 0L) {
                available.put(what, stored - extractedAmount);
                extracted.merge(what, extractedAmount, Long::sum);
            }
            return extractedAmount;
        }

        @Override
        public Component getDescription() {
            return Component.literal("test-storage");
        }

        private long getAvailable(AEKey key) {
            return available.getOrDefault(key, 0L);
        }

        private long getExtracted(AEKey key) {
            return extracted.getOrDefault(key, 0L);
        }
    }
}
