package git.chexson.chexsonsaeutils.bigint;

import java.math.BigInteger;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * 样板份数账本（"这张样板执行几发"）：{@code long[]} 语义的主表 + BigInteger 溢出侧表，
 * 范式与 {@link WideKeyCounter} 一致——常态零分配纯原始读写，只有真超界的样板才进侧表，
 * 数值本体复用 {@link PlanAmount}，本类不实现第二套宽域算术。
 *
 * <p><b>为什么要有它</b>：任务表要能记、能执行超过 {@code long} 的发数。同一张样板在多个
 * 站点累加（旧实现 {@code Object2LongOpenHashMap} 相加越界就抛
 * {@code PlanningOverflowException}，等于整条规划被打死），或环宏发射向量本身就超界时，
 * 份数会越过 {@code Long.MAX_VALUE}。份数的饱和代表值只允许出现在交给 AE2
 * {@code patternTimes()} 的那一份字典上（那是 AE2 的 {@code Map<IPatternDetails, Long>} 字段，
 * 改不动）；守恒闸门、毛账与随计划出门的旁路账必须读这里的精确值。
 *
 * <p>不变式：侧表键 ⊆ 主表在册键（提升时主表写窄视图哨兵），所以 {@link #keys()} 与
 * {@link #longView()} 只遍历主表就覆盖全部键；加到 0 时移除键位，与"缺位"等价——
 * undo 日志因此能只记增量（与 AEKey 账本的 delta 条目同一套口径）。
 *
 * <p>等值语义与改造前的 {@code Object2LongOpenHashMap} 逐字相同（按 {@code hashCode/equals}
 * 定身位），换容器不改变任何"哪一发算在哪张样板上"的判据。
 *
 * <p><b>遍历序</b>：主表 {@link #fast} 是哈希表，它的 {@code keySet()} 序由键的哈希决定，而样板的
 * 哈希一路取到 {@code Item} 的身份哈希（见 {@link #orderedLongView()} 那条），<b>换 JVM 会话就换序</b>。
 * 因此凡要把份数账交给下游定顺位的场合，一律走 {@link #orderedLongView()}（注册序）；主表只承担
 * O(1) 查表与交回 AE2 的那份字典本体。范式与 {@link WideKeyCounter} 的「id + 位图」同形：
 * 登记一次（{@link #slots}）+ 按槽号遍历一次，<b>不排序</b>，热路径的复加一次哈希都不多付。
 *
 * <p><b>注册序管到哪一层</b>（台账 #140/#143 的话术改口）：它管的是"哪张样板先被记进<b>这本账</b>"，
 * 与这本账的哈希无关；再往上的喂入口是求解器的首触序，而首触序来自编译期那份提供者清单——
 * AE2 在"机器优先级并列"那一档交的是 {@code HashSet} 遍历序。graph 路的两个读取点
 * （{@code GraphCompiler.Compilation#craftingFor} 的提供者清单、{@code #resolveNodeKey} 的模糊代用清单）
 * 都由 {@code ProviderPatternOrder} 在同一把稳定键下定死后，注册序才算真的跨会话稳；
 * <b>AE2 树路（DECLINE 回落）的那份 {@code plan.patternTimes()} 仍在别人家的 {@code HashMap} 序上</b>，
 * 走那一条交出的计划不在本段承诺范围内（见 {@code ProviderPatternOrder} 类抬头"管到哪一条路"）。
 */
public final class WideCraftCounter {

    /** {@link #slots} 的"从未登记"读数。 */
    private static final int MISSING_SLOT = -1;

    /** 样板 → 份数窄视图（溢出样板存饱和哨兵）；同时是交回 AE2 的那份字典。 */
    private final Object2LongOpenHashMap<IPatternDetails> fast = new Object2LongOpenHashMap<>();
    @Nullable
    private Object2ObjectOpenHashMap<IPatternDetails, BigInteger> overflow;
    /**
     * 样板 → 注册槽号（首次入账那一刻定死，撤销后重新入账沿用原槽号）。
     *
     * <p>它只为 {@link #orderedLongView()} 提供顺位，<b>不参与查表</b>：查表仍走 {@link #fast}
     * （实机剖析：{@code Object2LongOpenHashMap.getLong} 24% + {@code WideKeyCounter.getLong} 18%
     * 是热路径的账，见 {@code crafting/numeric/KeyIndex} 那份档案）。写侧也只在"键从缺位变在册"
     * 那一支碰它一次（见 {@link #writeNarrow} 用 {@code put} 旧值判缺位），复加路径零额外哈希。
     */
    private final Object2IntOpenHashMap<IPatternDetails> slots = newSlots();
    /** 槽号 → 样板：{@link #slots} 的反表，按槽号遍历即注册序。 */
    private IPatternDetails[] registered = new IPatternDetails[16];
    private int registeredCount;

    private static Object2IntOpenHashMap<IPatternDetails> newSlots() {
        var slots = new Object2IntOpenHashMap<IPatternDetails>();
        slots.defaultReturnValue(MISSING_SLOT);
        return slots;
    }

    // ---- 写 ----

    /** 宽域累加发数：越界自动提升到侧表，绝不抛溢出、绝不饱和。 */
    public void add(IPatternDetails key, long delta) {
        var existing = overflowOf(key);
        if (existing != null) {
            promote(key, existing.add(BigInteger.valueOf(delta)));
            return;
        }
        long current = this.fast.getLong(key);
        long updated = current + delta;
        if (((current ^ updated) & (delta ^ updated)) < 0) {
            promote(key, BigInteger.valueOf(current).add(BigInteger.valueOf(delta)));
            return;
        }
        writeNarrow(key, updated);
    }

    /** 同上，增量本身可超 {@code long}（环宏宽域发射向量走这条）。 */
    public void add(IPatternDetails key, PlanAmount delta) {
        if (delta.isFast()) {
            add(key, delta.fastValue());
            return;
        }
        var existing = overflowOf(key);
        BigInteger base = existing != null ? existing : BigInteger.valueOf(this.fast.getLong(key));
        promote(key, base.add(delta.exactValue()));
    }

    private void writeNarrow(IPatternDetails key, long value) {
        if (value == 0L) {
            // 加到 0 与缺位等价：与 WideKeyCounter 同一条不变式，undo 只记增量才成立。
            // 槽位<b>不回收</b>：撤销后再进来的同一张样板沿用首次的槽号，注册序不重排。
            this.fast.removeLong(key);
            return;
        }
        // 主表从不存 0 值（上面那一支就是唯一写 0 的路），所以 put 的旧值 0 恰好就是"这张样板
        // 第一次在册"的读数——登记点因此不需要在复加路径上多付一次哈希。
        if (this.fast.put(key, value) == 0L) {
            registerSlot(key);
        }
    }

    /** 首次入账的样板占一个递增槽号；已登记过（含撤销后回来）的沿用原槽号。 */
    private void registerSlot(IPatternDetails key) {
        if (this.slots.getInt(key) != MISSING_SLOT) {
            return;
        }
        int slot = this.registeredCount++;
        if (slot == this.registered.length) {
            this.registered = java.util.Arrays.copyOf(this.registered, this.registered.length << 1);
        }
        this.registered[slot] = key;
        this.slots.put(key, slot);
    }

    private void promote(IPatternDetails key, BigInteger value) {
        if (value.bitLength() <= 63) {
            if (this.overflow != null) {
                this.overflow.remove(key);
            }
            writeNarrow(key, value.longValueExact());
            return;
        }
        if (this.overflow == null) {
            this.overflow = new Object2ObjectOpenHashMap<>();
        }
        this.overflow.put(key, value);
        if (this.fast.put(key, value.signum() >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE) == 0L) {
            registerSlot(key);
        }
    }

    // ---- 读 ----

    /** 精确份数（宽域）；缺位为 0。闸门判据必须走这里。 */
    public PlanAmount getAmount(IPatternDetails key) {
        var existing = overflowOf(key);
        return existing != null ? PlanAmount.of(existing) : PlanAmount.of(this.fast.getLong(key));
    }

    @Nullable
    private BigInteger overflowOf(IPatternDetails key) {
        return this.overflow == null ? null : this.overflow.get(key);
    }

    public boolean hasOverflow() {
        return this.overflow != null && !this.overflow.isEmpty();
    }

    /**
     * 账上有没有<b>负发数</b>。发数是"这张样板执行几发"，负号只可能来自上游算批的那一眼
     * 出了符号错误（实机 2026-09-24 13:50 的 {@code times=-13216328854969394}），
     * 绝不是任何一种合法的账目——出口必须据此 DECLINE。
     *
     * <p>只扫主表即可：{@link #promote} 对负宽值写的窄哨兵是 {@link Long#MIN_VALUE}，
     * 宽值落回 long 域时又必然降级回主表，所以负号一定看得见。
     */
    public boolean hasNegative() {
        for (var entry : this.fast.object2LongEntrySet()) {
            if (entry.getLongValue() < 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 第一处负发数的点名串（样板 + 份数）；没有负发数返回 {@code null}。只给判决文本用。
     *
     * <p>点名按<b>注册序</b>（{@link #registered}）而不是主表的哈希序：判决要靠这一串认是哪张样板
     * 出了负号，而样板的哈希一路取到 {@code Item} 的身份哈希——同一份计划两次会话可以点名不同的那一张。
     * 判据本体 {@link #hasNegative()} 与 {@link #negativeCraftDetails()} 的返回值都不受影响
     * （只扫主表即可覆盖全部在册键，不变式见类抬头），这里动的只有"先点哪一张"。
     */
    public @Nullable String describeNegative() {
        for (int slot = 0; slot < this.registeredCount; slot++) {
            IPatternDetails details = this.registered[slot];
            // 撤销后回到缺位的样板读 0（主表从不存 0 值），自然不进这一格的视野
            long value = this.fast.getLong(details);
            if (value < 0L) {
                // 判决文本不能自己再抛一次：null 键撞上 NPE，DECLINE 的原因就没了
                return (details == null ? "<null>" : details.getDefinition()) + " times=" + value;
            }
        }
        return null;
    }

    /**
     * 全部在册样板（含溢出样板：侧表键提升时已在主表置位）的<b>只读</b>视图。
     *
     * <p>交只读的：外部拿 {@code keySet().remove(...)} 能绕过 {@link #writeNarrow} 的登记口把键抽掉，
     * 主表与 {@link #slots}/{@link #registered} 就此失同步（注册序视图少一张样板而 {@link #size()}
     * 还对得上）。台账 #141 第 3 条：这一层不给写口。
     */
    public Set<IPatternDetails> keys() {
        return java.util.Collections.unmodifiableSet(this.fast.keySet());
    }

    /**
     * 在册样板按<b>注册槽号</b>序交出的一份列表：与 {@link #orderedLongView()}、
     * {@link #describeNegative()} 同一把尺（都是 {@link #registered} 那一趟登记序）。
     *
     * <p>为什么要有它：{@link #keys()} 交回的是主表 {@code keySet()} 的哈希遍历序，而样板的哈希
     * 一路取到 {@code Item} 的身份哈希（见类抬头"遍历序"那一段）⇒ 换一次会话就换序。凡是
     * <b>按遍历序累加、再按累加序点名</b>的场合（守恒闸门的供需账、配给表超配那行 WARN、
     * {@code MaterialClosureGate#explainHoles} 的逐槽诊断行），都必须走这一份序，否则同一张表
     * 两次解出来会先点到不同的那一张样板／那一个键——判据本身不变，变的只是点名。
     *
     * <p>已被减到缺位的样板不出现在这份列表里（与 {@link #orderedLongView()} 同一条口径）。
     */
    public java.util.List<IPatternDetails> keysInRegistrationOrder() {
        var ordered = new java.util.ArrayList<IPatternDetails>(this.registeredCount);
        for (int slot = 0; slot < this.registeredCount; slot++) {
            IPatternDetails key = this.registered[slot];
            if (this.fast.containsKey(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    /**
     * 该样板的注册槽号（首次入账那一刻定死，撤销后回来沿用原号）；从未登记返回 -1。
     *
     * <p>只给"并列档需要一个确定尾键"的场合用（守恒闸门的槽排序把并列的模糊槽按这张样板
     * <b>进这本份数账的先后</b>定死），不参与查表、不参与任何判据。
     */
    public int registrationSlot(@Nullable IPatternDetails key) {
        return key == null ? MISSING_SLOT : this.slots.getInt(key);
    }

    /**
     * 饱和 {@code long} 形态字典——只准交给 AE2 {@code patternTimes()} 与按 long 计数的场合。
     *
     * <p>值就是主表本身（改造前的同一只表）：溢出样板在里面上的是饱和哨兵，精确值只能由
     * {@link #getAmount} 取，或从随计划对象出门的份数旁路账读回。交出去的是<b>只读包装</b>
     * （同一份理由见 {@link #keys()}）：AE2 与本仓的读侧都只遍历不写，实测调用点零写依赖。
     *
     * <p>这一层挡住哪些写口（台账 #143 的 MF-4 在当前树上实测，Java 21）：
     * {@code put/remove/clear/putAll/replace/merge/compute*} 由 {@code Collections$UnmodifiableMap}
     * 自己抛；{@code entrySet()} 交回的是 {@code UnmodifiableEntrySet$UnmodifiableEntry}，
     * 它的 {@code setValue} 同样抛，而 {@code replaceAll} 走的就是这一条 Entry 写口——
     * 所以"包装挡不住 Entry.setValue/replaceAll，外部能把主表写成 0 而不动 overflow 与槽位"
     * 这一说不成立（复核给的这条形状在本 JDK 上不成立）。两条臂钉在
     * {@code WideCraftCountTest#narrowViewAndKeySetAreReadOnly()}：将来谁把这里换成直交主表
     * 或换成不被包的副本视图，那两条先红。
     */
    public java.util.Map<IPatternDetails, Long> longView() {
        return java.util.Collections.unmodifiableMap(this.fast);
    }

    /**
     * 与 {@link #longView()} <b>同值</b>而按<b>注册序</b>遍历的一份新字典：交下游定顺位用。
     *
     * <p>为什么必须是它：{@link #fast} 的 {@code keySet()} 序由 {@code IPatternDetails#hashCode}
     * 决定，而 AE2 与其下游的样板实现一律把 hashCode 取成 {@code getDefinition().hashCode()}
     * （{@code AECraftingPattern}/{@code AEProcessingPattern}/{@code AESmithingTablePattern}/
     * {@code AEStonecuttingPattern} 都是 {@code return definition.hashCode();}，本仓
     * {@code CustomProcessingPattern}/{@code MatterMassPatternDetails}/{@code HostBatchPatternImpl}/
     * {@code ReplacementAwarePattern} 同形），再往下是 {@code AEItemKey} 的
     * {@code ItemStack.hashItemAndComponents} —— {@code Item} 不覆写 {@code hashCode}，那是一位
     * <b>身份哈希</b>：同一份计划换一次 JVM 会话就能给出相反的顺位。
     *
     * <p>这里交出的顺位＝该样板<b>第一次进这份份数账</b>的先后（{@link #slots} 的槽号序），
     * 与<b>这本账</b>的哈希无关：一次登记、一次遍历，<b>不排序</b>。已在 {@link #fast} 里被减到缺位的样板
     * 不出现在这份视图里，读数与 {@link #longView()} 逐键相同（含溢出样板的饱和哨兵）。
     *
     * <p>"与哈希无关"管到哪一层（台账 #140/#143 改口）：只到<b>转换器以下</b>，且只对<b>graph 路</b>成立。
     * 槽号序＝求解器首触序，而首触序吃的是编译期那两份清单的次序——AE2
     * {@code NetworkCraftingProviders:237} 的 {@code HashSet}（提供者清单并列那一档）与
     * {@code :138-139} 的 {@code KeyCounter}（模糊代用清单）交的都是哈希遍历序。这两档已由
     * {@code ProviderPatternOrder} 在我们侧的两个读取点上按同一把稳定键定死；
     * 走 AE2 树路（DECLINE 回落）交出的那份 {@code plan.patternTimes()} 不在这条承诺里。
     *
     * <p>冷路径：每份计划出口物化一次，O(登记过的样板数) 次哈希查询；常态的复加与查表
     * 一个都不碰它（见 {@link #slots} 那条）。
     */
    public java.util.Map<IPatternDetails, Long> orderedLongView() {
        var ordered = new java.util.LinkedHashMap<IPatternDetails, Long>(
                Math.max(16, this.registeredCount * 2));
        for (int slot = 0; slot < this.registeredCount; slot++) {
            IPatternDetails key = this.registered[slot];
            if (this.fast.containsKey(key)) {
                ordered.put(key, this.fast.getLong(key));
            }
        }
        return ordered;
    }

    /** 与 {@link #longView()} 同值的键数。 */
    public int size() {
        return this.fast.size();
    }

    /** 全表份数总和（宽域精确，绝不回绕）。 */
    public PlanAmount total() {
        PlanAmount sum = PlanAmount.ZERO;
        for (IPatternDetails key : keys()) {
            sum = sum.add(getAmount(key));
        }
        return sum;
    }
}
