package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 递归计划：在普通 AE2 计划外携带递归执行所需的额外契约字段。
 *
 * <p>计划契约（{@link RecursivePlanContract} 与
 * {@link SelectedInputsPlan}）被执行侧消费（CPU 预取催化物、
 * formal 聚合折叠）。历史方法名保持兼容，颜色不参与字段语义。
 *
 * <p>{@link #wideLedgers} 是<b>显示旁路</b>：出账那一刻被 {@code Ae2Amounts.toLong} 饱和掉的
 * 超 long 真值只在这份快照里还完整，由确认菜单经 {@link WideAmountPlanCarrier} 读走。
 * 它不参与任何规划/提交判定，也<b>不进 NBT</b>（本类的计划读写方法一律不碰它）。
 *
 * <p>{@link #planMaterialPlan} 是<b>执行契约的一部分</b>，不是显示旁路：它是逐张样板的配给上界，
 * 执行侧照这份数取料。份数＝每发量 × 宽域发数，同一键还要跨槽跨候选复加 ⇒ 合计可以越出
 * {@code long}，所以账本用 {@link WideKeyCounter}（主表 {@code long} + 溢出侧表
 * {@code BigInteger}）。这份表随计划对象在内存里出门，<b>不进 NBT、也不过 AE2 的 packet</b>
 * （{@code ICraftingPlan} 从不落盘），因此宽域载体不撞任何序列化边界。
 *
 * <p>{@link #planMaterialEnvelopes} 是同一趟闸门账里<b>封套那一截</b>（模糊槽首位候选的许可上界，
 * 按样板 → 键 → 量，形状与配给表一致）：它<b>已经在</b> {@link #planMaterialPlan} 里（配额夹照旧读
 * 整本），另记一本只为了让"这一族还缺多少料"的读数（执行侧 {@code hole=}）在减法上先剔掉它。
 * 与配给表同一侧：只活在内存里、<b>不进 NBT</b>，读回时走同一条 {@code instanceof} 契约穿壳路。
 *
 * <p>{@link #unboundedKeys} 是规划侧认下的<b>无限源</b>键集：执行侧对它们豁免配额与占料封顶，
 * 网络给不出的那一截在<b>取料点就地认量、不经池子</b>（无限键在 {@code long} 域里没有上界，
 * 注进池子的那一截唯一的去处是残池回网 = 凭空多一份料，那条退路已删）。与配给表同一侧，
 * 只活在内存里、不进 NBT；续传作业读不到就是空集（= 老口径）。
 */
public record RecursiveCraftingPlan(
        CraftingPlan delegate,
        KeyCounter recursiveInitialItems,
        KeyCounter recursiveInternalItems,
        long recursiveFinalOutputAmount,
        Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
        Map<IPatternDetails, WideKeyCounter> planMaterialPlan,
        WideLedgerSnapshot wideLedgers,
        WideCraftSnapshot wideCrafts,
        Set<AEKey> unboundedKeys,
        Map<IPatternDetails, WideKeyCounter> planMaterialEnvelopes,
        git.chexson.chexsonsaeutils.bigint.PlanAmount exactFinalOutput,
        git.chexson.chexsonsaeutils.bigint.PlanAmount exactBytes)
        implements ICraftingPlan, RecursivePlanContract, SelectedInputsPlan, WideAmountPlanCarrier {

    public RecursiveCraftingPlan(CraftingPlan delegate, KeyCounter recursiveInitialItems,
            KeyCounter recursiveInternalItems, long recursiveFinalOutputAmount,
            Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
            Map<IPatternDetails, WideKeyCounter> planMaterialPlan, WideLedgerSnapshot wideLedgers,
            WideCraftSnapshot wideCrafts, Set<AEKey> unboundedKeys,
            Map<IPatternDetails, WideKeyCounter> planMaterialEnvelopes) {
        this(delegate, recursiveInitialItems, recursiveInternalItems, recursiveFinalOutputAmount,
                selectedPatternInputs, planMaterialPlan, wideLedgers, wideCrafts, unboundedKeys,
                planMaterialEnvelopes, null);
    }

    public RecursiveCraftingPlan(CraftingPlan delegate, KeyCounter recursiveInitialItems,
            KeyCounter recursiveInternalItems, long recursiveFinalOutputAmount,
            Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
            Map<IPatternDetails, WideKeyCounter> planMaterialPlan, WideLedgerSnapshot wideLedgers,
            WideCraftSnapshot wideCrafts, Set<AEKey> unboundedKeys,
            Map<IPatternDetails, WideKeyCounter> planMaterialEnvelopes,
            git.chexson.chexsonsaeutils.bigint.PlanAmount exactFinalOutput) {
        this(delegate, recursiveInitialItems, recursiveInternalItems, recursiveFinalOutputAmount,
                selectedPatternInputs, planMaterialPlan, wideLedgers, wideCrafts, unboundedKeys,
                planMaterialEnvelopes, exactFinalOutput, null);
    }

    @Override public git.chexson.chexsonsaeutils.bigint.PlanAmount chexsonsaeutils$exactBytes() {
        return exactBytes;
    }

    @Override public git.chexson.chexsonsaeutils.bigint.PlanAmount chexsonsaeutils$exactFinalOutput() {
        return exactFinalOutput;
    }

    /** 宽域账缺省给空快照：载体取值端（{@link WideAmountPlanCarrier#resolveWideLedgers}）因此永不触空。 */
    public RecursiveCraftingPlan {
        wideLedgers = wideLedgers == null ? WideLedgerSnapshot.empty() : wideLedgers;
        wideCrafts = wideCrafts == null ? WideCraftSnapshot.empty() : wideCrafts;
        unboundedKeys = unboundedKeys == null ? Set.of() : Set.copyOf(unboundedKeys);
        planMaterialEnvelopes = planMaterialEnvelopes == null
                ? Map.of() : planMaterialEnvelopes;
    }

    /** 规划出口还没带无限键集时的老形状（等价于空集：执行侧按今日口径夹）。 */
    public RecursiveCraftingPlan(
            CraftingPlan delegate,
            KeyCounter recursiveInitialItems,
            KeyCounter recursiveInternalItems,
            long recursiveFinalOutputAmount,
            Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
            Map<IPatternDetails, WideKeyCounter> planMaterialPlan,
            WideLedgerSnapshot wideLedgers,
            WideCraftSnapshot wideCrafts) {
        this(delegate, recursiveInitialItems, recursiveInternalItems, recursiveFinalOutputAmount,
                selectedPatternInputs, planMaterialPlan, wideLedgers, wideCrafts, Set.of());
    }

    /** 规划出口还没带封套那一本时的老形状（等价于空表：执行侧的 hole 分子不剔任何东西）。 */
    public RecursiveCraftingPlan(
            CraftingPlan delegate,
            KeyCounter recursiveInitialItems,
            KeyCounter recursiveInternalItems,
            long recursiveFinalOutputAmount,
            Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
            Map<IPatternDetails, WideKeyCounter> planMaterialPlan,
            WideLedgerSnapshot wideLedgers,
            WideCraftSnapshot wideCrafts,
            Set<AEKey> unboundedKeys) {
        this(delegate, recursiveInitialItems, recursiveInternalItems, recursiveFinalOutputAmount,
                selectedPatternInputs, planMaterialPlan, wideLedgers, wideCrafts, unboundedKeys,
                Map.of());
    }

    @Override
    public WideLedgerSnapshot chexsonsaeutils$wideLedgers() {
        return wideLedgers;
    }

    /** 宽域份数字典：同 {@link #wideLedgers} 一样是旁路账，不进 NBT、不参与任何执行契约判据。 */
    @Override
    public WideCraftSnapshot chexsonsaeutils$wideCrafts() {
        return wideCrafts;
    }

    /** 无限源键集：执行侧豁免配额与占料封顶、缺口在取料点就地认量（不经池子），不进 NBT。 */
    @Override
    public Set<AEKey> chexsonsaeutils$unboundedKeys() {
        return unboundedKeys;
    }

    @Override
    public GenericStack finalOutput() {
        return delegate.finalOutput();
    }

    @Override
    public long bytes() {
        return delegate.bytes();
    }

    @Override
    public boolean simulation() {
        return delegate.simulation();
    }

    @Override
    public boolean multiplePaths() {
        return delegate.multiplePaths();
    }

    @Override
    public KeyCounter usedItems() {
        return delegate.usedItems();
    }

    @Override
    public KeyCounter emittedItems() {
        return delegate.emittedItems();
    }

    @Override
    public KeyCounter missingItems() {
        return delegate.missingItems();
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return delegate.patternTimes();
    }

    @Override
    public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
        return true;
    }

    @Override
    public ICraftingPlan chexsonsaeutils$getDelegate() {
        return delegate;
    }

    @Override
    public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
        return copyCounter(recursiveInitialItems);
    }

    @Override
    public KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
        return copyCounter(recursiveInternalItems);
    }

    @Override
    public long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
        return recursiveFinalOutputAmount;
    }

    @Override
    public Map<AEItemKey, Map<AEKey, Long>> chexsonsaeutils$dyeableSelectedPatternInputs() {
        return copySelectedPatternInputs(selectedPatternInputs);
    }

    /**
     * 配给表按样板<b>实例</b>定版（同一 definition 可以是不同实例），所以拷贝必须保身份：
     * 外层用身份表，内层逐张换一份账本副本。
     *
     * <p>内层必须是<b>深拷贝</b>：{@link WideKeyCounter} 可变，直接把计划里的账本交出去，
     * 执行侧后续在那本上复加就会改写计划本身的配给上界（同一份计划被二次提交/对账时
     * 就会拿到一份被吃过的表）。老口径靠 {@code Map.copyOf} 得到不可变视图，这里等价地
     * 用 {@code copy()} 换隔离，读数一字不变。
     */
    @Override
    public Map<IPatternDetails, WideKeyCounter> chexsonsaeutils$planMaterialPlan() {
        return copyByPattern(planMaterialPlan);
    }

    /**
     * 封套那一本：与 {@link #chexsonsaeutils$planMaterialPlan()} <b>同一条读法、同一条拷贝规则</b>
     * （同一趟闸门账的两本账不许在两处各自解释壳层）。
     */
    @Override
    public Map<IPatternDetails, WideKeyCounter> chexsonsaeutils$planMaterialEnvelopes() {
        return copyByPattern(planMaterialEnvelopes);
    }

    /** 逐张样板的宽域账本读数：外层保身份、内层深拷贝，理由见 {@link #chexsonsaeutils$planMaterialPlan()}。 */
    private static Map<IPatternDetails, WideKeyCounter> copyByPattern(
            @Nullable Map<IPatternDetails, WideKeyCounter> original) {
        if (original == null || original.isEmpty()) {
            return Map.of();
        }
        var copy = new java.util.IdentityHashMap<IPatternDetails, WideKeyCounter>();
        for (var entry : original.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    private static KeyCounter copyCounter(KeyCounter original) {
        KeyCounter copy = new KeyCounter();
        if (original != null) {
            copy.addAll(original);
        }
        return copy;
    }

    private static Map<AEItemKey, Map<AEKey, Long>> copySelectedPatternInputs(
            @Nullable Map<AEItemKey, Map<AEKey, Long>> original) {
        if (original == null || original.isEmpty()) {
            return Map.of();
        }
        Map<AEItemKey, Map<AEKey, Long>> copy = new HashMap<>();
        for (var entry : original.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copy.put(entry.getKey(), Map.copyOf(new HashMap<>(entry.getValue())));
        }
        return Map.copyOf(copy);
    }
}
