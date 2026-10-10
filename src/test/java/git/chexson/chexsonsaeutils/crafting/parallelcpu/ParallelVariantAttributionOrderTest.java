package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
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
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排水记的<b>付款键顺位</b>是这一槽的候选顺位，不是哈希序（台账 #137，MF-2）。
 *
 * <p>缺陷形状：{@code SlotAccount.drain} 里 {@code funded = new HashMap<>()}（窄域入口
 * {@code drainSlot} 同一形）。链上两步都吃它的遍历序——
 * <ol>
 * <li>{@code ParallelCraftingCpuLogic#mintSubmitAllocation} 逐笔
 * {@code for (var paid : drained.funded().entrySet())} 把份额 {@code mergePlannedShare} 进那张样板的
 * {@link WideKeyCounter} ⇒ 键的 id 就是这份序；</li>
 * <li>族到账摊派 {@code ParallelExecutingCraftingJob#narrowVariants} 按 {@code assigned.keys()}
 * （＝登记序）逐变体扣 ⇒ <b>同族各变体分到几枚</b>是这份序直接决定的数字。</li>
 * </ol>
 * {@link java.util.HashMap} 的序由 {@code AEKey} 的哈希决定，而 {@code AEItemKey} 的哈希取
 * {@code ItemStack.hashItemAndComponents}（含 {@code Item} 的身份哈希）⇒ 同一族几个变体谁排在前面，
 * 随 JVM 会话变。
 *
 * <p>修法与判据同尺：{@code funded} 改成按<b>槽自己的候选顺位</b>登记（{@link java.util.LinkedHashMap}，
 * 与 drain 既有的"榨干靠前候选才退下一个"同一条序），不新造第三把尺。
 *
 * <p>本用例走真链（提交期排水 mint → 唯一收窄口摊派），两次构造的差别只在<b>与本顺位无关的插入序</b>
 * （第二次把 {@code usedItems} 那几笔反着写）：两档归属必须逐字相同，且等于独立手算表。
 * 改前的树上"谁保住自己那一格"由变体键的哈希决定 ⇒ 与手算表不符即红。
 *
 * <p><b>牙在哪（台账 #141 第 1 条）</b>：账本键是上面那批 {@code static final} 的同一批实例，
 * {@code HashMap} 的遍历序只看键的哈希、不看插入序 ⇒ "反着写 {@code usedItems}" 那条等价腿对
 * "排水记的付款键退回 {@code HashMap}"这一型<b>恒真</b>，绝对钉值只剩 {@code 1-1/k!} 的概率牙。
 * 所以判据多一条确定性的：先用<b>同一批键</b>建 {@code HashMap}（正插与反插各一遍），断它的遍历序
 * <b>不等于</b>候选顺位——产品一旦退回哈希序，这一档必红，而不是"多半会红"。
 */
final class ParallelVariantAttributionOrderTest {

    private static final AEItemKey CELL = AEItemKey.of(Items.CHEST);
    private static final AEItemKey PATTERN_ITEM = AEItemKey.of(Items.STONE);

    /** 一族（同一把铁镐）的五个变体键本体（创建序固定，哈希序由会话定）。 */
    private static final AEKey V1 = wornKey(10);
    private static final AEKey V2 = wornKey(20);
    private static final AEKey V3 = wornKey(30);
    private static final AEKey V4 = wornKey(40);
    private static final AEKey V5 = wornKey(50);

    /**
     * 候选顺位＝登记顺位＝摊派顺位，并且<b>按构造确定不等于本会话的哈希序</b>（台账 #141 第 1 条）。
     *
     * <p>这一批账本键是 {@code static final} 的同一批实例 ⇒ {@code HashMap} 的遍历序与插入序无关
     * ⇒ "把 {@code usedItems} 反着写"那条等价腿对"付款键容器退回 {@code HashMap}"这一型<b>恒真</b>，
     * 绝对钉值只剩 {@code 1-1/k!} 的概率牙。所以候选顺位取"同一批键实测出的 {@code HashMap}
     * 遍历序的反序"：反序必不等于哈希序 ⇒ 产品一退回哈希序，本用例每次会话都确定红。
     */
    private static final List<AEKey> CANDIDATES = reverseOfHashOrder(List.of(V1, V2, V3, V4, V5));

    /** 用产品同一批键建 {@code HashMap}，交出其遍历序的反序（＝本用例钉住的候选顺位）。 */
    private static List<AEKey> reverseOfHashOrder(List<AEKey> keys) {
        return List.copyOf(reversed(hashOrderWhenInsertedAs(keys)));
    }

    /**
     * 反事实读数：把 {@code insertionOrder} 这笔序按原样喂进一只 {@code HashMap}（＝容器退回
     * 哈希序时产品会走的那条路），交出它的遍历序。
     */
    private static List<AEKey> hashOrderWhenInsertedAs(List<AEKey> insertionOrder) {
        var probe = new java.util.HashMap<AEKey, Boolean>();
        for (var key : insertionOrder) {
            probe.put(key, Boolean.TRUE);
        }
        return List.copyOf(probe.keySet());
    }

    private static List<AEKey> reversed(List<AEKey> keys) {
        var out = new ArrayList<>(keys);
        Collections.reverse(out);
        return List.copyOf(out);
    }

    /** 族到账只有登记首位那一张的 10 枚。 */
    private static final long ARRIVED = 10L;

    private static AEKey wornKey(int damage) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }

    /**
     * 独立手算表（纸面算出，不经被测代码）。一发一枚、50 发；每一号候选的池子里躺着 10 枚，
     * 排水按候选顺位逐号榨干 ⇒ 五个变体各付 10 枚，共 50＝全部发数（无缺口）。
     *
     * <table border="1">
     * <caption>#137：候选顺位登记 + 族池 10 的摊派</caption>
     * <tr><th>变体（候选顺位）</th><th>mint 份额</th><th>族摊派后</th></tr>
     * <tr><td>候选首位</td><td>10</td><td>10（族池 10 枚被登记首位那一号摊走）</td></tr>
     * <tr><td>候选第 2..5 号</td><td>各 10</td><td>各 0</td></tr>
     * <tr><td>族合计</td><td>50</td><td>10＝族到账</td></tr>
     * </table>
     *
     * <p>"首位到底是哪一号耐久"随会话变（候选顺位按构造取本会话哈希序的反序，见上面那条），
     * <b>不随会话变</b>的是相对归属：只有候选首位那一号保住 10 枚，其余四号摊到 0。
     * 登记序若是哈希序，"10 枚归谁"就换人——被保住的那一格不再是候选首位那一号。
     */
    @Test
    void variantSharesFollowSlotCandidateOrderNotKeyHash() {
        // 确定性牙（#141 第 1 条）：把候选顺位原样喂进一只 HashMap——那正是"付款键容器退回
        // 哈希序"时产品会走的那条路。它的遍历序必须不等于候选顺位，钉值才每次会话都能抓住回归。
        assertNotEquals(candidateOrderReadout(),
                hashOrderWhenInsertedAs(CANDIDATES).stream()
                        .map(ParallelVariantAttributionOrderTest::label).toList(),
                () -> "判据自身失效：这批键的哈希序碰巧等于候选顺位，绝对钉值挡不住退回哈希序");
        var ordered = readBuild(false);
        var shuffledInsertion = readBuild(true);

        assertEquals(candidateOrderReadout(), ordered.registeredVariants(),
                "配给账里这一族的变体顺位＝这一槽的候选顺位（排水按它逐号付，摊派按它逐号扣）");
        var handTable = new java.util.LinkedHashMap<String, BigInteger>();
        for (int index = 0; index < CANDIDATES.size(); index++) {
            handTable.put(label(CANDIDATES.get(index)),
                    index == 0 ? BigInteger.valueOf(ARRIVED) : BigInteger.ZERO);
        }
        assertEquals(Map.copyOf(handTable), ordered.shares,
                "族池 10 枚该归候选顺位首位那一个变体（手算表）");
        assertEquals(BigInteger.valueOf(ARRIVED), ordered.familyTotal,
                "族合计收到族到账 10：不是 50（没收窄）也不是 0（把保住的那一格也夹没）");
        assertEquals(ARRIVED, ordered.quotaOfFirst, "取料点向候选首位那一号放行 10");

        assertEquals(ordered.registeredVariants(), shuffledInsertion.registeredVariants(),
                "与本顺位无关的插入序（usedItems 反着写、换一组样板实例）不许改登记序");
        assertEquals(ordered.shares, shuffledInsertion.shares,
                "两档归属逐字相同：哪个变体保住 10 枚、哪几个摊到 0");
        assertEquals(ordered.familyTotal, shuffledInsertion.familyTotal);
    }

    /**
     * 一次构造的全部读数。变体一律以<b>耐久值</b>点名——{@code AEItemKey#toString} 不印组件，
     * 五个变体打出来是同一串 {@code minecraft:iron_pickaxe (with patches)}，红字会无从判读。
     */
    private record Build(List<String> registeredVariants, Map<String, BigInteger> shares,
            BigInteger familyTotal, long quotaOfFirst) {
    }

    private static List<String> candidateOrderReadout() {
        return CANDIDATES.stream().map(ParallelVariantAttributionOrderTest::label).toList();
    }

    private static String label(AEKey key) {
        return "dmg=" + ((AEItemKey) key).toStack().getDamageValue();
    }

    /**
     * @param reverseSupplyInsertionOrder 第二次构造：把 {@code usedItems} 那几笔反着写——它只喂
     *                                    池子的<b>查表</b>，不该决定任何顺位
     */
    private static Build readBuild(boolean reverseSupplyInsertionOrder) {
        var pattern = new FuzzyConsumerPattern();
        var used = new KeyCounter();
        var supplies = new ArrayList<>(CANDIDATES);
        if (reverseSupplyInsertionOrder) {
            Collections.reverse(supplies);
        }
        for (AEKey variant : supplies) {
            used.add(variant, ARRIVED);
        }
        var plan = new CraftingPlan(new GenericStack(CELL, 1L), 0L, false, false,
                used, new KeyCounter(), new KeyCounter(),
                new java.util.LinkedHashMap<IPatternDetails, Long>(Map.of(pattern, 50L)));
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        // 真链第一步：提交期自算配给趟（排水按候选顺位逐号付）
        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertTrue(line.contains(" src=submit"), () -> "读的就是自算那一臂：" + line);
        assertTrue(line.contains(" ok keys="), () -> "50 发全被候选池盖住，本用例不该有缺口：" + line);

        // 真链第二步：唯一的族收窄口（族到账按<b>候选首位</b>那一号的池子计）
        var firstCandidate = CANDIDATES.get(0);
        job.narrowPlanMaterialShareByArrival(firstCandidate, PlanAmount.of(ARRIVED), PlanAmount.ZERO);

        var assigned = MaterialAllocationFixtures.assignedSnapshot(job).get(pattern);
        var registered = new ArrayList<String>();
        var shares = new java.util.LinkedHashMap<String, BigInteger>();
        var familyTotal = BigInteger.ZERO;
        for (AEKey variant : assigned.keys()) {
            registered.add(label(variant));
            shares.put(label(variant), assigned.getExact(variant));
            familyTotal = familyTotal.add(assigned.getExact(variant));
        }
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        return new Build(List.copyOf(registered), Map.copyOf(shares), familyTotal,
                reservation.remainingQuota(pattern, firstCandidate));
    }

    /** 一格五个候选（同族五个变体）的消费者样板；不覆写 equals/hashCode＝每组实例一位身份哈希。 */
    private static final class FuzzyConsumerPattern implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return PATTERN_ITEM;
        }

        @Override
        public IInput[] getInputs() {
            var candidates = new GenericStack[CANDIDATES.size()];
            for (int index = 0; index < candidates.length; index++) {
                candidates[index] = new GenericStack(CANDIDATES.get(index), 1L);
            }
            return new IInput[] {new FuzzySlot(candidates)};
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(CELL, 1L));
        }
    }

    /** 候选顺位＝数组序（AE2 的模糊槽就是这个形状）；一发一枚、无退料。 */
    private record FuzzySlot(GenericStack[] candidates) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates;
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey key, @Nullable net.minecraft.world.level.Level level) {
            for (var candidate : candidates) {
                if (candidate.what().equals(key)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
