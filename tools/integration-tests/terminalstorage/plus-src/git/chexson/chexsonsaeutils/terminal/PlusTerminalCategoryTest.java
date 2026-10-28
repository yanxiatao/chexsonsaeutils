package git.chexson.chexsonsaeutils.terminal;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.ISaveProvider;
import com.extendedae_plus.api.bridge.CompatUpgradeProvider;
import com.extendedae_plus.compat.UpgradeSlotCompat;
import com.extendedae_plus.items.materials.ChannelCardItem;
import com.extendedae_plus.api.storage.InfinityBigIntegerCellHandler;
import com.extendedae_plus.api.storage.InfinityBigIntegerCellInventory;
import com.extendedae_plus.init.ModItems;
import com.extendedae_plus.util.storage.InfinityConstants;
import com.extendedae_plus.util.storage.InfinityDataStorage;
import com.extendedae_plus.util.storage.InfinityStorageManager;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderRegistration;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogic;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.ProviderTestSupport;
import git.chexson.chexsonsaeutils.integration.terminalstorage.extendedaeplus.PlusExactStorageAdapterImpl;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SDK 的同类行为统一验证，按原有集成环境显式运行。 */
final class PlusTerminalCategoryTest {
    @Test
    void verifyLoadedIntegration() {
        org.junit.jupiter.api.Assertions.assertAll("PlusTerminalCategoryTest",
                PlusTerminalStorageSdkCases::verify,
                PlusTerminalCategoryTest::customProviderPreservesParentChannelCardHooks
        );
    }

    /** 通过真实 Plus API 和 FML 注入的父类验证升级库存，不直接引用 mixin 实现类。 */
    private static void customProviderPreservesParentChannelCardHooks() {
        assertTrue(ModList.get().isLoaded("extendedae_plus"));
        assertEquals("1.6.3", ModList.get().getModContainerById("extendedae_plus").orElseThrow()
                .getModInfo().getVersion().toString());
        assertTrue(UpgradeSlotCompat.shouldManageLocalUpgradeInventory(),
                "本验收需要 Plus 自有 compat_upgrades 分支，不能被 AppFlux 升级库存替代");
        var original = providerWithPlusHooks();
        var upgrades = assertInstanceOf(CompatUpgradeProvider.class, original.logic).eap$getCompatUpgrades();
        assertTrue(upgrades.size() > 0, "Plus 必须为父类创建真实升级库存");
        var card = new ItemStack(ModItems.CHANNEL_CARD.get());
        var owner = UUID.fromString("1203d823-d653-4fb8-bdb4-56f46445b18e");
        ChannelCardItem.setOwnerUUID(card, owner);
        ChannelCardItem.setTeamName(card, "provider-persistence-regression");
        assertTrue(upgrades.insertItem(0, card.copy(), false).isEmpty(),
                "频道卡必须通过定制供应器已注册的正常升级校验");
        var copper = AEItemKey.of(Items.COPPER_INGOT);
        assertEquals(3, original.logic.getReturnInv().insert(copper, 3, Actionable.MODULATE, IActionSource.empty()));
        var saved = original.save();
        assertTrue(saved.contains("compat_upgrades"), "super.writeToNBT 必须执行 Plus 保存回调");

        var loaded = providerWithPlusHooks();
        loaded.logic.readFromNBT(saved, ProviderTestSupport.REGISTRIES);
        var loadedUpgrades = assertInstanceOf(CompatUpgradeProvider.class, loaded.logic).eap$getCompatUpgrades();
        var loadedCard = loadedUpgrades.getStackInSlot(0);
        assertTrue(ItemStack.matches(card, loadedCard), "频道卡数量和全部组件必须保留");
        assertEquals(owner, ChannelCardItem.getOwnerUUID(loadedCard));
        assertEquals("provider-persistence-regression", ChannelCardItem.getTeamName(loadedCard));
        assertEquals(1, loadedUpgrades.getInstalledUpgrades(ModItems.CHANNEL_CARD.get()));
        var drops = new ArrayList<ItemStack>();
        loaded.logic.addDrops(drops);
        assertEquals(1, drops.stream().filter(stack -> stack.is(ModItems.CHANNEL_CARD.get()))
                .mapToLong(ItemStack::getCount).sum(), "super.addDrops 必须恰好返还一张频道卡");
        assertEquals(3, drops.stream().filter(copper::matches).mapToLong(ItemStack::getCount).sum(),
                "子类返回栏不得因保留父类 hook 而重读或丢失");

        loaded.logic.clearContent();
        assertTrue(loadedUpgrades.isEmpty(), "super.clearContent 必须清空 Plus 升级库存");
        assertTrue(loaded.logic.getReturnInv().isEmpty());
        var afterClear = new ArrayList<ItemStack>();
        loaded.logic.addDrops(afterClear);
        assertTrue(afterClear.stream().allMatch(ItemStack::isEmpty), "清空后不能再次掉落频道卡或原料");
    }

    private static ProviderTestSupport.Host providerWithPlusHooks() {
        var host = new ProviderTestSupport.Host(new ItemStackHandler(1),
                new ProviderTestSupport.BoundedStorage(2, 64)) {
            @Override public AEItemKey getTerminalIcon() {
                return AEItemKey.of(CustomPatternProviderRegistration.CUSTOM_PATTERN_PROVIDER_ITEM.get());
            }
        };
        host.logic = new CustomPatternProviderLogic(host.node, host, 1, ProviderTestSupport.NO_INTEGRATION);
        return host;
    }

    private static final class PlusTerminalStorageSdkCases {
        private static final AEKey COPPER = AEItemKey.of(Items.COPPER_INGOT), GOLD = AEItemKey.of(Items.GOLD_INGOT);
        private static final BigInteger HUGE = BigInteger.TEN.pow(100), MAX = BigInteger.valueOf(Long.MAX_VALUE);
        private record Fixture(InfinityStorageManager manager, InfinityDataStorage data, ItemStack stack,
                InfinityBigIntegerCellInventory inventory, Counter saves, PlusExactStorageAdapterImpl adapter) {}
        private static final class Counter implements ISaveProvider {
            int calls;
            @Override public void saveChanges() { calls++; }
        }
        private Fixture fixture(Map<AEKey, BigInteger> quantities) {
            assertTrue(ModList.get().isLoaded("extendedae_plus"), "必须由 FML 加载官方 Plus JAR");
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var keys = new ListTag(); var amounts = new ListTag();
            quantities.forEach((key, amount) -> {
                keys.add(key.toTagGeneric(registries));
                var tag = new CompoundTag(); tag.putByteArray("value", amount.toByteArray()); amounts.add(tag);
            });
            var tag = new CompoundTag(); tag.put(InfinityConstants.INFINITY_CELL_KEYS, keys);
            tag.put(InfinityConstants.INFINITY_CELL_AMOUNTS, amounts);
            var data = InfinityDataStorage.loadFromNBT(tag, registries);
            var manager = new InfinityStorageManager(); var identity = UUID.randomUUID(); manager.updateCell(identity, data);
            manager.setDirty(false);
            var stack = new ItemStack(ModItems.INFINITY_BIGINTEGER_CELL_ITEM.get());
            var cellTag = new CompoundTag(); cellTag.putUUID(InfinityConstants.INFINITY_CELL_UUID, identity);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(cellTag));
            var saves = new Counter();
            var inventory = InfinityBigIntegerCellHandler.INSTANCE.getCellInventory(stack, saves);
            var adapter = new PlusExactStorageAdapterImpl((server, id) -> manager.getCell(id));
            return new Fixture(manager, data, stack, inventory, saves, adapter);
        }
        void readsLiveSplitMapsImmutablyWithoutPersistenceOrDirtyWrites() {
            var f = fixture(Map.of(COPPER, HUGE, GOLD, BigInteger.valueOf(7)));
            var before = f.stack.copy(); long revision = f.manager.getStorageRevision();
            var snapshot = f.adapter.read(f.inventory, null);
            assertEquals(HUGE, snapshot.get(COPPER).value()); assertEquals(BigInteger.valueOf(7), snapshot.get(GOLD).value());
            assertTrue(f.adapter.supports(f.inventory));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            assertEquals(revision, f.manager.getStorageRevision()); assertFalse(f.manager.isDirty());
            assertEquals(0, f.saves.calls); assertTrue(ItemStack.matches(before, f.stack));
            f.data.extract(COPPER, 64, true);
            assertEquals(HUGE.subtract(BigInteger.valueOf(64)), f.adapter.read(f.inventory, null).get(COPPER).value());
            assertEquals(HUGE, snapshot.get(COPPER).value());
            assertEquals(revision, f.manager.getStorageRevision(), "结构修订不会随逐键存取变更，不能据此缓存数量");
        }
        void boundaryPromotionDemotionAndSameTotalDifferentKeysStayExact() {
            var f = fixture(Map.of(COPPER, MAX, GOLD, BigInteger.valueOf(64)));
            f.data.insert(COPPER, 1);
            assertEquals(MAX.add(BigInteger.ONE), f.adapter.read(f.inventory, null).get(COPPER).value());
            assertFalse(f.data.longAmounts.containsKey(COPPER));
            f.data.extract(COPPER, 1, true);
            assertEquals(MAX, f.adapter.read(f.inventory, null).get(COPPER).value());
            assertFalse(f.data.bigAmounts.containsKey(COPPER));
            var total = f.data.getItemCount();
            f.data.extract(GOLD, 64, true); f.data.insert(COPPER, 64);
            var result = f.adapter.read(f.inventory, null);
            assertEquals(total, f.data.getItemCount()); assertFalse(result.containsKey(GOLD));
            assertEquals(MAX.add(BigInteger.valueOf(64)), result.get(COPPER).value());
        }
        void freshCellReadsEmptyWithoutAssigningUuidOrLookingUpData() {
            var stack = new ItemStack(ModItems.INFINITY_BIGINTEGER_CELL_ITEM.get());
            var inventory = InfinityBigIntegerCellHandler.INSTANCE.getCellInventory(stack, new Counter());
            var adapter = new PlusExactStorageAdapterImpl((server, id) -> { throw new AssertionError("空盘不得查找或创建数据"); });
            assertTrue(adapter.read(inventory, null).isEmpty()); assertNull(inventory.getUUID());
            assertNull(stack.get(DataComponents.CUSTOM_DATA));
        }
        void filtersVisibleKeysAndMixesPlusWithOwnCellExactly() {
            var f = fixture(Map.of(COPPER, HUGE, GOLD, BigInteger.valueOf(7)));
            var visible = visible(Map.of(COPPER, Long.MAX_VALUE));
            var own = new InfinityCellInventory(new ItemStack(Items.STICK), null);
            own.insert(COPPER, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
            own.insert(COPPER, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
            var reader = new TerminalInventoryReaderImpl(List.of(), List.of(f.adapter, new CellExactStorageAdapterImpl()));
            var amounts = reader.aggregate(List.of(new StorageMount(f.inventory.getUUID(), f.inventory, visible),
                    new StorageMount(own, own, own)), null);
            assertEquals(HUGE.add(MAX.multiply(BigInteger.TWO)), amounts.get(COPPER).value());
            assertFalse(amounts.containsKey(GOLD));
            // 该基线来自 NeoEco 每挂载累加：域真值 + Plus 饱和贡献 + 自家饱和贡献 + 普通来源。
            var domain = BigInteger.TEN.pow(80);
            var corrected = reader.reconcile(Map.of(COPPER, StorageAmount.finite(domain.add(MAX.multiply(BigInteger.TWO)).add(BigInteger.valueOf(64)))),
                    List.of(new StorageMount(f.inventory.getUUID(), f.inventory, visible), new StorageMount(own, own, own)), null);
            assertEquals(domain.add(HUGE).add(MAX.multiply(BigInteger.TWO)).add(BigInteger.valueOf(64)), corrected.get(COPPER).value());
        }
        void malformedSplitMapFailsInsteadOfDoubleCounting() {
            var f = fixture(Map.of(COPPER, HUGE)); f.data.longAmounts.put(COPPER, 1);
            assertThrows(IllegalStateException.class, () -> f.adapter.read(f.inventory, null));
        }
        void sharedUuidHasOneContributionAcrossDistinctInventoryObjects() {
            var f = fixture(Map.of(COPPER, HUGE));
            var alias = InfinityBigIntegerCellHandler.INSTANCE.getCellInventory(f.stack.copy(), new Counter());
            assertNotSame(f.inventory, alias);
            var view = visible(Map.of(COPPER, Long.MAX_VALUE));
            var first = new StorageMount("slot-a", f.inventory, view);
            var second = new StorageMount("slot-b", alias, view);
            assertEquals(f.adapter.identity(first), f.adapter.identity(second));
            var reader = new TerminalInventoryReaderImpl(List.of(), List.of(f.adapter));
            assertEquals(HUGE, reader.aggregate(List.of(first, second), null).get(COPPER).value());
        }
        private static MEStorage visible(Map<AEKey, Long> amounts) {
            return new MEStorage() {
                @Override public Component getDescription() { return Component.literal("过滤可见挂载"); }
                @Override public void getAvailableStacks(KeyCounter out) { amounts.forEach(out::add); }
            };
        }

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("PlusTerminalStorageSdkCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("readsLiveSplitMapsImmutablyWithoutPersistenceOrDirtyWrites", () -> new PlusTerminalStorageSdkCases().readsLiveSplitMapsImmutablyWithoutPersistenceOrDirtyWrites()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("boundaryPromotionDemotionAndSameTotalDifferentKeysStayExact", () -> new PlusTerminalStorageSdkCases().boundaryPromotionDemotionAndSameTotalDifferentKeysStayExact()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("freshCellReadsEmptyWithoutAssigningUuidOrLookingUpData", () -> new PlusTerminalStorageSdkCases().freshCellReadsEmptyWithoutAssigningUuidOrLookingUpData()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("filtersVisibleKeysAndMixesPlusWithOwnCellExactly", () -> new PlusTerminalStorageSdkCases().filtersVisibleKeysAndMixesPlusWithOwnCellExactly()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("malformedSplitMapFailsInsteadOfDoubleCounting", () -> new PlusTerminalStorageSdkCases().malformedSplitMapFailsInsteadOfDoubleCounting()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("sharedUuidHasOneContributionAcrossDistinctInventoryObjects", () -> new PlusTerminalStorageSdkCases().sharedUuidHasOneContributionAcrossDistinctInventoryObjects())
            );
        }
    }
}
