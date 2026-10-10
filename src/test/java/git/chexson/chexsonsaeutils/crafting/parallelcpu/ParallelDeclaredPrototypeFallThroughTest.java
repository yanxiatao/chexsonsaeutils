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
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 申报腿的<b>原型那一趟</b>凑不齐一发时，不许把同轮的 k 倍 holder 段一起吞掉。
 *
 * <p>两把尺本来就答两个不同的问题（本文件判过"要不要合成一把"，结论是留两把，见判据 2 的注释）：
 * <ul>
 *   <li>{@link ParallelBatchExtraction#declaredPayableCeiling} —— <b>上界估算</b>：这一份池子总共
 *       撑得起几发。每一格按<b>自己的</b>每发件数独立除，同槽多个候选相加，跨槽同键<b>不</b>累加；</li>
 *   <li>{@code ParallelBatchExtraction#primaryCandidate}（原型腿与 holder 段共用的取料点）——
 *       <b>真正伸手的那一趟</b>：本批这几发要按"该键在<b>已选槽</b>上的每发合计"当分母，
 *       每槽只认一个键。</li>
 * </ul>
 *
 * <p>两把尺分叉的那一档（判据 1 的形状）：payable 报得出 1 发 ⇒ 申报腿继续走，原型腿却交不出 ⇒
 * 旧实现交 {@code new DeclaredPush(..., extractFailed=true)} ⇒ {@code endsTaskRound()} 为真 ⇒
 * 同轮 holder 段被跳过。而 holder 段在 {@code prepareUniform} 失败之后还有
 * {@code ParallelBatchExtraction#prepareSingleFiring} 的<b>逐槽混取</b>窗口：同一份池子里
 * 那一发本可以推出去（3 枚走槽0 的独苗键 + 2 枚首位 + 1 枚备选＝混取凑得齐，原型腿"每槽一键"凑不齐）。
 *
 * <p>判据（本仓定版口径：总份数只出自规划，本批推几发由现场量决定，能推就推）：
 * <ol>
 *   <li>真链一测：跨槽同键、payable 报 1 发而原型腿交不出 ⇒ 本轮<b>不收工</b>（同轮 holder 段被走到），
 *       并且那一发真能从混取窗口推出去；申报腿这一趟不许留下半挂状态（料回位、义务账不挂、
 *       占料账不销、总份数一分不减）。</li>
 *   <li>钉住分叉本身：同一份池量下 {@code declaredPayableCeiling} 与取料点对<b>本批发数</b>的取值，
 *       按独立手算表逐档写死（期望值不出自被测函数）。</li>
 * </ol>
 *
 * <p>接缝就是真接缝：{@code attemptDeclaredBatch}／{@code prepareUnitPrototype}／{@code prepare}
 * 都是 {@code executeCrafting} 推送循环各自调用的那一个静态方法，参数也与循环同构
 * （holder 段在没有 k 倍宿主在册时 {@code uniformBatchLimit} 就是 1 发）。
 * {@code executeCrafting} 本体要 MC bootstrap，测试树跑不起来，仍未覆盖，实机判 {@code pushed=}。
 */
final class ParallelDeclaredPrototypeFallThroughTest {

    private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    /** 两个槽共用的那一个键（槽0 的独苗候选、槽1 的首位候选）。 */
    private static final AEItemKey SHARED = AEItemKey.of(Items.IRON_INGOT);
    /** 只有槽1 认得的备选键：不同物品本体，避免 AE2 模糊索引把两键并成一格。 */
    private static final AEItemKey ALT = AEItemKey.of(Items.COPPER_INGOT);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.BARREL);

    /** 每槽每发 3 枚（倍率 3 × 模板量 1），两槽共用 {@link #SHARED} ⇒ 跨槽同键每发合计 6 枚。 */
    private static final long MULTIPLIER = 3L;

    /**
     * 跨槽同键那一型：槽0 只有 {@code SHARED}，槽1 有 {@code SHARED → ALT} 两位候选。
     * 一发真需 3 枚 SHARED（槽0）+ 3 枚（槽1，可混取）。
     */
    private static final FakePattern TWIN_SHARED_KEY = new FakePattern(
            AEItemKey.of(Items.CHEST),
            new FakeInput[]{
                    new FakeInput(new GenericStack[]{new GenericStack(SHARED, 1L)},
                            MULTIPLIER, null, Set.of(SHARED)),
                    new FakeInput(new GenericStack[]{new GenericStack(SHARED, 1L),
                            new GenericStack(ALT, 1L)}, MULTIPLIER, null, Set.of(SHARED, ALT))
            },
            List.of(new GenericStack(PRODUCT, 1)));

    private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
    private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

    /** 没有别的任务占料、也没有提交期配给：账本存在也不改取料口径。 */
    private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), NO_PLAN, NO_TAKEN);

    /** 规划定版本张 5 发（总份数只出自规划）。 */
    private static final long PLANNED_FIRINGS = 5L;

    /**
     * 判据 1（真链）：payable 报 1 发、原型腿交不出 ⇒ 本轮不许收工，同轮 holder 段把那一发推出去。
     *
     * <p>未修树红在 {@code holderLegOpenInThisRound} 那一句：旧实现交回
     * {@code DeclaredPush(accepted=0, extractFailed=true)}，{@code endsTaskRound()} 为真，
     * 循环里的 {@code continue taskLoop} 把 holder 段整段吞掉。
     */
    @Test
    void prototypeShortfallLeavesTheHolderLegOpenInTheSameRoundAndThatFiringGoesOut() {
        var job = newJob();
        var task = progress(PLANNED_FIRINGS);
        job.tasks.put(TWIN_SHARED_KEY, task);
        // 手算：SHARED 5 枚 + ALT 1 枚 = 6 枚 = 正好一发，但那 6 枚分在三个键次上
        var pool = pool(5L, 1L);
        var probe = new ParallelBatchExtraction.Probe();

        // 上界那把尺：槽0 5÷3=1、槽1 5÷3 + 1÷3 = 1 ⇒ payable=1 ⇒ 申报腿继续走
        assertEquals(new BigInteger("1"), ParallelBatchExtraction.declaredPayableCeiling(
                TWIN_SHARED_KEY, pool, null, RESERVATION_FREE, BIG_LONG),
                "上界尺：每格独立除、跨槽同键不累加 ⇒ 报得出 1 发");

        // 原型腿（每槽一键、分母含跨槽累加）：槽1 分母 3+3=6 > 5，备选 ALT 只有 1 枚 < 3 ⇒ 交不出
        assertNull(ParallelBatchExtraction.prepareUnitPrototype(TWIN_SHARED_KEY, pool, null,
                new KeyCounter(), RESERVATION_FREE, probe),
                "原型腿：这一趟凑不齐一发（与 payable 的 1 发正是分叉的那一档）");
        assertEquals(5L, pool.extract(SHARED, Long.MAX_VALUE, Actionable.SIMULATE),
                "原型腿交不出时池子逐枚回到原位：不许把料丢在池外");
        assertEquals(1L, pool.extract(ALT, Long.MAX_VALUE, Actionable.SIMULATE), "备选键一枚没动");

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(new RejectingHost(),
                TWIN_SHARED_KEY, job, job.tasks.entrySet(), pool, null, RESERVATION_FREE,
                new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(), Long.MAX_VALUE,
                Long.MAX_VALUE, probe, null, null, null, 10L);

        // 这一句就是推送循环里的出口判据（循环只在 push!=null && endsTaskRound() 时跳过 holder 段）
        boolean holderLegOpenInThisRound = push == null || !push.endsTaskRound();
        assertTrue(holderLegOpenInThisRound,
                "原型腿凑不齐一发不许吞掉同轮的 k 倍 holder 段：与 declared==null 同一条出口交回 null");

        // 交回 null 那一趟不留半挂状态：义务账没 incur、占料账没销、总份数一分不减
        assertTrue(job.batchObligations.pendingFirings(TWIN_SHARED_KEY).signum() == 0,
                "申报腿这一趟没挂义务账：holder 段推出去的份数不会再被欠账遮一遍");
        assertTrue(job.accountTaken.isEmpty(), "没推出去就不许销账占料");
        assertEquals(PLANNED_FIRINGS, task.exactRemaining().exactValue().longValueExact(),
                "总份数只出自规划：本批推几发不改它");

        // 同轮 holder 段：与循环同构（没有 k 倍宿主在册时 uniformBatchLimit 就是 1 发）
        var expectedOutputs = new KeyCounter();
        var expectedContainers = new KeyCounter();
        var batch = ParallelBatchExtraction.prepare(TWIN_SHARED_KEY, pool, null, 1L,
                expectedOutputs, expectedContainers, RESERVATION_FREE, probe);
        assertNotNull(batch, "holder 段的逐槽混取窗口本可以推 1 发：这一轮不是整轮空转");
        assertEquals(1L, batch.executions(), "本批推 1 发");
        assertEquals(3L, batch.holder()[0].get(SHARED), "槽0 独苗键 3 枚");
        assertEquals(2L, batch.holder()[1].get(SHARED), "槽1 首位键吃满剩下的 2 枚");
        assertEquals(1L, batch.holder()[1].get(ALT), "槽1 退到备选键补最后 1 枚");
        assertTrue(probe.uniformFallback >= 1L, "混取窗口是被走到的那一条腿（prepareUniform 先交白卷）");

        // 那一发真交得出去：与循环里 provider.pushPattern 同一次调用形状
        var provider = new RecordingProvider();
        assertTrue(provider.pushPattern(TWIN_SHARED_KEY, batch.holder()), "宿主收下这一发的输入向量");
        assertEquals(1, provider.pushes);
        assertEquals(java.util.Arrays.deepToString(batch.holder()),
                java.util.Arrays.deepToString(provider.lastHolder));
        assertEquals(0L, pool.extract(SHARED, Long.MAX_VALUE, Actionable.SIMULATE),
                "6 枚 SHARED 全付出去：槽0 的 3 枚 + 槽1 的 2 枚 + 剩下的 1 枚在 ALT 那一位候选上");
        assertEquals(0L, pool.extract(ALT, Long.MAX_VALUE, Actionable.SIMULATE), "ALT 1 枚付出去");
    }

    /**
     * 判据 2（分叉本身）：同一份池量下，两把尺对<b>本批发数</b>的取值按手算表逐档钉死。
     *
     * <p>手算表（每槽倍率 3、模板量 1 ⇒ 槽0 每发 3 枚 SHARED；槽1 每发 3 枚、候选 SHARED→ALT；
     * 跨槽同键 SHARED 每发合计 6 枚）：
     * <table border="1">
     *   <tr><th>池 SHARED</th><th>池 ALT</th><th>上界尺 payable（手算）</th>
     *       <th>原型腿（手算）</th><th>holder 取料点（手算）</th></tr>
     *   <tr><td>5</td><td>1</td><td>min(5÷3, 5÷3+1÷3) = 1</td>
     *       <td>槽1 分母 6&gt;5、ALT 1&lt;3 ⇒ 0 发</td><td>混取 3+2+1 ⇒ 1 发</td></tr>
     *   <tr><td>6</td><td>0</td><td>min(6÷3, 6÷3) = 2</td>
     *       <td>槽0 6÷3=2、槽1 6÷6=1 ⇒ 1 发</td><td>min(1, 6÷3), min(1, 6÷6) ⇒ 1 发</td></tr>
     *   <tr><td>4</td><td>0</td><td>min(4÷3, 4÷3) = 1</td>
     *       <td>槽1 分母 6&gt;4 ⇒ 0 发</td><td>混取最多 3+1=4 枚，差 2 枚 ⇒ 0 发</td></tr>
     * </table>
     *
     * <p><b>为什么判"留两把"而不是"合成一把"</b>：上界那把尺答的是"这一份池子总共撑得起几发"，
     * 它必须把<b>同一槽的多个候选相加</b>（同族两键各 5e17 枚申报 1e15 发那一型，由
     * {@code ParallelDeclaredBatchObligationTest#declaredFiringsAreNotCappedByWhatOneKeyHolderCanCarry}
     * 钉着），也<b>必须</b>允许申报超出当场能凑齐的发数——多报的那几发挂在义务账上分批从网络交割，
     * 这正是"份数归规划、本批归现场"那口径的另一半。取料点那把尺答的是"这一趟每槽只伸一个键、
     * 凑不凑得齐一整发"，把它的"每槽一键"塞回上界尺就会把那一条既有判据打死，把上界算成
     * holder 上限（这一刀要拆的夹子）。所以两把尺各有各的 scope、各自全仓一处实现；
     * 分叉的代价由"申报腿交不出即退回既有取料腿"来吸收，不在两条腿上各打补丁。
     * 表里每一档都满足同一条关系：<b>上界尺 ≥ 取料点本批发数</b>。
     */
    @Test
    void payableCeilingAndExtractionPointReadingsMatchTheHandComputedTable() {
        // 档 1：跨槽同键把原型腿判死、混取窗口救得回来（判据 1 那一型）
        assertRulerRow(5L, 1L, new BigInteger("1"), 0L, 1L);
        // 档 2：两把尺都说这一轮推得出发（上界 2 发、取料点本批 1 发，差额走义务/下一批）
        assertRulerRow(6L, 0L, new BigInteger("2"), 1L, 1L);
        // 档 3：真缺料 —— 上界仍报 1 发，两条腿都交不出，回补腿接手（不许凭空造料）
        assertRulerRow(4L, 0L, new BigInteger("1"), 0L, 0L);
    }

    /**
     * 一行手算表：同一份池量分别喂三处读数，期望值全部来自上面那张手算表的字面量。
     *
     * @param expectedCeiling     上界尺 {@code declaredPayableCeiling}（BigInteger）
     * @param expectedPrototype   原型腿本批发数，0 即 {@code prepareUnitPrototype} 交白卷
     * @param expectedHolderPoint holder 取料点本批发数，0 即 {@code prepare} 交白卷
     */
    private static void assertRulerRow(long sharedUnits, long altUnits,
            BigInteger expectedCeiling, long expectedPrototype, long expectedHolderPoint) {
        var probe = new ParallelBatchExtraction.Probe();

        assertEquals(expectedCeiling, ParallelBatchExtraction.declaredPayableCeiling(
                TWIN_SHARED_KEY, pool(sharedUnits, altUnits), null, RESERVATION_FREE, BIG_LONG),
                "上界尺读数（手算）shared=" + sharedUnits + " alt=" + altUnits);

        var prototypePool = pool(sharedUnits, altUnits);
        var prototype = ParallelBatchExtraction.prepareUnitPrototype(TWIN_SHARED_KEY, prototypePool,
                null, new KeyCounter(), RESERVATION_FREE, probe);
        assertEquals(expectedPrototype, prototype == null ? 0L : prototype.executions(),
                "原型腿本批发数（手算）shared=" + sharedUnits + " alt=" + altUnits);

        var holderPool = pool(sharedUnits, altUnits);
        var holder = ParallelBatchExtraction.prepare(TWIN_SHARED_KEY, holderPool, null, 1L,
                new KeyCounter(), new KeyCounter(), RESERVATION_FREE, probe);
        assertEquals(expectedHolderPoint, holder == null ? 0L : holder.executions(),
                "holder 取料点本批发数（手算）shared=" + sharedUnits + " alt=" + altUnits);

        // 两把尺的取值关系一处说死：上界只许 ≥ 真正伸手那一个
        assertTrue(expectedCeiling.compareTo(BigInteger.valueOf(Math.max(expectedPrototype,
                        expectedHolderPoint))) >= 0,
                "上界尺不许比取料点还窄：shared=" + sharedUnits + " alt=" + altUnits);
    }

    // ==================================================================================
    // 夹具
    // ==================================================================================

    /** 收料用的供应器：只记输入向量，永远接。 */
    private static final class RecordingProvider implements ICraftingProvider {
        private KeyCounter[] lastHolder;
        private int pushes;

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            pushes++;
            lastHolder = inputHolder;
            return true;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    /**
     * 本用例的宿主在原型腿之后压根不该被伸手（原型交白卷），所以它一律拒收：
     * 一旦实现把伸手顺序写错，申报就会以"整批拒收"的形态变红，而不是悄悄推出去。
     */
    private static final class RejectingHost implements DeclaredBatchHost {
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
            return BigInteger.ZERO;
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

    private static ListCraftingInventory pool(long sharedUnits, long altUnits) {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        if (sharedUnits > 0L) {
            inventory.insert(SHARED, sharedUnits, Actionable.MODULATE);
        }
        if (altUnits > 0L) {
            inventory.insert(ALT, altUnits, Actionable.MODULATE);
        }
        return inventory;
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
