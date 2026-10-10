package git.chexson.chexsonsaeutils.bigint;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Map;

/**
 * {@link ListCraftingInventory} 之上的<b>溢出侧表</b>：让 AE2 那个只装得下 {@code long} 的
 * 产出缓冲能记住越出 {@code long} 的累计量。
 *
 * <p>范式照 {@code cell/InfinityCellStore} 与 neoecoae 的 {@code ECOExactInventory}（同一件已验证的
 * 事，两份实现互为对照）：
 * <ul>
 *   <li>主表仍是基类那个 {@code public final KeyCounter list}，溢出键在里头停在
 *       {@link Long#MAX_VALUE}；</li>
 *   <li>越出的一截挂侧表 {@code Map<AEKey,BigInteger>}，侧表只存差值（恒正）；</li>
 *   <li>精确读数 = 主表（按 0 夹底）+ 侧表那一截，见 {@link #amount(AEKey)}。</li>
 * </ul>
 *
 * <h2>对 AE2 与外部 mod 可见的读数：报小不报大，且永不回绕</h2>
 * 主表是 AE2 唯一看得见的那一层，所以溢出键对它恒等于 {@link Long#MAX_VALUE}：
 * 与无限盘 {@code InfinityCellStore} 的 {@code visibleAmount} 同一条口径。
 * 老实现下同一个序列会把 {@code KeyCounter} 加到绕回（{@code 3×Long.MAX → Long.MAX-2}），
 * 读数既可能比真值大到无法解释、也可能小到"看着没料"；现在它被钉在 {@code long} 天花板下，
 * 判"够不够这一批"只会低估、不会假通过。真值只在 {@link #amount(AEKey)} /
 * {@link #amount(ListCraftingInventory, AEKey)} 这两个自有宽域读口上可见。
 *
 * <h2>常态零额外开销</h2>
 * 没越界的键<b>一次都不碰侧表</b>：侧表本身懒建（{@code null} 即从未溢出），
 * 塞得进 {@code long} 的累加与抽取逐字走 {@code super}，写值、0 值条目、变更回调全部
 * 与基类同一条代码路径。NBT 亦然——只有真越界的条目多挂一个覆盖键。
 *
 * <p>本类不实现第二套宽域算术：跨 {@code long} 的判定与夹取只用 {@link BigInteger} 比较，
 * 交回 AE2 的窄化仍归 {@link Ae2Amounts}。
 */
public class WideCraftingInventory extends ListCraftingInventory {


    /** 条目上的老 {@code long} 数量键：与 AE2 {@code ListCraftingInventory} 同一个字段名。 */
    private static final String NBT_ENTRY_AMOUNT = "#";

    /**
     * 条目上的<b>宽域覆盖键</b>：只在该键累计越出 {@code long} 时挂上，存<b>精确总值</b>
     * （BigInteger 补码字节，与 {@code #craftingProgressWide}、{@code #deliveredWide} 同一写法）。
     *
     * <p>读不到该键就是"这笔装得进 {@code long}"，按主表 {@value #NBT_ENTRY_AMOUNT} 原样读回——
     * 纯覆盖层，不是存档迁移壳。
     */
    public static final String NBT_ENTRY_AMOUNT_WIDE = "#amountWide";

    /**
     * 溢出侧表：键 → <b>已越出 {@code long} 的那一截</b>（恒 {@code > 0}）。
     * {@code null} 即"这个容器从未溢出过"，常态路径一次分配都不做。
     */
    @Nullable
    private Map<AEKey, BigInteger> excess;

    /** 基类把回调收进私有字段，宽域写口只能自己再握一份：同一条回调，一次变更只发一次。 */
    private final ChangeListener wideChangeListener;

    public WideCraftingInventory(ChangeListener listener) {
        super(listener);
        this.wideChangeListener = listener;
    }

    // ---- 宽域读口 ----

    /** 该键的<b>精确</b>读数：主表 {@code long}（按 0 夹底）加溢出侧表那一截。 */
    public BigInteger amount(@Nullable AEKey what) {
        if (what == null) {
            return BigInteger.ZERO;
        }
        BigInteger exact = BigInteger.valueOf(Math.max(0L, list.get(what)));
        BigInteger overflowed = this.excess == null ? null : this.excess.get(what);
        return overflowed == null ? exact : exact.add(overflowed);
    }

    /**
     * 只握基类引用的调用方读口：是 {@link WideCraftingInventory} 就给精确值，
     * 否则按它主表的 {@code long} 读数（同样是 AE2 各处看到的口径）。
     */
    public static BigInteger amount(@Nullable ListCraftingInventory inventory, @Nullable AEKey what) {
        if (inventory instanceof WideCraftingInventory wide) {
            return wide.amount(what);
        }
        if (inventory == null || what == null) {
            return BigInteger.ZERO;
        }
        return BigInteger.valueOf(Math.max(0L, inventory.list.get(what)));
    }

    /** 侧表里有没有条目（诊断与"NBT 该不该挂覆盖键"的判据）。 */
    public boolean hasOverflow() {
        return this.excess != null && !this.excess.isEmpty();
    }

    // ---- AE2 基类身份写口：常态逐字退回基类 ----

    @Override
    public void insert(AEKey what, long amount, Actionable mode) {
        if (mode != Actionable.MODULATE || !willOverflow(what, amount)) {
            // 常态（SIMULATE；或该键不在侧表且这笔累加不越界）：整条走基类原路径 ⇒
            // 写值、0 值条目、变更回调与换实现前逐字相同
            super.insert(what, amount, mode);
            return;
        }
        insertWide(what, BigInteger.valueOf(amount));
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode) {
        if (this.excess == null || !this.excess.containsKey(what)) {
            // 该键没越界：基类原路径，SIMULATE/MODULATE 的读数与回调逐字不变
            return super.extract(what, amount, mode);
        }
        return extractWide(what, BigInteger.valueOf(amount), mode).longValueExact();
    }

    /**
     * 宽域入账：在<b>精确值</b>上累加，主表按 {@code long} 天花板夹住、越出的一截进侧表。
     *
     * <p>它同时是<b>回补（restore）</b>入口：交割失败要把已扣的那几枚原样还回来，就是再
     * {@code insertExact} 一次——侧表是累加语义，不需要"扣了再记欠"的第二本账。
     */
    public void insertExact(AEKey what, @Nullable BigInteger amount, Actionable mode) {
        if (amount == null) {
            return;
        }
        if (amount.bitLength() <= 63) {
            // 塞得进 long ⇒ 逐字退回 long 那条路（含 0 值入账这类边角行为）
            insert(what, amount.longValueExact(), mode);
            return;
        }
        if (mode != Actionable.MODULATE) {
            return;
        }
        insertWide(what, amount);
    }

    /**
     * 宽域出账：先在<b>精确值</b>上夹 {@code min(精确值, 请求量)}，只有 MODULATE 才写回。
     *
     * <p>与基类同一条"先算出实扣、再一次性写回"的形状，不是"先扣主表、发现不够再补侧表"——
     * 那种写法在中途抛异常时会留下扣了一半的账。
     */
    public BigInteger extractExact(AEKey what, @Nullable BigInteger amount, Actionable mode) {
        if (what == null || amount == null) {
            return BigInteger.ZERO;
        }
        return extractWide(what, amount, mode);
    }

    // ---- 清空与模糊模板 ----

    /**
     * 连侧表一起清。
     *
     * <p>先撤侧表再走基类的"逐键清零并回调"：与基类 {@code clear()} 注释里同一条理由——
     * 回调里查读数的调用方必须看到 0，宽域读口也不例外。
     */
    @Override
    public void clear() {
        if (this.excess != null) {
            this.excess.clear();
        }
        super.clear();
    }

    /**
     * 模糊模板查询不覆写：它只认"哪些键在容器里"，而溢出键在主表里恒在（值为
     * {@link Long#MAX_VALUE}），键集与非溢出时完全一致。
     */

    // ---- NBT：纯覆盖层 ----

    @Override
    public ListTag writeToNBT(HolderLookup.Provider registries) {
        // 主表逐条照基类写：溢出键的老 `#` 位就是 Long.MAX_VALUE，与网络读出口径一致
        ListTag tag = super.writeToNBT(registries);
        if (!hasOverflow()) {
            return tag;
        }
        for (int index = 0; index < tag.size(); index++) {
            if (!(tag.get(index) instanceof CompoundTag entry)) {
                continue;
            }
            AEKey key = AEKey.fromTagGeneric(registries, entry);
            BigInteger exact = amount(key);
            if (exact.compareTo(WideCeilings.LONG_ACCOUNT) > 0) {
                entry.putByteArray(NBT_ENTRY_AMOUNT_WIDE, exact.toByteArray());
            }
        }
        return tag;
    }

    /**
     * 读回：条目带覆盖键就按补码还原精确值，否则按老 {@code long} 值走。
     *
     * <p>必须整个覆写而不是复用基类实现——基类只认 {@value #NBT_ENTRY_AMOUNT} 那一个键，
     * 覆盖键会被它静丢掉，续传后越界那一截就蒸发了。
     */
    @Override
    public void readFromNBT(@Nullable ListTag data, HolderLookup.Provider registries) {
        if (this.excess != null) {
            this.excess.clear();
        }
        list.clear();
        if (data == null) {
            return;
        }
        for (int index = 0; index < data.size(); index++) {
            CompoundTag compound = data.getCompound(index);
            AEKey key = AEKey.fromTagGeneric(registries, compound);
            if (key == null) {
                continue;
            }
            BigInteger exact = compound.contains(NBT_ENTRY_AMOUNT_WIDE, Tag.TAG_BYTE_ARRAY)
                    ? new BigInteger(compound.getByteArray(NBT_ENTRY_AMOUNT_WIDE))
                    : BigInteger.valueOf(compound.getLong(NBT_ENTRY_AMOUNT));
            insertExact(key, exact, Actionable.MODULATE);
        }
    }

    // ---- 内部 ----

    /** 这笔 {@code long} 累加是否需要走宽域：该键已在侧表，或主表累加会越出 {@code long}。 */
    private boolean willOverflow(AEKey what, long amount) {
        if (this.excess != null && this.excess.containsKey(what)) {
            return true;
        }
        long current = list.get(what);
        long updated = current + amount;
        // 同号相加得反号即绕回：与 WideKeyCounter.addById 同一条判据，比 Math.addExact 省一次异常
        return ((current ^ updated) & (amount ^ updated)) < 0L;
    }

    private void insertWide(AEKey what, BigInteger delta) {
        setExact(what, amount(what).add(delta));
        wideChangeListener.onChange(what);
    }

    private BigInteger extractWide(AEKey what, BigInteger request, Actionable mode) {
        BigInteger available = amount(what);
        BigInteger extracted = available.min(request);
        if (mode != Actionable.MODULATE) {
            return extracted;
        }
        setExact(what, available.subtract(extracted));
        wideChangeListener.onChange(what);
        return extracted;
    }

    /** 写回精确值：主表按 {@code long} 天花板夹住，侧表按需挂上/撤下（与 {@code InfinityCellStore} 同一条不变式）。 */
    private void setExact(AEKey what, BigInteger exact) {
        if (exact.compareTo(WideCeilings.LONG_ACCOUNT) > 0) {
            list.set(what, Long.MAX_VALUE);
            if (this.excess == null) {
                this.excess = new java.util.HashMap<>();
            }
            this.excess.put(what, exact.subtract(WideCeilings.LONG_ACCOUNT));
            return;
        }
        if (this.excess != null) {
            this.excess.remove(what);
        }
        if (exact.signum() == 0) {
            // 抽干即整条撤掉，不留 0 值条目：与基类 extract 把键 remove 掉同一条口径
            list.remove(what);
        } else {
            list.set(what, exact.longValueExact());
        }
    }
}
