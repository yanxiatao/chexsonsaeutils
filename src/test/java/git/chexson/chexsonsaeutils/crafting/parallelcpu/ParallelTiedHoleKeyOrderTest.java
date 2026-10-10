package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停滞行 {@code planBalance=} 里<b>并列 hole</b> 的键次序＝这趟账的登记序（台账 #135 的 P2 那一档，
 * MF-3）。
 *
 * <p>缺陷形状：{@code describePlanBalance} 的 {@code emitted}/{@code supply}/{@code holes}/
 * {@code consumers} 外层是 {@link java.util.HashMap}，而 {@code ranked} 只按 {@code hole} 数值排一把尺
 * （{@code e5e5a45} 刚把 definition 预排那第二把尺拆掉，这里不装回来）。{@link java.util.List#sort}
 * 稳定 ⇒ 并列 hole 保持 {@code holes} 的遍历序＝<b>哈希序</b>：六个键各欠 1 枚时，
 * "前 8 点名哪几个、按什么次序点名"随 {@code AEKey} 的哈希（取到 {@code Item} 的身份哈希）随会话变。
 *
 * <p>修法只换一个容器：那四本外层改 {@link java.util.LinkedHashMap}，靠插入序自然落回推送底序
 * （排水按 {@code RecursiveTaskOrdering} 那份序逐槽记账，谁先被记上谁先点名）。<b>不给 {@code ranked}
 * 加第二把排序尺。</b>
 *
 * <p>这一档是读数不回写（不进任何判据账），所以 P2：它不改哪个键真缺、缺几枚，只改点名次序。
 * 判据仍按本仓口径——审计 oracle 先要全序，数字才可复现。
 *
 * <p><b>两条臂与一把确定的牙</b>（台账 #141 第 1、2 条）：自算臂（{@code src=submit}）与
 * plan 表臂（{@code src=plan}，生产主路）各钉一条同型用例；期望序取"同一批账本键实测出的
 * {@code HashMap} 遍历序的反序"，于是"退回哈希序必红"是每次会话的确定事件，而不是
 * {@code 1-1/k!} 的概率事件。
 */
final class ParallelTiedHoleKeyOrderTest {

    private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);

    /** 六个键的本体（同一批实例，创建序固定；它们的哈希序由会话定）。 */
    private static final List<AEKey> KEYS = List.of(
            AEItemKey.of(Items.LAPIS_LAZULI),
            AEItemKey.of(Items.DIAMOND),
            AEItemKey.of(Items.REDSTONE),
            AEItemKey.of(Items.EMERALD),
            AEItemKey.of(Items.GOLD_INGOT),
            AEItemKey.of(Items.IRON_INGOT));

    /**
     * 内容序＝注册序＝排水序，并且<b>按构造确定不等于本会话的哈希序</b>（台账 #141 第 1 条的牙）。
     *
     * <p>为什么必须这样摆：账本键是同一批 {@code static final} 的 {@code AEKey} 实例，
     * {@code HashMap} 的遍历序只看键的哈希、与插入序无关 ⇒ "反序构造样板实例"那条等价腿对
     * "外层容器退回 {@code HashMap}"这一型<b>恒真</b>，绝对钉值只剩 {@code 1-1/k!} 的概率牙。
     * 这里改用同一批键实测出的哈希序的<b>反序</b>当期望序：反序必不等于哈希序（互异元素），
     * 产品一旦退回哈希序，两条臂都<b>每次会话确定红</b>。
     */
    private static final List<AEKey> CONTENT_ORDER = reverseOf(hashOrderWhenInsertedAs(KEYS));

    /**
     * 反事实读数：把 {@code insertionOrder} 原样喂进一只 {@code HashMap}——那正是"外层容器退回
     * 哈希序"时产品会走的那条路——交出它的遍历序。
     */
    private static List<AEKey> hashOrderWhenInsertedAs(List<AEKey> insertionOrder) {
        var probe = new java.util.HashMap<AEKey, Boolean>();
        for (var key : insertionOrder) {
            probe.put(key, Boolean.TRUE);
        }
        return List.copyOf(probe.keySet());
    }

    private static List<AEKey> reverseOf(List<AEKey> keys) {
        var out = new ArrayList<>(keys);
        Collections.reverse(out);
        return List.copyOf(out);
    }

    /**
     * 确定性牙：期望序（＝注册序）按构造是本会话哈希序的反序，所以"产品若走哈希序"给出的读数
     * 必不等于钉值。这一条在两条臂的开头各跑一次，红字直接点名判据自身失效的那一种会话。
     */
    private static void assertPinnedOrderIsNotTheHashOrder() {
        var pinned = CONTENT_ORDER.stream().map(String::valueOf).toList();
        var counterfactual = hashOrderWhenInsertedAs(CONTENT_ORDER).stream()
                .map(String::valueOf)
                .toList();
        assertNotEquals(pinned, counterfactual,
                () -> "判据自身失效：这批键的 HashMap 遍历序碰巧等于钉住的注册序，绝对钉值挡不住"
                        + "退回哈希序那一型（换键，不是放宽判据）");
    }

    /** 六个键各欠 1 枚（一发一枚、网络与自产都没有）：并列 hole 的整档形状。 */
    @Test
    void tiedHolesAreNamedInTheOrderTheDrainBilledThem() {
        var expected = CONTENT_ORDER.stream().map(String::valueOf).toList();
        assertPinnedOrderIsNotTheHashOrder();
        var first = readBuild(false);
        var second = readBuild(true);

        assertTrue(first.line().contains(" unbalanced=" + CONTENT_ORDER.size() + "/"),
                () -> "前提读数：六个键各一个洞：" + first.line());
        assertEquals(expected, first.printedHoleKeys(),
                "并列 hole 的点名次序＝排水记账序（一把尺都不加）：" + first.line());
        // 这一条只挡"点名跟着样板实例的身份哈希走"那一型：账本键是上面那批 static final 的
        // AEKey，反序创建样板实例并不换它们的哈希序 ⇒ 它对"退回 AEKey 哈希序"恒真，牙在上面那条。
        assertEquals(expected, second.printedHoleKeys(),
                "换一组样板实例（身份哈希不同、创建顺序相反）后，同一份计划该给出同一份点名次序");
        assertEquals(first.line(), second.line(),
                "整行读数随样板实例变＝还在读实例哈希序：" + second.line());
    }

    /**
     * 同型臂走 <b>plan 表</b>（{@code adoptPlannedMaterialPlan} 收下规划出口的配给表，
     * 读数走 {@code billPlannedMaterialHoles} → {@code sumAssignedByKey}）——生产主路是这一条，
     * 上面那条自算臂（{@code src=submit}）盖不住它（台账 #141 第 2 条）。
     *
     * <p>这一臂上并列洞的点名次序由 {@code ParallelExecutingCraftingJob#rationPatternsInExecutionOrder}
     * （任务表过 {@link RecursiveTaskOrdering}）交回，与配给那本身份表的插入序无关：两次构造只换
     * 身份表的插入序，整行读数必须逐字相同，且等于自算臂那份期望序（同一把尺、同一份注册序）。
     */
    @Test
    void tiedHolesAreNamedInTheSameOrderOnThePlanTableArm() {
        var expected = CONTENT_ORDER.stream().map(String::valueOf).toList();
        assertPinnedOrderIsNotTheHashOrder();
        var first = readPlanArmBuild(false);
        var second = readPlanArmBuild(true);

        assertTrue(first.line().contains(" src=plan"),
                () -> "这一臂读的是照单采用的配给表（自算臂压根不碰它）：" + first.line());
        assertTrue(first.line().contains(" unbalanced=" + CONTENT_ORDER.size() + "/"),
                () -> "前提读数：六个键各一个洞：" + first.line());
        assertEquals(expected, first.printedHoleKeys(),
                () -> "plan 表臂的并列 hole 点名次序＝任务表那把尺的登记序：" + first.line());
        assertEquals(first.line(), second.line(),
                () -> "换一种配给身份表插入序就换一份点名＝还在吃 System.identityHashCode："
                        + second.line());
    }

    /** 一次构造：造计划 → 建作业 → 走真读数口。 */
    private static Build readBuild(boolean reverseCreationOrder) {
        // 实例创建序可以反过来摆（登记序不变）：这一份序对账面没有任何语义，正是不许影响点名次序的东西
        var creationOrder = new ArrayList<>(CONTENT_ORDER);
        if (reverseCreationOrder) {
            Collections.reverse(creationOrder);
        }
        var byKey = new LinkedHashMap<AEKey, TiedHolePattern>();
        for (var key : creationOrder) {
            byKey.put(key, new TiedHolePattern((AEItemKey) key));
        }
        var times = new LinkedHashMap<IPatternDetails, Long>();
        for (var key : CONTENT_ORDER) {
            times.put(byKey.get(key), 1L);
        }
        var plan = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        // 前提读数：任务表顺位＝注册序，且这一档没有环成员（那把尺不动并列项）
        var ordered = RecursiveTaskOrdering.orderedEntries(job.tasks, job.orderingInternalItems());
        assertEquals(CONTENT_ORDER, ordered.stream().map(entry -> entry.getKey().getDefinition()).toList(),
                "夹具前提：推送底序没立住，本用例的点名次序就无从判读");

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        return new Build(line, printedHoleKeys(line));
    }

    /** 一次构造的读数：整行与按出现次序点名的洞键。 */
    private record Build(String line, List<String> printedHoleKeys) {
    }

    /**
     * plan 表臂的一次构造：配给表随 {@link RecursiveCraftingPlan} 出门，
     * {@code describePlanBalance} 走 {@code adoptPlannedMaterialPlan}（{@code src=plan}）。
     *
     * @param reversedRationInsert 假＝按注册序把六张插进配给那本身份表，真＝反着插（同一批实例）
     */
    private static Build readPlanArmBuild(boolean reversedRationInsert) {
        var byKey = new LinkedHashMap<AEKey, TiedHolePattern>();
        for (var key : CONTENT_ORDER) {
            byKey.put(key, new TiedHolePattern((AEItemKey) key));
        }
        var times = new LinkedHashMap<IPatternDetails, Long>();
        for (var key : CONTENT_ORDER) {
            times.put(byKey.get(key), 1L);
        }
        var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);

        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        var insertionOrder = new ArrayList<>(CONTENT_ORDER);
        if (reversedRationInsert) {
            Collections.reverse(insertionOrder);
        }
        for (var key : insertionOrder) {
            // 每张样板配自己那一号键 1 枚，执行侧供给里没有这一号键 ⇒ 六个并列 hole
            var ration = new WideKeyCounter();
            ration.add(key, 1L);
            assigned.put(byKey.get(key), ration);
        }
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), 1L,
                Map.of(), assigned, null, null, java.util.Set.of(), new IdentityHashMap<>());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        var ordered = RecursiveTaskOrdering.orderedEntries(job.tasks, job.orderingInternalItems());
        assertEquals(CONTENT_ORDER, ordered.stream().map(entry -> entry.getKey().getDefinition()).toList(),
                "夹具前提：推送底序没立住，plan 表臂的点名次序就无从判读");

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        return new Build(line, printedHoleKeys(line));
    }

    /** 按出现次序把 {@code xxx hole=} 点名的键抠出来。 */
    private static List<String> printedHoleKeys(String line) {
        var printed = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile(" (minecraft:[a-z_]+) hole=").matcher(line);
        while (matcher.find()) {
            printed.add(matcher.group(1));
        }
        return List.copyOf(printed);
    }

    /** 一格单候选（内容序那一号键）的样板；不覆写 equals/hashCode＝每组实例一位身份哈希。 */
    private static final class TiedHolePattern implements IPatternDetails {

        private final AEItemKey key;

        private TiedHolePattern(AEItemKey key) {
            this.key = key;
        }

        @Override
        public AEItemKey getDefinition() {
            return key;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] {new TiedHoleSlot(key)};
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(CELL, 1L));
        }
    }

    private record TiedHoleSlot(AEKey candidate) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(candidate, 1L)};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, @Nullable net.minecraft.world.level.Level level) {
            return candidate.equals(input);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
