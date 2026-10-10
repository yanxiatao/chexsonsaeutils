package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主<b>整批拒收</b>声明批量之后的两条路（实机 lane 09a75f4a 那一型活锁）：
 *
 * <ol>
 *   <li>拒收不许吞掉<b>同一轮</b>的 k 倍 holder 段——旧实现 {@code continue taskLoop}，宿主每轮拒
 *       就每轮什么都不推（{@code declaredReject=99}≈每轮恰一次、{@code declared=0}、
 *       {@code pushed=0}、{@code rem} 连续两窗一字不动）；</li>
 *   <li>拒收那笔退避必须记在<b>真宿主</b>身份上——记在伪装壳上，{@code checkProvider} 拿真方块实体
 *       查询永远查不到（对端 {@code back=0} 恒零即此空转）。</li>
 * </ol>
 *
 * <p>三条 B1 用例各钉一处：① 拒收后 holder 路在本轮可达（判据 {@code DeclaredPush#endsTaskRound}）
 * 且料齐；② 接受仍走申报批（份数与报称账与改前逐字相同，防把 fall through 做成绕开申报批）；
 * ③ 拒收后账面干净（既没算成已占、也没算成已消耗）。
 *
 * <p>夹具沿用 {@link ParallelDeclaredBatchNetworkDeliveryTest} 的 {@code FakePattern} 口径。
 */
final class ParallelDeclaredBatchRejectFallThroughTest {

    private static final long UNITS_PER_FIRING = 1000L;
    private static final long FIRINGS = 5_000L;
    private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private static final AEItemKey MATERIAL = AEItemKey.of(Items.IRON_ORE);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

    private static final FakePattern PATTERN = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(MATERIAL, 1L)},
                    UNITS_PER_FIRING, BUCKET, Set.of(MATERIAL))},
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * 一张"编码过的"样板：{@code getDefinition()} 返回一个<b>非空组件补丁</b>的 {@code AEItemKey}——
     * 实机上带 NBT 的加工／合成样板就是这个形状，{@code AEItemKey#toString} 会写成
     * {@code <id> (with patches)}（含两个空白）。用这一档把"一格一 token"的尺打到真渲染口上。
     */
    private static final AEItemKey PATCHED_DEFINITION = patchedBarrelKey();
    private static final FakePattern PATCHED_PATTERN = new FakePattern(
            PATCHED_DEFINITION,
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(MATERIAL, 1L)},
                    UNITS_PER_FIRING, BUCKET, Set.of(MATERIAL))},
            List.of(new GenericStack(PRODUCT, 1)));

    private static AEItemKey patchedBarrelKey() {
        // 带 damage（DataComponents.DAMAGE） ⇒ isComponentsPatchEmpty() 为假 ⇒ toString 追加 " (with patches)"。
        // IRON_PICKAXE 是可损伤件，damage=1 就是最小可用的组件补丁（BARREL 无耐久不能这样造）。
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(1);
        return AEItemKey.of(stack);
    }

    private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
    private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

    private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), NO_PLAN, NO_TAKEN);

    /**
     * ① 宿主整批拒收 ⇒ 这张样板<b>本轮不收工</b>，且回池后的料够 holder 段再提一次。
     *
     * <p>旧实现红在 {@code endsTaskRound()} 把 {@code rejected} 也算成收工（循环里那一支
     * {@code continue taskLoop}），于是 holder 段一次都走不到，实机 {@code pushed=0}。
     */
    @Test
    void wholeBatchRejectionKeepsTheHolderRouteOpenInTheSameRound() {
        var job = newJob();
        job.tasks.put(PATTERN, progress(FIRINGS));
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new ShellHost("overworld@1,2,3");
        host.rejectReason = "capacity#overworld@1,2,3";
        var probe = new ParallelBatchExtraction.Probe();

        var push = attempt(job, pool, host, probe, null, 10L);

        assertNotNull(push);
        assertTrue(push.rejected(), "宿主整批拒收");
        assertEquals(0L, push.accepted());
        assertFalse(push.endsTaskRound(),
                "拒收不许收工：同轮还要落到既有的 k 倍 holder 段");
        assertEquals(FIRINGS * UNITS_PER_FIRING, pool.extract(MATERIAL, Long.MAX_VALUE, Actionable.SIMULATE),
                "原型料原数在池里：holder 段这一轮提得出料才谈得上推得出去");
        assertTrue(job.accountTaken.isEmpty(), "拒收一分料没销账（不许占料/遮池双记）");
        assertEquals(0, job.batchObligations.pendingFirings(PATTERN).signum(),
                "拒收不挂义务账：holder 段推出去的份数不会被欠账再遮一遍");
        assertEquals(0L, ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                        job.tasks.entrySet(), pool, RESERVATION_FREE)
                .getExact(MATERIAL).longValue(),
                "池子里那一截没人欠账遮着");
        var cell = PATTERN.getDefinition() + "@ShellHost#capacity#overworld@1,2,3";
        assertEquals(cell, firstCell(probe), "拒收点名格：样板@宿主#机器出口");
        assertEquals(1L, (long) probe.declaredRejectCells.get(cell), "这一 tick 只拒了一次");
        assertEquals(1L, probe.declaredRejected, "聚合数与点名格同源");
    }

    /**
     * ② 宿主接受 ⇒ 走的<b>仍是申报批</b>：实收份数、报称产出账、义务欠账与改前逐字相同，
     * 且这一张样板本轮收工（不许被 fall through 绕开申报批再去 holder 段推一次）。
     */
    @Test
    void acceptedBatchStillSettlesOnTheDeclaredRouteAndClosesTheRound() {
        var job = newJob();
        job.tasks.put(PATTERN, progress(FIRINGS));
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new ShellHost("overworld@1,2,3");
        host.accepts = true;
        var expectedOutputs = new KeyCounter();
        var expectedContainers = new KeyCounter();
        var probe = new ParallelBatchExtraction.Probe();

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, PATTERN, job,
                job.tasks.entrySet(), pool, null, RESERVATION_FREE, new ParallelPoolLedger(),
                expectedOutputs, expectedContainers, Long.MAX_VALUE, Long.MAX_VALUE, probe,
                null, null, null, 10L);

        assertNotNull(push);
        assertFalse(push.rejected());
        assertEquals(FIRINGS, push.accepted(), "实收＝申报：申报批这条腿一分没少");
        assertTrue(push.endsTaskRound(), "申报批已结算：本轮收工，不再走 holder 段");
        assertEquals(FIRINGS, job.accountAnnounced.getLong(PRODUCT), "报称产出流水账按全额记（与改前同值）");
        assertEquals(FIRINGS, expectedOutputs.get(PRODUCT), "预期产出按申报份数记");
        assertEquals(FIRINGS - 1L, job.batchObligations.pendingFirings(PATTERN).longValue(),
                "剩下 (k-1) 份挂义务账：申报批的收益不被 fall through 换掉");
        assertEquals(1L, probe.declaredPushes, "申报批计数前进");
        assertEquals(0L, probe.declaredRejected, "接受那一支不许记进拒收账");
    }

    /**
     * ③ 拒收且 holder 也推不动（机器真不接）⇒ 回池后<b>账面干净</b>：池里那一截既不计为已占、
     * 也不计为已消耗。
     *
     * <p>旧实现红在把 {@code attemptDeclaredBatch} 拒收分支里的 {@code reinjectInputs} 摘掉：
     * 料不回池，holder 段这一轮就少一发原型，而占料账与挂账一概没记它。
     */
    @Test
    void rejectionThatTheHolderAlsoRefusesLeavesThePoolAndClaimsClean() {
        var job = newJob();
        job.tasks.put(PATTERN, progress(FIRINGS));
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new ShellHost("overworld@1,2,3");
        var before = pool.extract(MATERIAL, Long.MAX_VALUE, Actionable.SIMULATE);

        var push = attempt(job, pool, host, new ParallelBatchExtraction.Probe(), null, 20L);

        assertNotNull(push);
        assertTrue(push.rejected());
        assertEquals(before, pool.extract(MATERIAL, Long.MAX_VALUE, Actionable.SIMULATE),
                "整批拒收后池子逐枚回到原位：holder 段推不动也不欠任何一截");
        assertTrue(job.waitingFor.list.isEmpty(), "没推出去就不挂任何实物预期");
        assertTrue(job.accountTaken.isEmpty(), "不计为已消耗");
        assertEquals(0, job.batchObligations.pendingFirings(PATTERN).signum());
        assertEquals(0L, ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                        job.tasks.entrySet(), pool, RESERVATION_FREE)
                .getExact(MATERIAL).longValue(), "不计为已占");
    }

    /**
     * B2：拒收那笔退避记在<b>真 provider</b> 上，与 {@code checkProvider} 查询同一身份。
     *
     * <p>旧实现红在 key 用壳对象：真宿主下一窗照样 READY（对端 {@code back=0} 恒零），
     * 于是每轮都对同一台拒收的机器伸手。
     */
    @Test
    void rejectionBacksOffTheRealProviderAndNotTheShell() {
        var job = newJob();
        job.tasks.put(PATTERN, progress(FIRINGS));
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var realProvider = new ShellHost("real");
        var host = new ShellHost("overworld@1,2,3");
        host.backoffOwner = realProvider;
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        assertNotNull(attempt(job, pool, host, new ParallelBatchExtraction.Probe(), backoff, 10L));

        assertEquals(ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                backoff.checkProvider(realProvider, PATTERN, ParallelCpuProviderBackoff.PushLeg.DECLARED, 11L),
                "下一窗不再对真宿主伸手：退避记在它身上");
        assertEquals(ParallelCpuProviderBackoff.ProviderAvailability.READY,
                backoff.checkProvider(host, PATTERN, ParallelCpuProviderBackoff.PushLeg.DECLARED, 11L),
                "壳不是网格 provider：账上不许有它一个");
        assertEquals(1, backoff.trackedProviderCount(), "只多出一个真身份");
    }

    /**
     * F1-①：申报腿那一笔拒收<b>不许堵同轮的 holder 腿</b>——同一 tick、同一真 provider、同一张样板。
     *
     * <p>旧实现红在这里判成 {@code BACKED_OFF}：B2 把拒收记进 {@code (真 provider, 样板)} 那一桶，
     * 而 {@code executeCrafting} 的 k 倍 holder 段下一行问的就是同一个桶，于是只有一个 provider 的
     * lane（实机 lane 09a75f4a 那一型）连 holder 路也推不动 ⇒ {@code pushed=0}。
     *
     * <p>端到端那一格（{@code executeCrafting} 里真去推一次）测试树没有 MC bootstrap，跑不起来，
     * 仍未覆盖；下一跑靠实机 {@code pushed=} 判。
     */
    @Test
    void declaredRejectionLeavesTheHolderLegOpenInTheSameTick() {
        var job = newJob();
        job.tasks.put(PATTERN, progress(FIRINGS));
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var realProvider = new ShellHost("real");
        var host = new ShellHost("overworld@1,2,3");
        host.backoffOwner = realProvider;
        var backoff = new ParallelCpuProviderBackoff(2, 40);

        assertNotNull(attempt(job, pool, host, new ParallelBatchExtraction.Probe(), backoff, 10L));

        assertEquals(ParallelCpuProviderBackoff.ProviderAvailability.READY,
                ParallelCraftingCpuLogic.checkProviderAvailability(realProvider, PATTERN, 10L, backoff,
                        ParallelCpuProviderBackoff.PushLeg.HOLDER),
                "同 tick 的 holder 腿必须还开着：申报腿的拒收不许把它一起堵死");
        assertEquals(ParallelCpuProviderBackoff.ProviderAvailability.BACKED_OFF,
                ParallelCraftingCpuLogic.checkProviderAvailability(realProvider, PATTERN, 10L, backoff,
                        ParallelCpuProviderBackoff.PushLeg.DECLARED),
                "申报腿那一桶照旧记着这一笔（分桶不是抹账）");
    }

    /**
     * R1 的<b>渲染点</b>那一格：拒收点名不许只在"直调静态格式化口"上自证。
     *
     * <p>这里让 {@code attemptDeclaredBatch} 真拒一次（点名格由 {@code describeDeclaredReject} 现造，
     * 不是测试手写），再把同一份 {@code Probe} 喂满 100 轮，断言的是
     * {@code StallProbe#noteBatch} 真打进日志的那一行 {@code per100r}：格式化<b>被真的调用过</b>、
     * 位置在 {@code declaredReject=} 那一格、整格不含空白（一格一 token）。
     *
     * <p>同一档还要过一遍<b>带数据组件</b>的样板（{@code AEItemKey#toString} 会写成
     * {@code <id> (with patches)}——含两个空白）：编码后的加工／合成样板在实机上就是这个形状，
     * 若点名格直接 {@code getDefinition()} 拼串，那一格按空白切就成了三段，
     * "一格一 token"这条既有尺就在真样本上失效。
     */
    @Test
    void per100rRenderSitePrintsTheNamedRejectCellAsOneToken() {
        var plain = renderNamedRejectCell(PATTERN, "capacity#overworld@1,2,3");
        assertEquals(
                "declaredReject=100/[" + PATTERN.getDefinition() + "@ShellHost#capacity#overworld@1,2,3=100]",
                firstTokenOf(plain, "declaredReject="),
                "无组件的样板：整格一 token。日志行=" + plain);

        var patched = renderNamedRejectCell(PATCHED_PATTERN, "capacity#overworld@1,2,3");
        // 无 fix 时：AEItemKey 的 toString 带 " (with patches)" ⇒ 整格按空白切成三段，
        // 第一个 token 只截到 "declaredReject=100/[minecraft:iron_pickaxe"——这一断言就是变红的入口。
        var tokens = List.of(patched.split(" "));
        var idx = firstTokenIndexStartingWith(tokens, "declaredReject=");
        var token = tokens.get(idx);
        assertEquals(
                "declaredReject=100/[minecraft:iron_pickaxe_(with_patches)@ShellHost#capacity#overworld@1,2,3=100]",
                token,
                "带组件的样板：点名格不许引入空白，整串仍是一 token。日志行=" + patched);
        // 可辨识度不许丢：折空白成 "_" 之后仍认得出是哪张样板（item id + 组件标记）。
        assertTrue(token.contains("minecraft:iron_pickaxe"), token);
        // 硬判据①（原"contains(with_patches) 或 contains((with_patches))"是同义反复，删）：
        // 整格不含<b>任何</b>空白——split(" ") 只切 ASCII 空格，tab 一类杂空白归这条判据抓。
        assertFalse(token.chars().anyMatch(Character::isWhitespace),
                "declaredReject= 那一格不许含任何空白：" + token);
        // 硬判据②（原"all.contains(token)"由 firstTokenOf 自己的定义必真，删）：
        // 整行按空白切出的 token 数在这里＝期望格数 1——点名格之后紧挨着的必须是下一格字段
        // obBatch=；格内漏出任何一处空白，紧挨着的下一段就是格碎片（实机形如 "(with"）而不是它。
        assertEquals("obBatch=0", tokens.get(idx + 1),
                "declaredReject= 一格按空白切恰占一段（期望格数=1）：日志行=" + patched);
    }

    /** 走真渲染口，把 {@code per100r} 那一行原文取回来。 */
    private static String renderNamedRejectCell(FakePattern pattern, String rejectReason) {
        var job = newJob();
        job.tasks.put(pattern, progress(FIRINGS));
        var pool = pool(FIRINGS * UNITS_PER_FIRING);
        var host = new ShellHost("overworld@1,2,3");
        host.rejectReason = rejectReason;
        var probe = new ParallelBatchExtraction.Probe();
        assertNotNull(ParallelCraftingCpuLogic.attemptDeclaredBatch(host, pattern, job,
                job.tasks.entrySet(), pool, null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, probe,
                null, null, null, 10L), "申报腿真拒一次");
        return renderPer100rLine(probe, pool);
    }

    /**
     * 走真渲染口：把这份 {@code Probe} 喂满 100 轮，取回 {@code per100r} 那一行原文。
     *
     * <p>不直调 {@code StallProbe} 的静态格式化方法——那正是本用例要证的"被调用"。
     */
    private static String renderPer100rLine(
            ParallelBatchExtraction.Probe probe, ListCraftingInventory pool) {
        var appender = new CapturingAppender();
        var context = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        try {
            appender.start();
            configuration.addAppender(appender);
            configuration.getRootLogger()
                    .addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
            context.updateLoggers();

            var stallProbe = new ParallelCraftingLaneState(ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster(), java.util.UUID.randomUUID(), 10L)
                    .logic().new StallProbe();
            // 这一条判据只问 declaredReject= 那一格：在飞那两格取"没挂上任何一笔"那一档的真读数
            var idleTerminals = newJob().declaredTerminals;
            for (int round = 0; round < 100; round++) {
                stallProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                        probe, new java.util.HashMap<>(), pool, idleTerminals, 0L);
            }
            return appender.lastLine("[chexsonsaeutils-batch] per100r");
        } finally {
            configuration.getRootLogger().removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        }
    }

    /** 取日志行里以 {@code marker} 开头的那一个空白分隔 token（没有就是 {@code <missing>}）。 */
    private static String firstTokenOf(String line, String marker) {
        for (var token : line.split(" ")) {
            if (token.startsWith(marker)) {
                return token;
            }
        }
        return "<missing>";
    }

    /** {@code marker} 开头那一格在空白 token 表里的下标；找不到直接红，不许把缺失喂给后续断言。 */
    private static int firstTokenIndexStartingWith(List<String> tokens, String marker) {
        for (var i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).startsWith(marker)) {
                return i;
            }
        }
        throw new AssertionError("整行里没有以 " + marker + " 开头的格：" + tokens);
    }

    /** 只收 {@code INFO} 及以上的原文行：渲染点的产物除了日志没有第二个出口。 */
    private static final class CapturingAppender extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("declared-reject-per100r-capture", null,
                    org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            var message = event.getMessage();
            if (message != null) {
                lines.add(message.getFormattedMessage());
            }
        }

        private String lastLine(String prefix) {
            String found = "<no log line captured with prefix " + prefix + ">";
            for (var line : lines) {
                if (line.startsWith(prefix)) {
                    found = line;
                }
            }
            return found;
        }
    }

    private static ParallelCraftingCpuLogic.DeclaredPush attempt(
            ParallelExecutingCraftingJob job, ListCraftingInventory pool, DeclaredBatchHost host,
            @Nullable ParallelBatchExtraction.Probe probe,
            @Nullable ParallelCpuProviderBackoff backoff, long currentTick) {
        return ParallelCraftingCpuLogic.attemptDeclaredBatch(host, PATTERN, job,
                job.tasks.entrySet(), pool, null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, probe,
                null, backoff, null, currentTick);
    }

    private static String firstCell(ParallelBatchExtraction.Probe probe) {
        return probe.declaredRejectCells.keySet().iterator().next();
    }

    private static ParallelExecutingCraftingJob newJob() {
        return new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    private static ICraftingPlan emptyPlan() {
        return new ICraftingPlan() {
            private final KeyCounter empty = new KeyCounter();

            @Override
            public GenericStack finalOutput() {
                return null;
            }

            @Override
            public long bytes() {
                return 0L;
            }

            @Override
            public boolean simulation() {
                return false;
            }

            @Override
            public boolean multiplePaths() {
                return false;
            }

            @Override
            public KeyCounter usedItems() {
                return empty;
            }

            @Override
            public KeyCounter emittedItems() {
                return empty;
            }

            @Override
            public KeyCounter missingItems() {
                return empty;
            }

            @Override
            public Map<IPatternDetails, Long> patternTimes() {
                return Map.of();
            }
        };
    }

    private static ListCraftingInventory pool(long units) {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(MATERIAL, units, Actionable.MODULATE);
        return inventory;
    }

    /**
     * 伪装壳那种宿主：本壳 {@link #declaredBatchBackoffOwner()} 交回<b>另一个</b>真 provider，
     * 于是"退避记在谁身上"这一件事在本用例里看得见、也分得开。
     */
    private static final class ShellHost implements DeclaredBatchHost {

        private boolean accepts;
        private @Nullable String rejectReason;
        private @Nullable ICraftingProvider backoffOwner;

        private ShellHost(String identity) {
            // 真壳的机器身份由 rejectReason 那一句带出来；这里的形参只给读用例的人指认是哪一台。
        }

        @Override
        public boolean supportsDeclaredBatch(IPatternDetails pattern) {
            return true;
        }

        @Override
        public BigInteger declaredBatchCapacity(IPatternDetails pattern) {
            return BIG_LONG;
        }

        @Override
        public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger declaredExecutions) {
            return accepts ? declaredExecutions : BigInteger.ZERO;
        }

        @Override
        public @Nullable String lastDeclaredBatchRejectReason() {
            return rejectReason;
        }

        @Override
        public ICraftingProvider declaredBatchBackoffOwner() {
            return backoffOwner == null ? this : backoffOwner;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return false;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    private record FakeInput(GenericStack[] candidates, long multiplier,
            @Nullable AEKey remainder, Set<AEKey> accepts) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return candidates.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return accepts.contains(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return remainder;
        }
    }

    private record FakePattern(AEItemKey definition, FakeInput[] slots,
            List<GenericStack> outputs) implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return slots.clone();
        }

        @Override
        public List<GenericStack> getOutputs() {
            return outputs;
        }
    }
}
