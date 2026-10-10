package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两格<b>定因读数</b>的用例（纯读数，不参与任何判据）。
 *
 * <h2>读数一：点名某张样板的那几格要能对上同一张样板</h2>
 * 实机 {@code lane cc5a2c03} 那一跑：{@code quotaBlind=} 的 {@code src=} 印的是
 * {@code src=useless_mod:omniversal_pattern (with patches)#1625664262962646930102[backoff]}（那个长数是
 * plan 的号，不是实例身份），而 {@code noCandWhy=} 点的是<b>另一张</b>万象样板实例——两格都没有实例身份，
 * 归因就在这一刻断掉。AE2 的样板实现一律按 {@code getDefinition()} 覆写 {@code equals}
 * （{@code AECraftingPattern:142} 等四条同形），同名两张在任何按样板记的账里就是同一个键，光看名字配不上号。
 * 现在 {@code noCandWhy=}／{@code quotaBlind=} 与 {@code alloc=} 的 {@code src=}／
 * {@code 本轮最后算的那张样板=}／{@code blocked=} 的样板头都带<b>同一个</b>身份标 {@code #i<hex>}
 * （{@link ParallelCraftingCpuLogic#patternIdentityTag}）。
 *
 * <h2>读数二：被退避跳过的那张样板，当轮真失败的那一格</h2>
 * {@code executeCrafting} 在 {@code backoffUntil > currentTick} 时直接 {@code continue} ⇒ 这张样板
 * <b>不产生 attempts</b> ⇒ {@code materialBackoff=18} 只说得出"跳过了 18 次"，看不出它上一轮为什么失败；
 * 而它当初欠哪些键记在 {@code materialBackoffMissing} 那本现成的账里（只为 {@code postChange} 唤醒而写，
 * <b>从不打印</b>）。新格 {@code backoffBlind=[样板头#i<身份>#slotN,欠的键,每发=…,池内=…,网络sim=…]}
 * 把那本账落成读数。
 *
 * <p>两条判据都从<b>真渲染口</b>取整行原文（{@link ParallelCraftingCpuLogic.StallProbe#describeStallLine}
 * 与 {@link ParallelCraftingCpuLogic#describeObligationStallLine}——生产侧 {@code AELog} 打的就是它们交出的
 * 整行），不另建第二本账、不直调私有格式化件。
 */
final class ParallelPatternIdentityAndBackoffBlindTest {

    /** 带组件的键：{@code toString()} 是 "{@code <id> (with patches)}"，两个外来空格就是形状判据的牙。 */
    private static final AEItemKey WORN = worn(50);
    private static final AEKey SHINING = AEItemKey.of(Items.NETHERITE_BLOCK);
    private static final AEKey CELL = AEItemKey.of(Items.CHEST);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final long SESSION_QUOTA = 42_662_400_000L;

    /** 样板身份标 {@code #i<hex>} 的样（八位以内十六进制，全小写）。 */
    private static final java.util.regex.Pattern IDENTITY_TAG =
            java.util.regex.Pattern.compile("#i([0-9a-f]{1,8})");

    /**
     * 判据①：{@code noCandWhy=} 与 {@code quotaBlind=} 的 {@code src=} 带<b>同一个</b>样板身份标，
     * 且与 {@code blocked=} 的样板头一致；两张同名不同实例的样板各自的身份标<b>必须不同</b>。
     *
     * <p>改前红在"两格里压根没有身份标"：{@code #i} 一个都找不出来 ⇒ 取不出 tag ⇒ 断言红。
     */
    @Test
    void theSamePatternCarriesOneIdentityTagAcrossNoCandWhyAndQuotaBlindSrc() {
        // 生产者：产 SHINING，自己那一格吃 WORN（池里一枚没有 ⇒ 真走 noCand 那一档）
        var slots = new OmniSlot[]{new OmniSlot(new GenericStack[]{new GenericStack(WORN, 4L)}, 1L)};
        var producer = new FakePattern(AEItemKey.of(Items.GRINDSTONE), slots,
                List.of(new GenericStack(SHINING, 1L)));
        // 另一张<b>同名不同实例</b>的万象样板：与 producer 按 equals 相等（AE2 就按 definition 覆写 equals）
        var twin = new FakePattern(producer.getDefinition(), slots, producer.getOutputs());
        assertEquals(producer, twin, "夹具前提：两张样板按 equals 必须是同一张（AE2 的样板就长这样）");
        assertTrue(producer != twin, "夹具前提：它们是<b>两个实例</b>——只看名字配不上号的那一型");

        // 消费者：吃 SHINING，配给表给它记一份没销的配额 ⇒ quotaBlind= 才会点名它
        var consumerSlots = new OmniSlot[]{
                new OmniSlot(new GenericStack[]{new GenericStack(SHINING, 1L)}, 1L)};
        var consumer = new FakePattern(AEItemKey.of(Items.GLOWSTONE), consumerSlots,
                List.of(new GenericStack(PRODUCT, 1L)));

        var times = new LinkedHashMap<IPatternDetails, Long>();
        times.put(consumer, 1L);
        times.put(producer, 1L);
        var job = jobWithRation(times, consumer);
        var pool = emptyPool();
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        // noCandWhy= 那一格的原文由<b>真取料腿</b>交出（noteNoCandidate 就是生产侧唯一的写入口）
        var probe = new ParallelBatchExtraction.Probe();
        probe.noteNoCandidate(0, producer.getInputs()[0], pool, null, producer, reservation);
        var otherProbe = new ParallelBatchExtraction.Probe();
        otherProbe.noteNoCandidate(0, twin.getInputs()[0], pool, null, twin, reservation);

        var line = renderStallLine(job, pool, null, probe.noCandKey, probe.noCandStored,
                probe.noCandQuota);
        var noCandTag = firstTag(line.substring(line.indexOf("noCandWhy="),
                line.indexOf(" stored=")), "noCandWhy=");
        var srcTag = firstTag(line.substring(line.indexOf(" quotaBlind=")), "quotaBlind= 的 src=");
        assertEquals(noCandTag, srcTag,
                "同一张样板实例的身份标必须在两格里是同一个数，否则归因还是断在这一跳：" + line);

        // 另一张同名实例必须报出<b>不同</b>的身份标（旧日志里两格字面完全相同，正因此配不上号）
        var twinLine = renderStallLine(job, pool, null, otherProbe.noCandKey,
                otherProbe.noCandStored, otherProbe.noCandQuota);
        var twinTag = firstTag(twinLine.substring(twinLine.indexOf("noCandWhy="),
                twinLine.indexOf(" stored=")), "noCandWhy=（另一张）");
        assertNotEquals(noCandTag, twinTag,
                "同名两张的身份标不许相同（相同就是身份标没长在实例上）：" + twinLine);

        // blocked= 的样板头：同一张 producer 必须交回同一个身份标
        job.batchObligations.incur(producer, BigInteger.ONE);
        var ordered = new java.util.ArrayList<Map.Entry<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>>();
        ordered.add(Map.entry((IPatternDetails) producer, progress(1L)));
        var obligationLine = ParallelCraftingCpuLogic.describeObligationStallLine(
                java.util.UUID.randomUUID(), BigInteger.valueOf(7L), job, ordered,
                pool, reservation, probe, 0L);
        var tokens = List.of(obligationLine.split(" "));
        var blockedCell = tokens.get(tokens.size() - 1);
        assertTrue(blockedCell.startsWith("blocked=<"), "最后一格必须是 blocked=：" + obligationLine);
        assertEquals(noCandTag, firstTag(blockedCell, "blocked= 的样板头"),
                "欠账样板的头与 noCandWhy=/src= 必须是同一张实例：" + obligationLine);
    }

    /**
     * 判据②：样板被退避跳过那一档，{@code backoffBlind=} 印得出 {@code 每发}/{@code 池内}/{@code 网络sim}
     * 三个子字段，整格<b>零空白</b>（带组件键的两个外来空格折成 {@code "_"}）；没样板在退避时不印空壳。
     *
     * <p>改前红在这一格压根不存在：整行里找不出 {@code backoffBlind=} ⇒ 取 token 直接红。
     */
    @Test
    void backoffBlindPrintsTheSkippedPatternsOwnFailingCell() {
        var slots = new OmniSlot[]{new OmniSlot(new GenericStack[]{new GenericStack(WORN, 4L)}, 1L)};
        var skipped = new FakePattern(AEItemKey.of(Items.BARREL), slots,
                List.of(new GenericStack(PRODUCT, 1L)));
        var job = newJob();
        job.tasks.put(skipped, progress(5L));
        // 与 recordMaterialBackoff 同一写法：退避在册 + 当初欠的键（全部候选键）
        job.materialBackoffUntil.put(skipped, 1_000L);
        job.materialBackoffMissing.put(skipped, new java.util.HashSet<>(Set.of(WORN)));
        var pool = emptyPool(); // 池内 0 枚：这一格真付不出每发 4 枚

        var line = renderStallLine(job, pool, gridHolding(WORN, 7L), "", 0L, -2L);
        var cell = firstTokenOf(line, "backoffBlind=");
        assertTrue(cell.contains(",每发=4"), "每发要几枚：与 blocked= 同一道口（SlotAccount.unitsPerFiringExact）："
                + cell);
        assertTrue(cell.contains(",池内=0"), "池里还剩几枚：WideCraftingInventory.amount 那一道口：" + cell);
        assertTrue(cell.contains(",网络sim=7"), "网络自报能交出几枚（至多一个 long，下界读）：" + cell);
        assertTrue(cell.contains("#slot0," + oneToken(WORN) + ","),
                "欠的那一枚键要点了名，且第三方空格在格出口折成 '_';：" + cell);
        assertTrue(cell.contains("#i"), "这一格要能对上 noCandWhy=/src= 点的那一张：" + cell);
        assertFalse(cell.chars().anyMatch(Character::isWhitespace),
                "一格一 token：整格不许含任何空白：" + cell);

        // 反向那一档：没有样板在退避 ⇒ 不许印空壳
        var clean = newJob();
        clean.tasks.put(skipped, progress(5L));
        var cleanCell = firstTokenOf(renderStallLine(clean, pool, null, "", 0L, -2L), "backoffBlind=");
        assertEquals("backoffBlind=<none>", cleanCell,
                "没被跳过就是 <none>，不许摆一个 [] 的空壳；网格不在场那一档照常点名：" + cleanCell);
    }

    /** 走真渲染口交整行（生产侧 {@code AELog.info("%s", …)} 打的就是这一个串）。 */
    private static String renderStallLine(ParallelExecutingCraftingJob job,
            ListCraftingInventory pool, @Nullable IGrid grid, String noCandKey,
            long noCandStored, long noCandQuota) {
        var stallProbe = new ParallelCraftingLaneState(null, java.util.UUID.randomUUID(), 10L)
                .logic().new StallProbe();
        return stallProbe.describeStallLine(java.util.UUID.randomUUID(), 1, job, pool, grid,
                "…", noCandKey, noCandStored, noCandQuota);
    }

    /** 取整行里第一个 {@code #i<hex>}：取不出即红（这一格压根没有身份标）。 */
    private static String firstTag(String segment, String whichCell) {
        var matcher = IDENTITY_TAG.matcher(segment);
        assertTrue(matcher.find(), whichCell + " 里必须带样板身份标 #i<hex>：" + segment);
        return matcher.group(1);
    }

    /** 取整行里以 {@code marker} 开头的那一格（整格零空白才切得出来）。 */
    private static String firstTokenOf(String line, String marker) {
        for (var token : line.split(" ")) {
            if (token.startsWith(marker)) {
                return token;
            }
        }
        throw new AssertionError("整行里没有以 " + marker + " 开头的格：" + line);
    }

    /** 日志出口那把尺：toString 里的空白折成 {@code "_"}（与格出口同一把）。 */
    private static String oneToken(Object key) {
        return String.valueOf(key).replace(' ', '_');
    }

    private static AEItemKey worn(int damage) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }

    private static WideCraftingInventory emptyPool() {
        return new WideCraftingInventory(ignored -> {
        });
    }

    private static ParallelExecutingCraftingJob newJob() {
        var base = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        return new ParallelExecutingCraftingJob(base, ignored -> {
        }, (CraftingLink) null, null);
    }

    /** 提交期配给表（真路：{@code adoptPlannedMaterialPlan}）：消费者在 SHINING 上留一份没销的配额。 */
    private static ParallelExecutingCraftingJob jobWithRation(Map<IPatternDetails, Long> times,
            IPatternDetails consumer) {
        var share = new WideKeyCounter();
        share.add(SHINING, SESSION_QUOTA);
        var material = new IdentityHashMap<IPatternDetails, WideKeyCounter>();
        material.put(consumer, share);
        var base = new CraftingPlan(new GenericStack(CELL, 1L), 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), times);
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                1L, Map.of(), material, null, null);
        var job = new ParallelExecutingCraftingJob(plan, ignored -> {
        }, (CraftingLink) null, null);
        ParallelCraftingCpuLogic.adoptPlannedMaterialPlan(job, plan);
        return job;
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    /** 只问 {@code getStorageService().getInventory().extract(…, SIMULATE, …)} 那一张嘴的网络桩。 */
    private static IGrid gridHolding(AEKey what, long total) {
        var held = new HoldingNetwork(what, total);
        var service = Proxy.newProxyInstance(IStorageService.class.getClassLoader(),
                new Class<?>[]{IStorageService.class},
                (proxy, method, args) -> "getInventory".equals(method.getName())
                        ? held : fallback(method));
        return (IGrid) Proxy.newProxyInstance(IGrid.class.getClassLoader(),
                new Class<?>[]{IGrid.class},
                (proxy, method, args) -> "getStorageService".equals(method.getName())
                        ? service : fallback(method));
    }

    private static Object fallback(java.lang.reflect.Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        return 0;
    }

    /** 持固定枚数的网络桩：SIMULATE 恒交回同一份现量（不把料抽走）。 */
    private static final class HoldingNetwork implements MEStorage {
        private final Map<AEKey, Long> held = new java.util.HashMap<>();

        private HoldingNetwork(AEKey what, long total) {
            held.put(what, total);
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource src) {
            var remaining = held.get(key);
            return remaining == null ? 0L : Math.min(remaining, amount);
        }

        @Override
        public Component getDescription() {
            return Component.literal("holding-network");
        }
    }

    /** 万象／模糊样板那一型：候选表写死，{@code isValid} 按 {@code equals} 认。 */
    private record OmniSlot(GenericStack[] candidates, long multiplier)
            implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            for (var candidate : candidates) {
                if (candidate.what().equals(input)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private record FakePattern(AEItemKey definition, OmniSlot[] slots,
            List<GenericStack> outputs) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots;
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }
    }
}
