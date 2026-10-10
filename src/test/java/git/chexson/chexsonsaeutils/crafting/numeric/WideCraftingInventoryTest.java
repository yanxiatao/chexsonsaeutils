package git.chexson.chexsonsaeutils.crafting.numeric;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;

/**
 * 产出缓冲的溢出侧表用例：库存本身能记 &gt;long，而 AE2 看见的读数一个字节都不变。
 *
 * <p>四条底线各钉一组：
 * <ul>
 *   <li>同一键累计越界时 {@code amount()} 给精确值，主表停在 {@code Long.MAX_VALUE}；</li>
 *   <li>没越界的键<b>不挂侧表、不写覆盖键</b>（常态零额外开销）；</li>
 *   <li>NBT 往返逐位相等，缺覆盖键的旧数据按主表 {@code long} 原样读回；</li>
 *   <li>以基类身份调 {@code insert/extract/clear} 的结果与 {@link ListCraftingInventory}
 *       <b>逐字相同</b>——这条是本刀最大的风险点，用平行跑脚本逐步比对钉死。</li>
 * </ul>
 *
 * <p>量级用 {@code 3 × 9.22e18}（{@code Long.MAX_VALUE}）那一档：正是会被 {@code KeyCounter}
 * 裸加绕回的一截。夹具沿用 {@code WideKeyCounterTest} 的口径，不起第二套。
 */
final class WideCraftingInventoryTest {

    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GEAR = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger TRIPLE_LONG_MAX = LONG_MAX.multiply(BigInteger.valueOf(3L));

    /** 同一键累计越出 {@code long}：宽域读口给精确值，主表停在 {@code Long.MAX_VALUE}。 */
    @Test
    void accumulatedBeyondLongReadsExactlyWhileMainTableStopsAtLongMax() {
        var inventory = newInventory(ignored -> {
        });

        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, 1L, Actionable.MODULATE);
        inventory.insert(PRODUCT, LONG_MAX.subtract(BigInteger.ONE).longValueExact(), Actionable.MODULATE);

        assertEquals(TRIPLE_LONG_MAX, inventory.amount(PRODUCT), "累计 3×Long.MAX_VALUE，逐位精确");
        assertEquals(Long.MAX_VALUE, inventory.list.get(PRODUCT),
                "AE2 看见的主表停在 long 天花板：既不绕回，也报不出比真值大的数");
        assertTrue(inventory.hasOverflow(), "这笔确实越界了，侧表该有条目");
    }

    /** 越界后又被抽回 {@code long} 域：侧表条目撤下，主表读数重新等于精确值。 */
    @Test
    void fallingBackIntoLongDomainRetiresTheSideEntry() {
        var inventory = newInventory(ignored -> {
        });
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        assertEquals(TRIPLE_LONG_MAX.subtract(LONG_MAX), inventory.amount(PRODUCT));

        assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(LONG_MAX, inventory.amount(PRODUCT));
        assertEquals(Long.MAX_VALUE, inventory.list.get(PRODUCT));
        assertFalse(inventory.hasOverflow(), "回到 long 域就该撤掉侧表条目");

        assertEquals(1L, inventory.extract(PRODUCT, 1L, Actionable.MODULATE));
        assertEquals(LONG_MAX.subtract(BigInteger.ONE), inventory.amount(PRODUCT));
        assertEquals(Long.MAX_VALUE - 1L, inventory.list.get(PRODUCT));
    }

    /** 抽干一个越界键：侧表与主表一起清空，不留 0 值条目（与基类抽干即 remove 同一条口径）。 */
    @Test
    void drainingAnOverflowKeyEvictsItWholesale() {
        var inventory = newInventory(ignored -> {
        });
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, 5L, Actionable.MODULATE);
        assertEquals(LONG_MAX.add(BigInteger.valueOf(5L)), inventory.amount(PRODUCT));

        assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(BigInteger.valueOf(5L), inventory.amount(PRODUCT));
        assertFalse(inventory.hasOverflow(), "差额抽完就回到 long 域");

        assertEquals(5L, inventory.extract(PRODUCT, 5L, Actionable.MODULATE));
        assertEquals(BigInteger.ZERO, inventory.amount(PRODUCT));
        assertTrue(inventory.list.isEmpty(), "抽干即整条撤掉，不留 0 值条目");
        assertFalse(inventory.hasOverflow());
    }

    /** 没越界的键：不挂侧表、不写覆盖键，写出的 NBT 与基类一字不差。 */
    @Test
    void inRangeKeysGetNoSideTableAndNoWideOverrideKey() {
        var wide = newInventory(ignored -> {
        });
        var plain = new ListCraftingInventory(ignored -> {
        });

        for (int batch = 0; batch < 4; batch++) {
            wide.insert(PRODUCT, 1_000_000_000_000L, Actionable.MODULATE);
            plain.insert(PRODUCT, 1_000_000_000_000L, Actionable.MODULATE);
            wide.insert(GEAR, 7L, Actionable.MODULATE);
            plain.insert(GEAR, 7L, Actionable.MODULATE);
        }

        assertFalse(wide.hasOverflow(), "全程没越界 ⇒ 侧表一次都没被建起来");
        assertEquals(BigInteger.valueOf(4_000_000_000_000L), wide.amount(PRODUCT));
        assertEquals(snapshotOf(plain), snapshotOf(wide), "主表内容与基类实现逐字相同");

        var wideTag = wide.writeToNBT(REGISTRIES);
        assertEquals(plain.writeToNBT(REGISTRIES), wideTag, "常态写出的 NBT 与基类一字不差");
        assertEquals(0, countWideEntries(wideTag), "不越界就一个覆盖键都不挂");
    }

    /** 越界条目进出 NBT 逐位相等；只有真越界那条挂覆盖键，AE2 可见的老 {@code #} 位仍是饱和读数。 */
    @Test
    void wideEntriesRoundTripThroughNbtBitForBit() {
        var inventory = newInventory(ignored -> {
        });
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, 4_000_000_000_000_000_000L, Actionable.MODULATE);
        inventory.insert(GEAR, 42L, Actionable.MODULATE);

        var exactProduct = LONG_MAX.multiply(BigInteger.valueOf(2L))
                .add(BigInteger.valueOf(4_000_000_000_000_000_000L));
        assertEquals(exactProduct, inventory.amount(PRODUCT));

        ListTag tag = inventory.writeToNBT(REGISTRIES);
        assertEquals(2, tag.size());
        assertEquals(1, countWideEntries(tag), "只有真越界的那一条多挂覆盖键");

        var restored = newInventory(ignored -> {
        });
        restored.readFromNBT(tag, REGISTRIES);
        assertEquals(exactProduct, restored.amount(PRODUCT), "含低位特征数字，逐位还原");
        assertEquals(BigInteger.valueOf(42L), restored.amount(GEAR));
        assertEquals(Long.MAX_VALUE, restored.list.get(PRODUCT),
                "读回后对 AE2 报的仍是饱和读数，不是真值");
        assertEquals(snapshotOf(inventory), snapshotOf(restored));
    }

    /** 没有覆盖键的旧数据：按主表 {@code long} 读数原样吃回来，不产生侧表条目。 */
    @Test
    void legacyEntriesWithoutOverrideKeyReadBackAsMainTableLongs() {
        ListTag legacy = new ListTag();
        legacy.add(entry(PRODUCT, Long.MAX_VALUE));
        legacy.add(entry(GEAR, 12345L));

        var inventory = newInventory(ignored -> {
        });
        inventory.insert(GEAR, 999L, Actionable.MODULATE);
        inventory.readFromNBT(legacy, REGISTRIES);

        assertFalse(inventory.hasOverflow(), "缺覆盖键就是\"这笔装得进 long\"，不是迁移壳");
        assertEquals(LONG_MAX, inventory.amount(PRODUCT));
        assertEquals(BigInteger.valueOf(12345L), inventory.amount(GEAR), "读回是覆盖不是累加：与基类同一条规则");
        assertEquals(2, snapshotOf(inventory).size());
    }

    /**
     * 回归护栏：一整套 {@code insert/extract/clear} 脚本在 {@link WideCraftingInventory} 与基类
     * {@link ListCraftingInventory} 上平行跑，每步比对<b>返回值、主表全量快照、变更回调次数</b>。
     *
     * <p>脚本刻意覆盖容易走偏的形状：全额抽干（基类整条 remove 键）、0 值入账（基类会留下 0 值
     * 条目）、抽到一半、SIMULATE 不许发回调、恰好塞满 {@code long} 但一次都没越界。
     */
    @Test
    void baseIdentityCallsMatchPlainListCraftingInventoryStepByStep() {
        var wideLog = new ArrayList<String>();
        var wide = newInventory(key -> wideLog.add(itemName(key)));
        var plainLog = new ArrayList<String>();
        var plain = new ListCraftingInventory(key -> plainLog.add(itemName(key)));
        // 两边都以基类身份被调用：宽域实现拿不到任何子类特权的捷径
        ListCraftingInventory wideAsBase = wide;

        apply(wideAsBase, plain, wideLog, plainLog, inv -> inv.insert(PRODUCT, 100L, Actionable.MODULATE));
        apply(wideAsBase, plain, wideLog, plainLog, inv -> inv.insert(BUCKET, 1L, Actionable.MODULATE));
        applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                inv -> inv.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE));
        applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                inv -> inv.extract(PRODUCT, 30L, Actionable.MODULATE));
        applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                inv -> inv.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
        apply(wideAsBase, plain, wideLog, plainLog, inv -> inv.insert(PRODUCT, 0L, Actionable.MODULATE));
        apply(wideAsBase, plain, wideLog, plainLog,
                inv -> inv.insert(GEAR, 4_000_000_000_000_000_000L, Actionable.MODULATE));
        apply(wideAsBase, plain, wideLog, plainLog,
                inv -> inv.insert(GEAR, 4_000_000_000_000_000_000L, Actionable.MODULATE));
        applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                inv -> inv.extract(GEAR, Long.MAX_VALUE, Actionable.SIMULATE));
        applyAndCompare(wideAsBase, plain, wideLog, plainLog,
                inv -> inv.extract(BUCKET, 1L, Actionable.MODULATE));
        apply(wideAsBase, plain, wideLog, plainLog, ListCraftingInventory::clear);

        assertFalse(wide.hasOverflow(), "整段脚本一次都没越界 ⇒ 全程该走基类原路径");
        assertEquals(plainLog, wideLog, "变更回调的序列与键集逐字相同");
    }

    /** 越界键对基类身份的读数：钉在 {@code Long.MAX_VALUE}，不绕回也不报得比真值大。 */
    @Test
    void overflowKeyStillReportsLongMaxToBaseIdentityCallers() {
        var inventory = newInventory(ignored -> {
        });
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);

        assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.SIMULATE),
                "getWaitingFor 那条读口的口径：饱和读数，绝不绕回成负数");
        assertEquals(TRIPLE_LONG_MAX.subtract(LONG_MAX), inventory.amount(PRODUCT),
                "真值只在宽域读口上可见");

        // 一批回池最多要 Long.MAX 枚：拿得到就全拿，剩的那一截留给下一批
        assertEquals(Long.MAX_VALUE, inventory.extract(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(LONG_MAX, inventory.amount(PRODUCT));
        assertFalse(inventory.hasOverflow(), "拿完这一批恰好回到 long 域");
    }

    /** 静态读口：只握基类引用的调用方也拿得到精确值，非宽域容器则按主表 long 读数。 */
    @Test
    void staticAccessorReadsExactThroughBaseTypedReference() {
        var inventory = newInventory(ignored -> {
        });
        inventory.insert(PRODUCT, Long.MAX_VALUE, Actionable.MODULATE);
        inventory.insert(PRODUCT, 1L, Actionable.MODULATE);

        ListCraftingInventory asBase = inventory;
        assertEquals(LONG_MAX.add(BigInteger.ONE), WideCraftingInventory.amount(asBase, PRODUCT));

        ListCraftingInventory plain = new ListCraftingInventory(ignored -> {
        });
        plain.insert(GEAR, 5L, Actionable.MODULATE);
        assertEquals(BigInteger.valueOf(5L), WideCraftingInventory.amount(plain, GEAR));
        assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(plain, null));
        assertEquals(BigInteger.ZERO, WideCraftingInventory.amount(null, GEAR));
    }

    // ---- 夹具 ----

    @FunctionalInterface
    private interface VoidOp {
        void run(ListCraftingInventory inventory);
    }

    @FunctionalInterface
    private interface ReadOp {
        long run(ListCraftingInventory inventory);
    }

    /** 两边施加同一条无返回值操作，随即比对可观测面：主表全量快照 + 这一动作出的回调次数。 */
    private static void apply(ListCraftingInventory wide, ListCraftingInventory plain,
            List<String> wideLog, List<String> plainLog, VoidOp op) {
        int wideBefore = wideLog.size();
        int plainBefore = plainLog.size();
        op.run(wide);
        op.run(plain);
        assertEquals(snapshotOf(plain), snapshotOf(wide), "主表快照必须逐键逐值相同");
        assertEquals(plainLog.size() - plainBefore, wideLog.size() - wideBefore,
                "同一条操作发出的变更回调次数必须相同");
    }

    /** 两边各施加一次同一条带返回值操作：返回值、主表快照、回调次数一起比。 */
    private static void applyAndCompare(ListCraftingInventory wide, ListCraftingInventory plain,
            List<String> wideLog, List<String> plainLog, ReadOp op) {
        int wideBefore = wideLog.size();
        int plainBefore = plainLog.size();
        long onWide = op.run(wide);
        long onPlain = op.run(plain);
        assertEquals(onPlain, onWide, "以基类身份调 extract 的返回值必须逐字相同");
        assertEquals(snapshotOf(plain), snapshotOf(wide), "主表快照必须逐键逐值相同");
        assertEquals(plainLog.size() - plainBefore, wideLog.size() - wideBefore,
                "同一条操作发出的变更回调次数必须相同");
    }

    /** 主表全量快照：连键的存在性与 0 值条目一起比，比只比总量严得多。 */
    private static Map<String, Long> snapshotOf(ListCraftingInventory inventory) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (var entry : inventory.list) {
            snapshot.put(itemName(entry.getKey()), entry.getLongValue());
        }
        return snapshot;
    }

    private static String itemName(AEKey key) {
        return key instanceof AEItemKey itemKey
                ? itemKey.getItem().getDescriptionId()
                : String.valueOf(key);
    }

    private static CompoundTag entry(AEKey key, long amount) {
        CompoundTag tag = key.toTagGeneric(REGISTRIES);
        tag.putLong("#", amount);
        return tag;
    }

    private static int countWideEntries(ListTag tag) {
        int count = 0;
        for (int index = 0; index < tag.size(); index++) {
            if (tag.getCompound(index).contains(
                    WideCraftingInventory.NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)) {
                count++;
            }
        }
        return count;
    }

    private static WideCraftingInventory newInventory(ListCraftingInventory.ChangeListener listener) {
        return new WideCraftingInventory(listener);
    }
}
