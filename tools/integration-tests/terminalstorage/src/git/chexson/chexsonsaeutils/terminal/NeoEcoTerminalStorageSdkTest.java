package git.chexson.chexsonsaeutils.terminal;

import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountSource;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountCollector;
import cn.dancingsnow.neoecoae.impl.storage.SaturatingStackAccumulator;
import git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoNetworkExactInventoryImpl;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SDK 行为验证；挂载回调按 NeoEco 字节码顺序调用，不宣称已启动游戏 mixin。 */
final class NeoEcoTerminalStorageSdkTest {
    private static final AEKey KEY = AEItemKey.of(Items.COPPER_INGOT);
    private static final BigInteger HUGE = BigInteger.TEN.pow(100);
    private final NeoEcoNetworkExactInventoryImpl reader = new NeoEcoNetworkExactInventoryImpl();
    @Test void ordinaryLongMountsAreSummedExactlyBeforeSaturation() {
        var count = 6_000_000_000_000_000_000L;
        var result = reader.readFrom(network(List.of(narrow(count), narrow(count))));
        assertEquals(BigInteger.valueOf(count).multiply(BigInteger.TWO), result.get(KEY).value());
    }
    @Test void confirmsOwnWideAmountWithUnknownEmptySourcesButDetectsSameKeyContribution() {
        var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
        cell.insert(KEY, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
        cell.insert(KEY, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
        var mounts = List.of(new StorageMount(cell, cell, cell));
        var normalize = new TerminalInventoryReaderImpl(List.of(), List.of(new CellExactStorageAdapterImpl()), reader);
        var extra = AEItemKey.of(Items.GOLD_INGOT);
        var unrelated = new MEStorage() {
            @Override public Component getDescription() { return Component.literal("未适配的其他键来源"); }
            @Override public void getAvailableStacks(KeyCounter out) { out.add(extra, 32); }
        };
        var net = network(List.of(cell, unrelated));
        for (int step = 0; step < 2; step++) {
            var baseline = reader.readFrom(net);
            var known = normalize.aggregate(mounts, null);
            var snapshot = normalize.confirmReadings(normalize.reconcile(baseline, mounts, null), known,
                    net.getAvailableStacks(), java.util.Set.of(), false, java.util.Set.of());
            assertNull(snapshot.unverified().get(KEY));
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)
                    .subtract(BigInteger.valueOf(64L * step)), snapshot.amounts().get(KEY).value());
            assertEquals(32L, snapshot.unverified().get(extra));
            assertFalse(snapshot.amounts().containsKey(extra));
            cell.extract(KEY, 64, Actionable.MODULATE, IActionSource.empty());
        }
        var overlap = network(List.of(cell, narrow(64)));
        var known = normalize.aggregate(mounts, null);
        var partial = normalize.confirmReadings(normalize.reconcile(reader.readFrom(overlap), mounts, null), known,
                overlap.getAvailableStacks(), java.util.Set.of(), false, java.util.Set.of());
        assertEquals(Long.MAX_VALUE, partial.unverified().get(KEY));
        assertEquals(known.get(KEY), partial.amounts().get(KEY));
        // 仅剩自家盘时，即使存在尚未定位的空提供者，也能证明该键没有额外贡献。
        var alone = network(List.of(cell));
        var confirmed = normalize.confirmReadings(normalize.reconcile(reader.readFrom(alone), mounts, null), known,
                alone.getAvailableStacks(), java.util.Set.of(), false, java.util.Set.of());
        assertTrue(confirmed.unverified().isEmpty());
        assertEquals(known, confirmed.amounts());
    }
    @Test void domainAndOwnCellMixWithoutDoubleCountingAndSaturatedUpdatesRemainVisible() {
        var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
        cell.insert(KEY, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
        cell.insert(KEY, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
        var mounts = List.of(new StorageMount(cell, cell, cell));
        var normalize = new TerminalInventoryReaderImpl(List.of(), List.of(new CellExactStorageAdapterImpl()));
        var net = network(List.of(new WideSource(ExactAmount.finite(HUGE)), cell, narrow(64)));
        var exact = HUGE.add(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)).add(BigInteger.valueOf(64));
        assertEquals(exact, normalize.reconcile(reader.readFrom(net), mounts, null).get(KEY).value());
        cell.extract(KEY, 64, Actionable.MODULATE, IActionSource.empty());
        assertEquals(exact.subtract(BigInteger.valueOf(64)),
                normalize.reconcile(reader.readFrom(net), mounts, null).get(KEY).value());
    }
    @Test void alreadyExactVisibleMountIsNotCorrectedTwice() {
        var domain = new WideSource(ExactAmount.finite(HUGE));
        var normalize = new TerminalInventoryReaderImpl(List.of(), List.of(
                new git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoExactStorageAdapterImpl()), reader);
        var baseline = reader.readFrom(network(List.of(domain, narrow(64))));
        assertEquals(baseline, normalize.reconcile(baseline,
                List.of(new StorageMount(domain, domain, domain)), null));
    }
    @Test void adapterBehindNonExactWrapperCorrectsItsOwnContributionAndPreservesInfinity() {
        var normalize = new TerminalInventoryReaderImpl(List.of(), List.of(
                new git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoExactStorageAdapterImpl()), reader);
        var wrapper = narrow(Long.MAX_VALUE);
        var baseline = reader.readFrom(network(List.of(wrapper, narrow(64))));
        var finite = new WideSource(ExactAmount.finite(HUGE));
        assertEquals(HUGE.add(BigInteger.valueOf(64)), normalize.reconcile(baseline,
                List.of(new StorageMount(finite, finite, wrapper)), null).get(KEY).value());
        var infinite = new WideSource(ExactAmount.unbounded());
        assertTrue(normalize.reconcile(baseline, List.of(new StorageMount(infinite, infinite, wrapper)), null).get(KEY).unbounded());
    }
    @Test void trueInfiniteSourceWinsAndFailureAbortsCollector() {
        assertTrue(reader.readFrom(network(List.of(new WideSource(ExactAmount.unbounded()), narrow(32)))).get(KEY).unbounded());
        assertThrows(IllegalStateException.class, () -> reader.readFrom(new MEStorage() {
            @Override public Component getDescription() { return Component.literal("失败库存"); }
            @Override public void getAvailableStacks(KeyCounter out) { throw new IllegalStateException("读取失败"); }
        }));
        assertEquals(BigInteger.valueOf(9), reader.readFrom(network(List.of(narrow(9)))).get(KEY).value());
        assertTrue(ExactAmountCollector.finish().isEmpty());
    }

    @Test void nativeCacheProjectionPreservesInitialStateReplacesUpdatesAndRemovesLastKey() {
        var menu = new net.minecraft.world.inventory.AbstractContainerMenu(null, 23) {
            @Override public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) {
                return net.minecraft.world.item.ItemStack.EMPTY;
            }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
        };
        var projection = new git.chexson.chexsonsaeutils.client.integration.neoecoae.NeoEcoTerminalProjectionImpl();
        var view = new git.chexson.chexsonsaeutils.terminal.sync.TerminalInventoryViewImpl();
        cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.replace(23,
                java.util.Map.of(KEY, ExactAmount.finite(BigInteger.valueOf(32))));
        projection.refresh(menu, view);
        assertEquals(BigInteger.valueOf(32), cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.get(23, KEY).value());
        view.apply(new git.chexson.chexsonsaeutils.terminal.sync.TerminalFrame(1, 0, true, true, true,
                List.of(new git.chexson.chexsonsaeutils.terminal.sync.TerminalEntry(1, KEY,
                        StorageAmount.finite(HUGE), false, null)), List.of()));
        projection.refresh(menu, view);
        assertEquals(HUGE, cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.get(23, KEY).value());
        long revision = cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.revision();
        projection.refresh(menu, view);
        assertEquals(revision, cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.revision());
        // 原生同步覆盖后，下一个渲染前恢复同一帧真值。
        cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.replace(23,
                java.util.Map.of(KEY, ExactAmount.finite(BigInteger.valueOf(Long.MAX_VALUE))));
        projection.refresh(menu, view);
        assertEquals(HUGE, cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.get(23, KEY).value());
        view.apply(new git.chexson.chexsonsaeutils.terminal.sync.TerminalFrame(2, 1, false, true, true, List.of(), List.of(1)));
        projection.refresh(menu, view);
        assertNull(cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.get(23, KEY));
        cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountClientCache.clear(23);
    }
    private static MEStorage narrow(long count) {
        return new MEStorage() {
            @Override public Component getDescription() { return Component.literal("普通挂载"); }
            @Override public void getAvailableStacks(KeyCounter out) { out.add(KEY, count); }
        };
    }
    private static MEStorage network(List<MEStorage> sources) {
        return new MEStorage() {
            @Override public Component getDescription() { return Component.literal("实际采集回调"); }
            @Override public void getAvailableStacks(KeyCounter out) {
                for (var source : sources) {
                    var local = source.getAvailableStacks();
                    ExactAmountCollector.observe(source, local);
                    SaturatingStackAccumulator.addAll(out, local);
                }
            }
        };
    }
    private record WideSource(ExactAmount amount) implements MEStorage, ExactAmountSource {
        @Override public Component getDescription() { return Component.literal("精确域"); }
        @Override public void getAvailableStacks(KeyCounter out) { out.add(KEY, Long.MAX_VALUE); }
        @Override public void neoecoae$visitExactAmounts(BiConsumer<AEKey, ExactAmount> visitor) { visitor.accept(KEY, amount); }
    }
}
