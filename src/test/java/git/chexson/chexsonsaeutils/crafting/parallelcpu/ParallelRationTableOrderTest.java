package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveTaskOrdering;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配给那三本 {@link IdentityHashMap}（{@code planMaterialPlan} / {@code planMaterialEnvelopes} /
 * {@code planMaterialEaten}）的<b>遍历序</b>不许决定数字与点名（台账 #135 第 1、2 条）。
 *
 * <h3>缺陷形状</h3>
 * {@code IdentityHashMap} 按 {@code System.identityHashCode} 定桶、线性探测摆位，
 * 它的 {@code entrySet()} 序既不是登记序也不跨会话稳定：<b>换一次 JVM 就翻，
 * 同一会话里换一组实例也翻</b>。上一轮把 {@code describePlanBalance} 的五只外层换成
 * {@link LinkedHashMap}（{@code 560bd84}），但那五只的<b>喂入口</b>还在吃这本身份表：
 * <ul>
 * <li>{@code rationConsumersInExecutionOrder} 的尾循环（族到账<b>摊派</b>的顺位）⇒ 进数字；</li>
 * <li>{@code sumAssignedByKey}（{@code hole=} 的登记序 → 并列洞的前 8 <b>点名</b>）⇒ 进点名；</li>
 * <li>{@code formatQuotaBlindspots} / {@code formatOverTakes}（{@code quotaBlind=} / {@code over=}
 * 的点名次序与被 400 字符截掉的那几行）⇒ 进点名。</li>
 * </ul>
 *
 * <h3>为什么不许换成 {@link LinkedHashMap}（身份语义是刻意的）</h3>
 * AE2 四条样板实现一律 {@code return definition.hashCode();} + 按 {@code definition.equals} 判等
 * （{@code AECraftingPattern}／{@code AEProcessingPattern}／{@code AESmithingTablePattern}／
 * {@code AEStonecuttingPattern}），而守恒闸门给同一张样板记配给时用<b>两把键</b>：定键槽记
 * {@code GraphPattern#source()}（{@code patternsBySource} 是 equals 键的 HashMap，存它第一次收下的
 * 那一张），模糊槽排水与环成员记 {@code state.craftsWide()} 的键（份数账主表第一次在册的那一张）。
 * 同一个 equals 类因此在配给表上留两个身份、各记一份份额——换 equals 键的容器会把两笔并成一笔。
 * 本替身 {@link EqualByDefinitionPattern} 照 AE2 同形覆写 {@code equals}/{@code hashCode}，
 * 正是为了让"任务表上的那张"与"配给表上的那张"可以不是同一个对象。
 *
 * <h3>修法</h3>
 * 只换一个落点：身份表本体的语义与算术一字不动，新增唯一的全序读口
 * {@code ParallelExecutingCraftingJob#rationPatternsInExecutionOrder()}——前段＝任务表过
 * {@link RecursiveTaskOrdering} 那把尺，尾段＝身份不在任务表里的那张领它那一号条目的顺位号。
 * 上面那节说了，一个 equals 类在任务表里只有一个条目 ⇒ 尾段每张的顺位号<b>唯一</b>，不并列。
 * 不新增第三把尺，也不给 {@code ranked} 装第二把排序尺。
 */
final class ParallelRationTableOrderTest {

    /** 摊派这一族的键（24 张样板共用同一族，份额各 10 枚）。 */
    private static final AEKey FAMILY_KEY = AEItemKey.of(Items.IRON_INGOT);
    /** 与配给键无关的产物键：让 {@code describePlanBalance} 的供给腿盖不住那 24 个洞。 */
    private static final AEKey OUTPUT = AEItemKey.of(Items.NETHER_STAR);
    /** 交回任务表的份数。 */
    private static final long FIRINGS = 1L;
    /** 每张样板在这一族上的配给份额（枚）。 */
    private static final long SHARE_PER_PATTERN = 10L;
    /** 24 张各要 10 枚，本轮到账只够 12 张——夹哪 12 张成 0，由顺位说。 */
    private static final long ARRIVED_UNITS = 120L;
    /** 摊满的那几张（前 12 号）。 */
    private static final int FULLY_FUNDED = 12;

    /**
     * 24 个互不相等的样板本体（{@code equals} 取 definition ⇒ 同一 item 的两张实例相等）。
     * 取 24 只而不取三五只：{@code IdentityHashMap} 只有撞上同一个桶才让插入顺序影响遍历序，
     * 24 条目摊 64 槽的期望碰撞数是 4 次以上，反序插入必然给出另一份遍历序（判据才有牙）。
     */
    private static final List<AEItemKey> DEFINITIONS = List.of(
            AEItemKey.of(Items.DIRT), AEItemKey.of(Items.SAND), AEItemKey.of(Items.GRAVEL),
            AEItemKey.of(Items.CLAY), AEItemKey.of(Items.CLAY_BALL), AEItemKey.of(Items.STONE),
            AEItemKey.of(Items.COBBLESTONE), AEItemKey.of(Items.MOSSY_COBBLESTONE),
            AEItemKey.of(Items.OBSIDIAN), AEItemKey.of(Items.NETHERRACK),
            AEItemKey.of(Items.SOUL_SAND), AEItemKey.of(Items.SOUL_SOIL),
            AEItemKey.of(Items.END_STONE), AEItemKey.of(Items.PRISMARINE_SHARD),
            AEItemKey.of(Items.PRISMARINE_CRYSTALS), AEItemKey.of(Items.RED_SAND),
            AEItemKey.of(Items.TERRACOTTA), AEItemKey.of(Items.BRICK),
            AEItemKey.of(Items.PAPER), AEItemKey.of(Items.FEATHER),
            AEItemKey.of(Items.FLINT), AEItemKey.of(Items.STRING),
            AEItemKey.of(Items.SLIME_BALL), AEItemKey.of(Items.GUNPOWDER));

    /**
     * 第 1 条（进数字）：族到账摊派的<b>尾循环</b>顺位＝它那一号任务表条目的顺位，
     * 不再是 {@code IdentityHashMap} 的遍历序。
     *
     * <p>配给表 24 张全部按<b>另一实例</b>登记（闸门那两把键那一型），任务表上那张身份都认不到 ⇒
     * 名单整个落在尾循环里。本轮到账只够 12 张 ⇒ "摊满的那 12 张是谁"就是这一刀的读数。
     */
    @Test
    void rationTailKeepsItsTaskRankAcrossInsertionOrders() {
        var first = rationBuild(false);
        var second = rationBuild(true);

        assertEquals(DEFINITIONS, first.taskOrder(),
                "夹具前提：任务表底序＝规划侧注册序，那一把尺不动并列项");
        assertEquals(DEFINITIONS.subList(0, FULLY_FUNDED), first.fundedDefinitions(),
                () -> "摊派顺位＝任务表那把尺：前 12 张摊满、后 12 张夹成 0。实际摊满的那批="
                        + first.fundedDefinitions());
        assertEquals(first.fundedDefinitions(), second.fundedDefinitions(),
                "同一份台账只换身份表的插入顺序 ⇒ 摊到份额的那批与先后次序都不许变");
        assertEquals(first.shareVector(), second.shareVector(),
                "逐张份额读数随身份表插入序变＝摊派还在吃 System.identityHashCode 的序");
    }

    /**
     * 第 2 条（进点名）：{@code planBalance=} 里并列 {@code hole} 的点名次序＝推送底序，
     * 而不是 {@code sumAssignedByKey} 那本身份表的遍历序。
     *
     * <p>走 {@code src=plan}（照单采用）那一臂：24 张各配 10 枚、执行侧供给盖不住 ⇒ 24 个并列洞，
     * {@code ranked} 只按洞数值排一把尺（稳定排序），并列项的先后就是 {@code holes} 的插入序，
     * 而 {@code holes} 的插入序由 {@code sumAssignedByKey → need} 的回读序交回。
     */
    @Test
    void tiedHolesAreNamedInTheRationTableTaskRankAcrossInsertionOrders() {
        var first = balanceBuild(false);
        var second = balanceBuild(true);

        assertEquals(DEFINITIONS.stream().limit(8).map(String::valueOf).toList(),
                first.printedHoleKeys(),
                () -> "前 8 点名＝推送底序的头 8 个键（一把尺都不加）。实际点名="
                        + first.printedHoleKeys() + " 整行=" + first.line());
        assertEquals(first.line(), second.line(),
                "换一种身份表插入顺序就换一份 planBalance 读数＝还在吃哈希序：" + second.line());
    }

    // ---- 一次构造：摊派臂 ----

    /** 一次摊派构造的读数：任务表序、逐张份额（按任务表顺位摆）、摊满的那批。 */
    private record RationBuild(List<AEItemKey> taskOrder, List<Long> shareVector,
                               List<AEItemKey> fundedDefinitions) {
    }

    /**
     * @param reversedRationInsert 假＝按任务表顺位把 24 张插进身份表，真＝反着插。
     *                             两种构造用的是<b>同一批实例</b>，只有插入序不同。
     */
    private static RationBuild rationBuild(boolean reversedRationInsert) {
        var tasks = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
        var twins = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
        for (var definition : DEFINITIONS) {
            tasks.add(new EqualByDefinitionPattern(definition, FAMILY_KEY));
            twins.add(new EqualByDefinitionPattern(definition, FAMILY_KEY));
        }
        var times = new LinkedHashMap<IPatternDetails, Long>();
        for (var task : tasks) {
            times.put(task, FIRINGS);
        }
        var plan = new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        // 配给表按<b>另一实例</>登记：任务表上的那张在身份表里认不到，名单整段落进尾循环
        for (int index : insertionIndexes(reversedRationInsert)) {
            var assigned = new WideKeyCounter();
            assigned.add(FAMILY_KEY, SHARE_PER_PATTERN);
            MaterialAllocationFixtures.assign(job, twins.get(index), assigned);
        }

        job.narrowPlanMaterialShareByArrival(
                FAMILY_KEY, PlanAmount.of(ARRIVED_UNITS), PlanAmount.ZERO);

        var shares = new ArrayList<Long>(DEFINITIONS.size());
        var funded = new ArrayList<AEItemKey>();
        for (int index = 0; index < DEFINITIONS.size(); index++) {
            long left = MaterialAllocationFixtures.assignedSnapshot(job).get(twins.get(index)).getLong(FAMILY_KEY);
            shares.add(left);
            if (left >= SHARE_PER_PATTERN) {
                funded.add(DEFINITIONS.get(index));
            }
        }
        var taskOrder = job.tasks.keySet().stream().map(IPatternDetails::getDefinition).toList();
        return new RationBuild(List.copyOf(taskOrder), List.copyOf(shares), List.copyOf(funded));
    }

    // ---- 一次构造：提交边界读数臂 ----

    /** 一次 {@code describePlanBalance} 构造的读数：整行与按出现次序点名的洞键。 */
    private record BalanceBuild(String line, List<String> printedHoleKeys) {
    }

    private static BalanceBuild balanceBuild(boolean reversedRationInsert) {
        var tasks = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
        var twins = new ArrayList<EqualByDefinitionPattern>(DEFINITIONS.size());
        for (var definition : DEFINITIONS) {
            tasks.add(new EqualByDefinitionPattern(definition, definition));
            twins.add(new EqualByDefinitionPattern(definition, definition));
        }
        var times = new LinkedHashMap<IPatternDetails, Long>();
        for (var task : tasks) {
            times.put(task, FIRINGS);
        }
        var base = new CraftingPlan(new GenericStack(OUTPUT, FIRINGS), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var assigned = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        for (int index : insertionIndexes(reversedRationInsert)) {
            var ration = new WideKeyCounter();
            // 每张样板配给自己那号键 10 枚，而执行侧供给里没有这一号键 ⇒ 24 个并列洞
            ration.add(DEFINITIONS.get(index), SHARE_PER_PATTERN);
            assigned.put(twins.get(index), ration);
        }
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), FIRINGS,
                Map.of(), assigned, null, null, Set.of(), new IdentityHashMap<>());
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);

        ParallelCraftingCpuLogic.initializeSubmitMaterials(job, plan);
        var line = ParallelCraftingCpuLogic.describePlanBalance(job, plan);
        assertTrue(line.contains(" src=plan"),
                () -> "读的是照单采用那一臂（自算臂压根不吃这本身份表）：" + line);
        var printed = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile(" (minecraft:[a-z_]+) hole=").matcher(line);
        while (matcher.find()) {
            printed.add(matcher.group(1));
        }
        return new BalanceBuild(line, List.copyOf(printed));
    }

    /** 假＝按任务表顺位插入身份表；真＝反着插（同一批实例，只换插入序）。 */
    private static List<Integer> insertionIndexes(boolean reversed) {
        var indexes = new ArrayList<Integer>(DEFINITIONS.size());
        for (int index = 0; index < DEFINITIONS.size(); index++) {
            indexes.add(index);
        }
        if (reversed) {
            Collections.reverse(indexes);
        }
        return indexes;
    }

    /** 按 definition 定身位的样板替身（与 AE2 四条样板实现同形）。 */
    private static final class EqualByDefinitionPattern implements IPatternDetails {

        private final AEItemKey definition;
        private final AEKey input;

        private EqualByDefinitionPattern(AEItemKey definition, AEKey input) {
            this.definition = definition;
            this.input = input;
        }

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[] {new SingleCandidateSlot(input)};
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(OUTPUT, 1L));
        }

        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass() == getClass()
                    && ((EqualByDefinitionPattern) obj).definition.equals(definition);
        }

        @Override
        public int hashCode() {
            return definition.hashCode();
        }
    }

    /** 单候选槽：候选就是那一号键，一发一枚。 */
    private record SingleCandidateSlot(AEKey candidate) implements IPatternDetails.IInput {

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
