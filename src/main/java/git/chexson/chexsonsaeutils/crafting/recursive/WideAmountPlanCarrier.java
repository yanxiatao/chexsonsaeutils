package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.networking.crafting.ICraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import org.jetbrains.annotations.Nullable;

/**
 * 计划对象自带的宽域账载体：AE2 的 {@code KeyCounter(long)} 通道把超 {@code long} 的计划数量
 * 饱和成 {@code Long.MAX_VALUE}，真值只有随计划对象带出来才回得来，供确认菜单下发给客户端显示。
 *
 * <p><b>为什么挂在计划对象上而不是旁路表</b>：宽域账与计划同生同灭。图规划出口构造计划那一刻
 * 是唯一同时握有 {@code SolveResult.state()} 三张 {@code WideKeyCounter} 的时刻，把它作为计划
 * 自己的分量带出去，就不需要"按对象身份建表 + 上限淘汰 + 陈旧清理"那一整套——那套东西的每个
 * 分支都只是为了让旁路表和计划保持同一份账而存在，账在对象上时全部是冗余。
 *
 * <p>计划载体本身是内存对象；宽域发数、供给与精确根请求由执行侧消费，
 * 根交付量和剩余量由执行中的作业持久化，不能从饱和的 long 值恢复。
 *
 * <p>实现者两类：{@link RecursiveCraftingPlan}（递归计划，账是它的第 7 个分量）、
 * {@link WideAmountCraftingPlan}（非环图计划的透明载体，只在真有溢出时套）。
 * AE2 原生计划与图引擎之外的树规划出口没有载体，按 {@link #resolveWideLedgers} 退化成空快照，
 * 界面原样显示 AE2 的 long。
 */
public interface WideAmountPlanCarrier {

    /** 超 long 根订单的精确交付量；null 表示原计划的 finalOutput long 本身就是真值。 */
    default @Nullable git.chexson.chexsonsaeutils.bigint.PlanAmount chexsonsaeutils$exactFinalOutput() { return null; }

    /** 按既有解壳规则读取根订单真值，普通 AE2 计划直接使用它的有限 long 数量。 */
    static git.chexson.chexsonsaeutils.bigint.PlanAmount resolveFinalOutput(ICraftingPlan plan) {
        var amount = readThrough(plan, WideAmountPlanCarrier::chexsonsaeutils$exactFinalOutput);
        return amount != null ? amount : git.chexson.chexsonsaeutils.bigint.PlanAmount.of(plan.finalOutput().amount());
    }

    /** 计划所需字节真值；null 表示原计划 bytes 的有限 long 已经是真值。 */
    default @Nullable git.chexson.chexsonsaeutils.bigint.PlanAmount chexsonsaeutils$exactBytes() { return null; }

    /** 读取精确字节占用，饱和值只用于 AE2 的 long 接口边界。 */
    static git.chexson.chexsonsaeutils.bigint.PlanAmount resolveBytes(ICraftingPlan plan) {
        var bytes = readThrough(plan, WideAmountPlanCarrier::chexsonsaeutils$exactBytes);
        return bytes != null ? bytes : git.chexson.chexsonsaeutils.bigint.PlanAmount.of(plan.bytes());
    }

    /** 本计划的宽域溢出账（只含绝对值超过 {@code long} 的键）；无溢出时为空快照，永不为 {@code null}。 */
    WideLedgerSnapshot chexsonsaeutils$wideLedgers();

    /**
     * 本计划的宽域<b>份数</b>字典（只含发数超过 {@code long} 的样板）；没有超界发数时为空快照，
     * 永不为 {@code null}。
     *
     * <p>读法只有一条：{@code patternTimes()} 是 AE2 那份饱和代表值，快照里有这个样板就用快照里的
     * 真值，否则代表值就是真值。
     */
    WideCraftSnapshot chexsonsaeutils$wideCrafts();

    /**
     * 规划侧认出是<b>无限源</b>的键（"报得少、给得多"那一类：AE2 创造盘、第三方无限元件）。
     *
     * <p>它不是显示旁路：执行侧照这份键集豁免配额与占料封顶，并把网络给不出的那一截直接
     * 注进 CPU 库存——无限键在 {@code long} 域里没有上界，走"从网络提取"这一步必然夹死。
     *
     * <p>缺省空集：AE2 原生计划与认不到载体的壳层一律退回今日口径（普通键）。
     */
    default java.util.Set<appeng.api.stacks.AEKey> chexsonsaeutils$unboundedKeys() {
        return java.util.Set.of();
    }

    /**
     * 从菜单手上的计划解析宽域账：先认载体本身；不是载体就按
     * {@link RecursivePlanContract#chexsonsaeutils$getDelegate()} 解一层壳再认
     * （确认菜单可能拿到被剥壳后的委托件，提交侧 {@code NativeJobSubmissionPlan} 还会多套一层）。
     * 都认不到返回 {@link WideLedgerSnapshot#empty()}——调用方不必判空、不会抛。
     */
    static WideLedgerSnapshot resolveWideLedgers(@Nullable ICraftingPlan plan) {
        WideLedgerSnapshot direct = readThrough(plan, WideAmountPlanCarrier::chexsonsaeutils$wideLedgers);
        return direct == null ? WideLedgerSnapshot.empty() : direct;
    }

    /**
     * 从计划对象解析宽域份数字典：解壳规则与 {@link #resolveWideLedgers} 同一条（同一份
     * {@link #readThrough} 实现），下游（任务表宽域）因此不会在两处认到不同的壳层。
     * 认不到载体返回 {@link WideCraftSnapshot#empty()}——调用方不必判空、不会抛。
     */
    static WideCraftSnapshot resolveWideCrafts(@Nullable ICraftingPlan plan) {
        WideCraftSnapshot direct = readThrough(plan, WideAmountPlanCarrier::chexsonsaeutils$wideCrafts);
        return direct == null ? WideCraftSnapshot.empty() : direct;
    }

    /**
     * 从计划对象解析无限源键集：解壳规则与 {@link #resolveWideLedgers} 同一条。
     * 认不到载体返回空集（= 今日口径），调用方不必判空、不会抛。
     */
    static java.util.Set<appeng.api.stacks.AEKey> resolveUnboundedKeys(@Nullable ICraftingPlan plan) {
        java.util.Set<appeng.api.stacks.AEKey> direct =
                readThrough(plan, WideAmountPlanCarrier::chexsonsaeutils$unboundedKeys);
        return direct == null ? java.util.Set.of() : direct;
    }

    /** 载体分量读取：本对象 → 解一层委托 → {@code null}（由调用方决定退路）。 */
    @Nullable
    private static <T> T readThrough(@Nullable ICraftingPlan plan,
            java.util.function.Function<WideAmountPlanCarrier, T> reader) {
        if (plan instanceof WideAmountPlanCarrier carrier) {
            return reader.apply(carrier);
        }
        if (plan instanceof RecursivePlanContract contract) {
            ICraftingPlan delegate = contract.chexsonsaeutils$getDelegate();
            // 自指委托（delegate == plan）不再下探，解壳不可能变成死循环。
            if (delegate != null && delegate != plan && delegate instanceof WideAmountPlanCarrier carrier) {
                return reader.apply(carrier);
            }
        }
        return null;
    }
}
