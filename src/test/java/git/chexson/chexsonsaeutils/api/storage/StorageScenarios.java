package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import java.math.BigInteger;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;

/** 存储与终端的行为场景；由类别测试统一调用。 */
public final class StorageScenarios {
    private StorageScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.api.storage",
                () -> ExactStorageExtractionCases.verify(temporaryRoot)
        );
    }

    /** ExactStorageExtractionCases 的独立场景夹具，按类别统一执行。 */
    public static final class ExactStorageExtractionCases {
        private static final AEKey KEY = AEItemKey.of(Items.COPPER_INGOT);
        private static final IActionSource SOURCE = IActionSource.empty();
        private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

        void realAeNetworkRoutesPlainCellBothWaysAndHonorsExtractionFilterWithoutNeoEco() {
            var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
            int[] activity = {0};
            var drive = new appeng.me.storage.DriveWatcher(cell, () -> activity[0]++);
            var network = new appeng.me.storage.NetworkStorage(); network.mount(0, drive);
            var huge = BigInteger.TEN.pow(100);
            assertInstanceOf(ExactStorageInsertion.class, network);
            assertInstanceOf(ExactStorageExtraction.class, network);
            assertEquals(huge, ExactStorageInsertion.insert(network, KEY, huge, Actionable.SIMULATE, SOURCE));
            assertEquals(BigInteger.ZERO, cell.exactAmount(KEY));
            assertEquals(0, activity[0]);
            assertEquals(huge, ExactStorageInsertion.insert(network, KEY, huge, Actionable.MODULATE, SOURCE));
            assertEquals(huge, ExactStorageExtraction.extract(network, KEY, huge, Actionable.SIMULATE, SOURCE));
            assertEquals(huge, cell.exactAmount(KEY));
            drive.setExtractFiltering(true, true); drive.setAllowExtraction(false);
            assertEquals(BigInteger.ZERO, ExactStorageExtraction.extract(network, KEY, huge, Actionable.MODULATE, SOURCE));
            drive.setAllowExtraction(true);
            assertEquals(huge, ExactStorageExtraction.extract(network, KEY, huge, Actionable.MODULATE, SOURCE));
            assertEquals(BigInteger.ZERO, cell.exactAmount(KEY));
            assertEquals(2, activity[0]);
        }

        void insertionRoutingOffersPreferredMountBeforeOtherMountsInSamePriorityGroup() {
            class PreferredCell extends InfinityCellInventory {
                PreferredCell() { super(new ItemStack(Items.STICK), null); }
                @Override public boolean isPreferredStorageFor(AEKey what, IActionSource source) { return true; }
            }
            var ordinary = new InfinityCellInventory(new ItemStack(Items.STICK), null);
            var preferred = new PreferredCell(); var huge = BigInteger.TEN.pow(90);
            assertEquals(huge, ExactStorageInsertion.insertIntoMounts(List.of(List.of(ordinary, preferred)),
                    s -> false, KEY, huge, Actionable.MODULATE, SOURCE));
            assertEquals(huge, preferred.exactAmount(KEY));
            assertEquals(BigInteger.ZERO, ordinary.exactAmount(KEY));
        }

        void mixedMountsKeepPrioritySkipRemovedAndPassFullRemainderOnlyOnce() {
            var legacy = new LegacyStorage(23L);
            var exact = new InfinityCellInventory(new ItemStack(Items.STICK), null);
            var demand = BigInteger.TEN.pow(90);
            exact.insertExact(KEY, demand.multiply(BigInteger.TWO), Actionable.MODULATE, SOURCE);
            var removed = new LegacyStorage(Long.MAX_VALUE);
            List<List<MEStorage>> mounts = List.of(List.of(legacy, removed), List.of(exact));
            assertEquals(demand, ExactStorageExtraction.extractFromMounts(mounts, s -> s == removed,
                    null, KEY, demand, Actionable.SIMULATE, SOURCE));
            assertEquals(23L, legacy.available);
            assertEquals(demand.multiply(BigInteger.TWO), exact.exactAmount(KEY));
            assertEquals(0, removed.calls);
            assertEquals(demand, ExactStorageExtraction.extractFromMounts(mounts, s -> s == removed,
                    null, KEY, demand, Actionable.MODULATE, SOURCE));
            assertEquals(0L, legacy.available);
            assertEquals(demand.add(BigInteger.valueOf(23)), exact.exactAmount(KEY));
            assertEquals(2, legacy.calls);
            assertSame(SOURCE, legacy.source);
        }

        void legacyStorageReceivesOnlyOneLongWindow() {
            var legacy = new LegacyStorage(Long.MAX_VALUE);
            assertEquals(MAX, ExactStorageExtraction.extract(legacy, KEY, MAX.pow(3), Actionable.MODULATE, SOURCE));
            assertEquals(1, legacy.calls);
            assertEquals(Long.MAX_VALUE, legacy.request);
            assertEquals(0L, legacy.available);
            assertEquals(BigInteger.ZERO, ExactStorageExtraction.extract(legacy, KEY, BigInteger.ZERO, Actionable.MODULATE, SOURCE));
            assertEquals(1, legacy.calls);
            assertThrows(IllegalArgumentException.class, () -> ExactStorageExtraction.extract(legacy,
                    KEY, BigInteger.valueOf(-1), Actionable.MODULATE, SOURCE));
        }

        void sourcePolicyCanExcludeMountWithoutDebitingIt() {
            var excluded = new LegacyStorage(100);
            var ordinary = new LegacyStorage(7);
            MountedStorageExtraction policy = (storage, key, amount, mode, source) -> storage == excluded
                    ? BigInteger.ZERO : ExactStorageExtraction.extract(storage, key, amount, mode, source);
            assertEquals(BigInteger.valueOf(7), ExactStorageExtraction.extractFromMounts(
                    List.of(List.of(excluded, ordinary)), s -> false, policy,
                    KEY, BigInteger.valueOf(80), Actionable.MODULATE, SOURCE));
            assertEquals(0, excluded.calls);
            assertEquals(100, excluded.available);
        }

        void exactProviderResultsAreValidatedBeforeCpuAccounting() {
            class InvalidStorage extends LegacyStorage implements ExactStorageExtraction {
                BigInteger result;
                InvalidStorage(BigInteger result) { super(0); this.result = result; }
                @Override public BigInteger extractExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
                    return result;
                }
            }
            for (var storage : List.of(new InvalidStorage(null), new InvalidStorage(BigInteger.valueOf(-1)),
                    new InvalidStorage(BigInteger.TEN.pow(91)))) {
                assertThrows(IllegalStateException.class, () -> ExactStorageExtraction.extract(storage, KEY,
                        BigInteger.TEN.pow(90), Actionable.MODULATE, SOURCE));
                assertEquals(0, storage.calls);
            }
        }

        private static class LegacyStorage implements MEStorage {
            long available, request;
            int calls;
            IActionSource source;
            LegacyStorage(long available) { this.available = available; }
            @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                calls++; request = amount; this.source = source;
                long got = KEY.equals(key) ? Math.min(available, amount) : 0;
                if (mode == Actionable.MODULATE) available -= got;
                return got;
            }
            @Override public Component getDescription() { return Component.literal("legacy"); }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ExactStorageExtractionCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactStorageExtractionCases.realAeNetworkRoutesPlainCellBothWaysAndHonorsExtractionFilterWithoutNeoEco", () -> new ExactStorageExtractionCases().realAeNetworkRoutesPlainCellBothWaysAndHonorsExtractionFilterWithoutNeoEco()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactStorageExtractionCases.insertionRoutingOffersPreferredMountBeforeOtherMountsInSamePriorityGroup", () -> new ExactStorageExtractionCases().insertionRoutingOffersPreferredMountBeforeOtherMountsInSamePriorityGroup()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactStorageExtractionCases.mixedMountsKeepPrioritySkipRemovedAndPassFullRemainderOnlyOnce", () -> new ExactStorageExtractionCases().mixedMountsKeepPrioritySkipRemovedAndPassFullRemainderOnlyOnce()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactStorageExtractionCases.legacyStorageReceivesOnlyOneLongWindow", () -> new ExactStorageExtractionCases().legacyStorageReceivesOnlyOneLongWindow()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactStorageExtractionCases.sourcePolicyCanExcludeMountWithoutDebitingIt", () -> new ExactStorageExtractionCases().sourcePolicyCanExcludeMountWithoutDebitingIt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ExactStorageExtractionCases.exactProviderResultsAreValidatedBeforeCpuAccounting", () -> new ExactStorageExtractionCases().exactProviderResultsAreValidatedBeforeCpuAccounting())
            );
        }
    }
}
