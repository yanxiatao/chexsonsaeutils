package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 份数声明 + 单发原型的批量形态：一次交互推进几发不再被"k 倍料装不装得进一个
 * {@code KeyCounter[]}"夹死，剩下 (k-1) 份材料由义务账分批从池子里提走并照旧销账。
 *
 * <p>三条底线在这里各自有用例钉住：
 * <ul>
 *   <li>申报份数只由宿主真实能力、可表示性与现场可付量决定，不再由单键 holder 上限决定；</li>
 *   <li>分批交割的合计与同一 (details, k, 存量) 下 k 倍 holder 路径逐键相等（可表示区间内）；</li>
 *   <li>宿主拒收即整批作废：料回池、任务表不动、义务账不挂账。</li>
 * </ul>
 *
 * <p>交割那一侧再补一条：内部缓冲不够时<b>分批从网络回补</b>（每批每键 ≤ long），欠账能清到零；
 * 网络也没料就维持停滞语义（欠账保留、不判完成、告警闸口照旧），且回补量夹在这张样板自己的配给里。
 * 销账出口仍然只有一个 {@code recordTakenInputs}。
 *
 * <p>夹具沿用 {@link ParallelBatchExtractionTest} 的 {@code FakePattern} 口径，本文件不起第二套。
 */
final class ParallelDeclaredBatchObligationTest {

    /** 一万枚一发：单键存量 5e17 枚 ⇒ k 倍 holder 一批最多 5e14 发，份数被"一次装多少料"夹住。 */
    private static final long UNITS_PER_FIRING = 1000L;
    private static final BigInteger BIG_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private static AEItemKey wornKey(int damage) {
        var stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(damage);
        return AEItemKey.of(stack);
    }

    /** 同族（同一物品本体）两个候选变体：受损的排在全新之前，与 AE2 的顺位一致。 */
    private static final AEItemKey V1 = wornKey(50);
    private static final AEItemKey V2 = wornKey(0);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);

    /** 单槽每发 1000 枚、两位候选、一发产 1 枚、每发回流一只桶。 */
    private static final FakePattern THOUSAND_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, 1L), new GenericStack(V2, 1L)},
                    UNITS_PER_FIRING, BUCKET, Set.of(V1, V2))},
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * 另一张吃<b>同一族同一格</b>料的样板：用来问"上一张申报批欠着的料，这一张看没看见"。
     * 输出换一件物品，两张样板因此不会被任何按产出键反查的表并成一张。
     */
    private static final FakePattern ANOTHER_THOUSAND_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, 1L), new GenericStack(V2, 1L)},
                    UNITS_PER_FIRING, BUCKET, Set.of(V1, V2))},
            List.of(new GenericStack(AEItemKey.of(Items.BOOKSHELF), 1)));

    /**
     * 首位候选是<b>无限源</b>（每发 1000 枚，量在 {@code long} 域里没有上界、池子里恒为 0），
     * 后面还挂一位<b>有限候选</b>（V1，池子里真有量）：排水若在无限源那一格不收手，就会退到
     * V1 上把别人的口粮遮掉。
     */
    private static final AEItemKey INFINITE_SOURCE = AEItemKey.of(Items.LAVA_BUCKET);
    private static final FakePattern INFINITE_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(INFINITE_SOURCE, 1L),
                            new GenericStack(V1, 1L)},
                    UNITS_PER_FIRING, null, Set.of(INFINITE_SOURCE, V1))},
            List.of(new GenericStack(AEItemKey.of(Items.BOOKSHELF), 1)));

    /**
     * 单槽候选模板量 {@code Long.MAX/2} 枚、倍率 4 ⇒ 一发真需 18446744073709551612 枚：
     * 比 {@code Long.MAX_VALUE} 还多一倍，任何单个 {@code KeyCounter} 格子都装不下。
     */
    private static final long HUGE_TEMPLATE_UNITS = Long.MAX_VALUE / 2L;
    private static final long HUGE_MULTIPLIER = 4L;
    private static final BigInteger HUGE_FIRING_UNITS =
            BigInteger.valueOf(HUGE_TEMPLATE_UNITS).multiply(BigInteger.valueOf(HUGE_MULTIPLIER));
    private static final FakePattern HUGE_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, HUGE_TEMPLATE_UNITS)},
                    HUGE_MULTIPLIER, null, Set.of(V1))},
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * 同一发的真需，但这一格有<b>两位候选</b>（同族 V1 → V2）。
     *
     * <p>首位候选交不出整发时，"要不要向第二位伸手"就是"多一枚都不要"这条定数口径的判据点：
     * 缺口比一个天花板还长的那一刀，本轮只喂得进一刀 long，剩下的归下一批。
     */
    private static final FakePattern HUGE_TWO_CANDIDATES_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, HUGE_TEMPLATE_UNITS),
                            new GenericStack(V2, HUGE_TEMPLATE_UNITS)},
                    HUGE_MULTIPLIER, null, Set.of(V1, V2))},
            List.of(new GenericStack(PRODUCT, 1)));

    /** 后续候选在网络上的诱饵量：本批多搬进池子的每一枚都从这里少一枚。 */
    private static final BigInteger BAIT = new BigInteger("5000000000000000000");
    /** 首轮网络只交得出的那一刀（比一个天花板少）：凑不齐整发，也不许向后续候选伸手。 */
    private static final BigInteger PARTIAL_CHUNK = new BigInteger("4000000000000000000");

    /**
     * 单槽两位<b>不同物品本体族</b>候选（首位 V1=IRON_PICKAXE、备选 BUCKET）：族配额按本体记，
     * 两位候选分属两族才能各自有独立份额——用来把"首位候选配额已尽 ⇒ 退到备选"与"首位候选可花
     * 且撑满本批 ⇒ break"两种形状分开钉。每发真需 = 倍率 1 × 模板量 1 = 1 枚。
     */
    private static final FakePattern TWO_FAMILY_TWO_CANDIDATE = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, 1L), new GenericStack(BUCKET, 1L)},
                    1L, null, Set.of(V1, BUCKET))},
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * 同族两个槽、每发各吃 4 枚（首位候选 {@code V1}），槽0 另带一位<b>不同族</b>备选
     * {@code BUCKET}：用来把"可花判据的分母是本槽那份，还是该族在整张样板上的每发合计"钉开。
     */
    private static final FakePattern TWIN_FAMILY_FOUR_WITH_FALLBACK = new FakePattern(
            AEItemKey.of(Items.HOPPER),
            new FakeInput[]{
                    new FakeInput(new GenericStack[]{new GenericStack(V1, 4L),
                            new GenericStack(BUCKET, 1L)}, 1L, null, Set.of(V1, BUCKET)),
                    new FakeInput(new GenericStack[]{new GenericStack(V1, 4L)},
                            1L, null, Set.of(V1))
            },
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * {@link #TWIN_FAMILY_FOUR_WITH_FALLBACK} 的变体：<b>槽1 是原样退回的模具槽</b>
     * （候选 {@code V1}、{@code remainder=V1}）。取料点对它 {@code quotaExact=null} ⇒
     * 族每发合计只累加槽0 那 4 枚 ⇒ 可花判据的分母必须同样只算槽0。
     */
    private static final FakePattern TWIN_FAMILY_FOUR_MOLD_SLOT = new FakePattern(
            AEItemKey.of(Items.HOPPER),
            new FakeInput[]{
                    new FakeInput(new GenericStack[]{new GenericStack(V1, 4L),
                            new GenericStack(BUCKET, 1L)}, 1L, null, Set.of(V1, BUCKET)),
                    new FakeInput(new GenericStack[]{new GenericStack(V1, 4L)},
                            1L, V1, Set.of(V1))
            },
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * {@link #TWIN_FAMILY_FOUR_WITH_FALLBACK} 的变体：<b>槽1 声明了 {@code V1} 却对它
     * {@code isValid=false}</b>（按标签放行的万能样板往里塞了别的物品，这一格只认 {@code BUCKET}）。
     * 取料点的候选宇宙走 {@code usableTemplates} → {@code isValid} ⇒ 族每发合计只算槽0 那 4 枚。
     */
    private static final FakePattern TWIN_FAMILY_FOUR_INVALID_SECOND = new FakePattern(
            AEItemKey.of(Items.HOPPER),
            new FakeInput[]{
                    new FakeInput(new GenericStack[]{new GenericStack(V1, 4L)},
                            1L, null, Set.of(V1)),
                    new FakeInput(new GenericStack[]{new GenericStack(V1, 4L),
                            new GenericStack(BUCKET, 1L)}, 1L, null, Set.of(BUCKET))
            },
            List.of(new GenericStack(PRODUCT, 1)));

    private static final Map<IPatternDetails, WideKeyCounter> NO_PLAN = Map.of();
    private static final Map<IPatternDetails, WideKeyCounter> NO_TAKEN = Map.of();

    /** 没有别的任务占料、也没有提交期配给：账本存在也不改取料口径。 */
    private static final ParallelPlanReservation RESERVATION_FREE = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), NO_PLAN, NO_TAKEN);

    /** 配给/销账账本换宽域账本后的建表helper：数值口径与老 {@code Map.of(key, amount)} 一字不变。 */
    private static WideKeyCounter share(AEKey key, long amount) {
        var counter = new WideKeyCounter();
        counter.set(key, amount);
        return counter;
    }

    /** <b>越出 {@code long} 的真配额</b>：配给表本身是宽域账，两发的真量记得下。 */
    private static WideKeyCounter wideShare(AEKey key, BigInteger amount) {
        var counter = new WideKeyCounter();
        counter.setExact(key, amount);
        return counter;
    }

    @Test
    void declaredFiringsAreNotCappedByWhatOneKeyHolderCanCarry() {
        long eachKey = 500_000_000_000_000_000L; // 5e17 枚，两键合计 1e18 枚
        var holderPool = variantPool(eachKey, eachKey);
        var holderBatch = ParallelBatchExtraction.prepare(THOUSAND_A_FIRING, holderPool, null,
                1_000_000_000_000_000L, new KeyCounter(), new KeyCounter(), RESERVATION_FREE);
        assertNotNull(holderBatch);
        assertEquals(eachKey / UNITS_PER_FIRING, holderBatch.executions(),
                "k 倍 holder 路径只能把首个候选键的存量一次装进去：5e17 枚 ÷ 1000 = 5e14 发");
        assertEquals(eachKey, holderBatch.holder()[0].get(V1));

        var declaredPool = variantPool(eachKey, eachKey);
        var declared = ParallelBatchExtraction.declaredPayableCeiling(THOUSAND_A_FIRING,
                declaredPool, null, RESERVATION_FREE, BigInteger.valueOf(1_000_000_000_000_000L));
        assertEquals(new BigInteger("1000000000000000"), declared,
                "同族两键的现场量按宽域加总：1e18 枚 ÷ 1000 = 1e15 发");
        assertTrue(declared.compareTo(BigInteger.valueOf(holderBatch.executions())) > 0,
                "声明份数越过单键 holder 的上限，这正是本刀要拆的夹子");
        assertTrue(declared.compareTo(BIG_LONG) < 0, "产出侧仍要求申报 × 每发产出 ≤ long");
    }

    /**
     * 申报批欠着的料<b>仍然躺在同一个池子里</b>：下一张样板算可申上界读的就是这份池子，
     * 不遮住上一张已经申出去的那一截，同一份存量就被许诺两次——后申那一张从此交割不出，
     * 停在 {@code obligation_stalled owed=…} 上（复核 P1）。
     *
     * <p>claim 是<b>顺位排水算出的派生读数</b>：沿欠账样板每一槽的候选顺位，把还欠的那几发要吃
     * 的枚数从同一份宽域池子余量里排出去，排到哪个键就遮哪个键；欠账清完读数自己就没了。
     * 它既不是"申报那一刻的一发向量 × 发数"（那样只遮得住 holder 里那一个键），也不是第二本账
     * （那样要记得减）。
     */
    @Test
    void pendingObligationsHoldTheirPoolMaterialsAgainstFurtherDeclarations() {
        long declared = 5_000L;
        var pool = variantPool(9_000_000L, 0L); // 9000 发的料：两张样板共用同一族
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(declared));
        job.batchObligations.incur(THOUSAND_A_FIRING, BigInteger.valueOf(declared - 1L));

        var claims = ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                job.tasks.entrySet(), pool, RESERVATION_FREE);
        assertEquals(BigInteger.valueOf(declared - 1L).multiply(BigInteger.valueOf(UNITS_PER_FIRING)),
                claims.getExact(V1), "欠 4999 发就遮住 4999 发的料");
        assertEquals(new BigInteger("9000"), ParallelBatchExtraction.declaredPayableCeiling(
                ANOTHER_THOUSAND_A_FIRING, pool, null, RESERVATION_FREE, BIG_LONG),
                "不遮 claim 的读数：这一张申出去的 9000 发里 4999 发是上一张的口粮");
        assertEquals(new BigInteger("4001"), ParallelBatchExtraction.declaredPayableCeiling(
                ANOTHER_THOUSAND_A_FIRING, pool, null, RESERVATION_FREE, BIG_LONG, claims),
                "按欠账遮住之后，只剩真没人认领的 4001 发");

        long payable = ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING));
        var paid = ParallelBatchExtraction.payDeclaredObligation(THOUSAND_A_FIRING, pool, null,
                payable, RESERVATION_FREE, null);
        assertNotNull(paid);
        job.batchObligations.settle(THOUSAND_A_FIRING, paid.executions());
        assertTrue(ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                        job.tasks.entrySet(), pool, RESERVATION_FREE).isEmpty(),
                "欠账清了还遮着料，就是把别人的口粮永久锁死");
        assertEquals(new BigInteger("4001"), ParallelBatchExtraction.declaredPayableCeiling(
                ANOTHER_THOUSAND_A_FIRING, pool, null, RESERVATION_FREE, BIG_LONG,
                ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                        job.tasks.entrySet(), pool, RESERVATION_FREE)),
                "claim 跟着欠账一起消失：另一张看到的还是交割后真剩下的那 4001 发，不多也不少");
    }

    /**
     * 申报批可表示上界的反向用例：一张<b>没有欠账</b>的样板不许被 claim 遮住任何东西。
     *
     * <p>这一条钉的是空表那一侧：排水读的是"还挂着欠账的那几张"，一张都没挂时"遮"必须是一次都
     * 不发生——把空 claim 当成"每键遮满 long"会把申报整体判死。
     */
    @Test
    void declarationWithoutObligationsSeesTheWholePool() {
        var pool = variantPool(5_000_000L, 0L);
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(5_000L));
        var claims = ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                job.tasks.entrySet(), pool, RESERVATION_FREE);
        assertTrue(claims.isEmpty(), "没申过就没有 claim");
        assertEquals(new BigInteger("5000"), ParallelBatchExtraction.declaredPayableCeiling(
                ANOTHER_THOUSAND_A_FIRING, pool, null, RESERVATION_FREE, BIG_LONG, claims),
                "空 claim 一格都不许遮");
    }

    /**
     * 端到端（复核 P1-1 那一型）：A 走 <b>attemptDeclaredBatch</b> 挂上欠账后，同族同键的 B <b>也走
     * attemptDeclaredBatch</b>——B 申出去的份数里不许再含 A 已经许走的那一截。
     *
     * <p>夹具刻意给<b>第二个候选上量</b>：A 的欠账沿顺位先排干首位候选 V1、再退到 V2。按"申报那
     * 一刻的一发向量 × 发数"遮，向量里只有 V1 那一个键（{@code primaryCandidate} 每槽只写一个键），
     * V2 的 7001 发一字遮不住，B 照样把同一份 V2 存量再许一次——就是 owed 永久挂死那条实机症状。
     * 删掉 {@code attemptDeclaredBatch} 里那份 claims 实参，这一条立刻红（B 会申满 5000 发）。
     */
    @Test
    void secondDeclarationCannotRePromiseTheLaterCandidateTheFirstOneOwes() {
        var pool = variantPool(2_000L * UNITS_PER_FIRING, 9_000L * UNITS_PER_FIRING);
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(9_001L));
        job.tasks.put(ANOTHER_THOUSAND_A_FIRING, progress(5_000L));

        var first = ParallelCraftingCpuLogic.attemptDeclaredBatch(new PlainHost(),
                THOUSAND_A_FIRING, job, job.tasks.entrySet(), pool, null, RESERVATION_FREE,
                new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(), Long.MAX_VALUE,
                Long.MAX_VALUE, null, null, null, null, 0L);
        assertNotNull(first);
        assertEquals(9_001L, first.accepted(), "A 按现场可付量整批申报（同族两键合计 11000 发）");
        assertEquals(BigInteger.valueOf(9_000L),
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING), "欠 9000 发等分批交割");

        var claims = ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                job.tasks.entrySet(), pool, RESERVATION_FREE);
        assertEquals(BigInteger.valueOf(1_999L * UNITS_PER_FIRING), claims.getExact(V1),
                "首位候选剩下的 1999 发排在前面，先记到 V1 头上");
        assertEquals(BigInteger.valueOf(7_001L * UNITS_PER_FIRING), claims.getExact(V2),
                "欠账退到第二个候选：那一截同样要遮，漏遮就是 B 重复许诺的来源");

        var host = new RejectingHost();
        var second = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, ANOTHER_THOUSAND_A_FIRING,
                job, job.tasks.entrySet(), pool, null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, null, null, null,
                null, 0L);
        assertNotNull(second, "还剩真没人认领的料，B 这一张仍然适用声明形态");
        assertEquals(BigInteger.valueOf(1_999L), host.lastDeclared,
                "B 只许申 V2 真剩下的那 1999 发，不是自己剩余发数那 5000 发");
        assertEquals(UNITS_PER_FIRING, host.lastPrototypeUnits, "交到宿主手上的仍是一发的原型料");
        assertFalse(job.batchObligations.hasPending(ANOTHER_THOUSAND_A_FIRING),
                "B 被拒收：不挂欠账、不占池子，本用例只取它申出去的那个数");
        assertEquals(1_999L * UNITS_PER_FIRING, WideCraftingInventory.amount(pool, V1).longValueExact(),
                "V1 剩下的一枚没被 B 的拒收批次动过");

        // 对照读数：不遮 claim 的那把尺子就是改前行为——同一份 V2 存量被许诺两次。
        assertEquals(new BigInteger("10999"), ParallelBatchExtraction.declaredPayableCeiling(
                ANOTHER_THOUSAND_A_FIRING, pool, null, RESERVATION_FREE, BIG_LONG),
                "V1 剩下的 1999 发 + V2 的 9000 发全被 B 当成自己的可供量：夹上它自己那 5000 发即申满");
    }

    /**
     * 跨续传（复核 P1-3）：欠账从 NBT 读回来（{@code pending>0}、<b>没有任何申报那一刻的观测</b>），
     * claim 仍然遮得住。
     *
     * <p>排水路线的输入只有"还欠几发 + 样板声明的顺位 + 池子的当下真值"，三样在续传后都读得回来：
     * 欠账落在任务条目上、顺位在样板里、池子在 CPU 库存里。观测路线在这一型上直接归零（向量不落
     * NBT），而这类作业一跑几小时、重启与卸载区块是常态。
     */
    @Test
    void obligationResumedFromNbtStillHoldsItsPoolMaterials() {
        var pool = variantPool(5_000L * UNITS_PER_FIRING, 0L);
        var entry = new CompoundTag();
        BatchObligationLedger.writeEntry(entry, BigInteger.valueOf(4_999L));

        var resumed = newJob();
        resumed.tasks.put(THOUSAND_A_FIRING, progress(0L));
        resumed.batchObligations.incur(THOUSAND_A_FIRING, BatchObligationLedger.readEntry(entry));

        var claims = ParallelCraftingCpuLogic.pendingPoolClaims(resumed.batchObligations,
                resumed.tasks.entrySet(), pool, RESERVATION_FREE);
        assertEquals(BigInteger.valueOf(4_999L * UNITS_PER_FIRING), claims.getExact(V1),
                "重载后只剩欠账这一个输入：claim 归零就是把同一份存量再次许诺给下一张样板");
        assertEquals(new BigInteger("1"), ParallelBatchExtraction.declaredPayableCeiling(
                ANOTHER_THOUSAND_A_FIRING, pool, null, RESERVATION_FREE, BIG_LONG, claims),
                "另一张只看得见真剩下的那一发");
    }

    /**
     * 复核 P1-2 的另一半：<b>凭空量与无限源那一格不进 claim</b>。
     *
     * <p>夹具刻意让这张样板<b>还有一位排在无限源后面的有限候选</b>，且那位在池子里真有 5000 发的
     * 料：取料点吃到第一位（无限源）就把整批付完，池子里那一枚都不动 ⇒ 欠 5000 发也遮不出 claim。
     * 少了"命中无限源即收手"这一格，排水会退到后面那位候选、把别人真能吃的 5000 发算成这张样板
     * 的口粮（对照段就是这一型：同一份夹具、配给表不认这个键为无限源，claim 立刻遮满 5_000_000）。
     *
     * <p>观测路线在这一格上还会把 {@code holder} 里的 {@code mintedUnits}／饱和哨兵当真值乘进
     * claim——那份量压根不在池子里，遮它就是凭空多出一份口粮账。
     */
    @Test
    void unboundedSlotContributesNothingToTheClaims() {
        var pool = variantPool(5_000L * UNITS_PER_FIRING, 0L);
        var job = newJob();
        job.tasks.put(INFINITE_A_FIRING, progress(0L));
        job.batchObligations.incur(INFINITE_A_FIRING, BigInteger.valueOf(5_000L));
        var reservation = MaterialAllocationFixtures.build(job.tasks.entrySet(), Map.of(),
                NO_PLAN, NO_TAKEN, Set.of(INFINITE_SOURCE));

        var claims = ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                job.tasks.entrySet(), pool, reservation);
        assertTrue(claims.isEmpty(), "无限源那一格不吃池子里的一枚：排水到此即收手，不退到后面的候选");
        assertEquals(0L, pool.extract(INFINITE_SOURCE, Long.MAX_VALUE, Actionable.SIMULATE),
                "池子里该键仍是 0：凭空的量从不进池子");
        assertEquals(new BigInteger("5000"), ParallelBatchExtraction.declaredPayableCeiling(
                ANOTHER_THOUSAND_A_FIRING, pool, null, RESERVATION_FREE, BIG_LONG, claims),
                "这张样板欠着 5000 发，也不该把 V1 那 5000 发锁给别人");

        // 对照：同一份夹具、账上不认这个键为无限源 ⇒ 排水退到有限候选，那一截照样遮得住
        // ——遮住的是"取料点真会伸手拿"的量，不是凭空量。
        var unrecogized = MaterialAllocationFixtures.build(job.tasks.entrySet(), Map.of(),
                NO_PLAN, NO_TAKEN);
        assertEquals(BigInteger.valueOf(5_000L * UNITS_PER_FIRING),
                ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                        job.tasks.entrySet(), pool, unrecogized).getExact(V1),
                "本用例的夹具确实带着会被错遮的量：没有无限源豁免时 claim 就是 5_000_000 枚");
    }


    /**
     * claim 的覆盖前提（复核点名的测试盲区）：发数跑完但材料还没交割的任务<b>不许出表</b>。
     *
     * <p>排水读的就是任务表——条目一旦提前出表，那笔欠账再也遮不住池子，"同一份存量许诺两次"
     * 逐字复发（实机形态 owed 挂死）。这一条判据此前散在 taskLoop 的四处里，离线用例走不进那条路，
     * 把它整个断掉五条用例仍全绿；抽成 {@code taskMayLeaveTable} 之后才钉得住。
     */
    @Test
    void finishedTaskWithUndeliveredObligationStaysOnTheTableAndKeepsItsClaim() {
        var job = newJob();
        var finished = progress(0L);
        job.tasks.put(THOUSAND_A_FIRING, finished);
        var pool = variantPool(5_000_000L, 0L);

        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(THOUSAND_A_FIRING, finished,
                job.batchObligations), "发数跑完、材料也交割干净：该出表");

        job.batchObligations.incur(THOUSAND_A_FIRING, BigInteger.valueOf(2_000L));
        assertFalse(ParallelCraftingCpuLogic.taskMayLeaveTable(THOUSAND_A_FIRING, finished,
                job.batchObligations), "欠着 2000 发的材料就不许出表");
        assertEquals(new BigInteger("2000000"), ParallelCraftingCpuLogic.pendingPoolClaims(
                        job.batchObligations, job.tasks.entrySet(), pool, RESERVATION_FREE)
                .getExact(V1), "条目还在表上：这笔欠账仍然遮得住池子");

        job.batchObligations.settle(THOUSAND_A_FIRING, 2_000L);
        assertTrue(ParallelCraftingCpuLogic.taskMayLeaveTable(THOUSAND_A_FIRING, finished,
                job.batchObligations), "还清了才放行出表");
        assertTrue(ParallelCraftingCpuLogic.pendingPoolClaims(job.batchObligations,
                job.tasks.entrySet(), pool, RESERVATION_FREE).isEmpty(), "还清即不再遮着别人的口粮");
    }

    /**
     * 欠账一批只吞"这一轮真付得起的发数"（实机 2026-09-25 19:08 owed 永挂那一型）。
     *
     * <p>宿主一次收下 4.5e14 发，欠账就是 4.5e14 发。旧形状把一批的规模定在"一个 {@code long}
     * 格子装得下几发"（这里 9.2e15），于是这一批要吞 4.5e17 枚——现场只有五发的料，
     * all-or-nothing 立刻整批归位：每轮 0 批、{@code obPull=0}、owed 一动不动，作业永远交不了工。
     */
    @Test
    void obligationBatchIsCappedByWhatThisRoundCanActuallyPay() {
        var job = newJob();
        var pool = variantPool(5_000L, 0L); // 只够 5 发
        var owed = new BigInteger("447389208773083");
        job.batchObligations.incur(THOUSAND_A_FIRING, owed);
        long payable = ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING));
        assertTrue(payable > 5_000L, "旧形状：一批的规模远大于现场这一轮付得起的发数");

        long paid = ParallelCraftingCpuLogic.payObligationBatch(job, THOUSAND_A_FIRING, payable,
                pool, null, RESERVATION_FREE, new ParallelPoolLedger(), null, null);

        assertEquals(5L, paid, "一批只付这一轮真付得起的 5 发，而不是整批归位报 0");
        assertEquals(owed.subtract(BigInteger.valueOf(5L)),
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING), "剩下的欠账照旧挂着等下轮");
        assertEquals(0L, pool.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE),
                "付出去那五发的料真离了池子");
    }

    /**
     * 流式的唯一刹车：同一格连喂多刀，喂到<b>凑齐</b>或<b>本轮墙钟用完</b>为止。
     *
     * <p>没有这道闸，一次回补就能把整池抽满、把 MSPT 保护让给搬运本身。网络在这里被写成
     * "要多少给多少"，所以能停下它的只有预算：三刀之后 hasTimeBudget 交回 false ⇒ 第四刀
     * 不许再伸手，剩下的那一截留在欠账里等下一轮。
     */
    @Test
    void streamingRefillStopsWhenTheWallClockBudgetRunsOut() {
        var pool = widePool();
        var pulls = new int[1];
        var refill = new ParallelBatchExtraction.ObligationRefill() {
            @Override
            public long pull(AEKey what, long wanted) {
                pulls[0]++;
                return wanted; // 网络一直给满口：只有预算能叫它停手
            }

            @Override
            public boolean hasTimeBudget() {
                return pulls[0] < 3;
            }
        };

        long pulled = ParallelBatchExtraction.refillObligationBatch(HUGE_A_FIRING, pool, null,
                BigInteger.valueOf(3L), RESERVATION_FREE, refill, null);

        assertEquals(3, pulls[0], "三刀之后预算用完：第四刀一次都不许多伸");
        assertEquals(BIG_LONG.multiply(BigInteger.valueOf(3L)),
                WideCraftingInventory.amount(pool, V1),
                "三刀都留在池子里（宽域累加，每刀仍 ≤long）");
        assertEquals(BIG_LONG.longValue(), pulled,
                "long 合计读数饱和在天花板：这正是它当不了推进证据的原因");
    }

    @Test
    void obligationPaymentsAddUpToTheSameMaterialsAsOneKFoldHolder() {
        long firings = 5_000L;
        long stock = firings * UNITS_PER_FIRING;
        var referencePool = variantPool(stock, 0L);
        var referenceOutputs = new KeyCounter();
        var referenceContainers = new KeyCounter();
        var reference = ParallelBatchExtraction.prepare(THOUSAND_A_FIRING, referencePool, null,
                firings, referenceOutputs, referenceContainers, RESERVATION_FREE);
        assertNotNull(reference);

        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(firings));
        var pool = variantPool(stock, 0L);
        var prototype = ParallelBatchExtraction.prepareUnitPrototype(THOUSAND_A_FIRING, pool, null,
                new KeyCounter(), RESERVATION_FREE, null);
        assertNotNull(prototype);
        assertEquals(1L, prototype.executions(), "宿主只收一发的原型材料");
        assertEquals(UNITS_PER_FIRING, prototype.holder()[0].get(V1));
        ParallelCraftingCpuLogic.recordTakenInputs(job, THOUSAND_A_FIRING, prototype.holder());
        job.batchObligations.incur(THOUSAND_A_FIRING, BigInteger.valueOf(firings - 1L));

        long payable = ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING));
        var paid = ParallelBatchExtraction.payDeclaredObligation(THOUSAND_A_FIRING, pool, null,
                payable, RESERVATION_FREE, null);
        assertNotNull(paid);
        assertEquals(firings - 1L, paid.executions());
        ParallelCraftingCpuLogic.recordTakenInputs(job, THOUSAND_A_FIRING, paid.holder());
        job.batchObligations.settle(THOUSAND_A_FIRING, paid.executions());

        assertEquals(0, job.batchObligations.pendingFirings(THOUSAND_A_FIRING).signum(),
                "欠账交割干净");
        assertEquals(reference.holder()[0].get(V1), job.accountTaken.getLong(V1),
                "分批销账的合计 == 同一 (details, k, 存量) 下 k 倍 holder 的提料合计");
        assertEquals(stock, job.accountTaken.getLong(V1));
        assertEquals(0L, pool.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE));

        var declaredOutputs = new KeyCounter();
        var declaredContainers = new KeyCounter();
        ParallelBatchExtraction.addDeclaredReserves(THOUSAND_A_FIRING, prototype.holder(), firings,
                declaredOutputs, declaredContainers);
        assertEquals(referenceOutputs.get(PRODUCT), declaredOutputs.get(PRODUCT),
                "产出预期与 k 倍 holder 路径同值");
        assertEquals(referenceContainers.get(BUCKET), declaredContainers.get(BUCKET),
                "回流容器预期与 k 倍 holder 路径同值");
    }

    @Test
    void overLongTaskTableAdvancesInDeclaredBatchesAndCarriesTheObligation() {
        // 任务表挂 1e19 发（越出 long 的饱和哨兵），池子里同族两键合计 1e18 枚 ⇒ 每发 1000 枚
        // ⇒ 一次声明最多申报 1e15 发；这个数远超单键 holder 一批装得下的 5e14 发。
        var tableFirings = new BigInteger("10000000000000000000");
        long eachKey = 500_000_000_000_000_000L;
        long firings = eachKey * 2L / UNITS_PER_FIRING;
        var job = newJob();
        var progress = progress(0L);
        progress.addFirings(PlanAmount.of(tableFirings));
        job.tasks.put(THOUSAND_A_FIRING, progress);
        var pool = variantPool(eachKey, eachKey);

        var declared = ParallelCraftingCpuLogic.declaredBatchFirings(new RejectingHost(),
                THOUSAND_A_FIRING, progress, pool, null, RESERVATION_FREE, Long.MAX_VALUE, null);
        assertEquals(BigInteger.valueOf(firings), declared,
                "申报被现场可付量夹住，而不是被任务表的饱和读数或单键 holder 夹住");
        assertTrue(declared.compareTo(BigInteger.valueOf(firings / 2L)) > 0,
                "申报份数越过单键 holder 一批的上限");

        var prototype = ParallelBatchExtraction.prepareUnitPrototype(THOUSAND_A_FIRING, pool, null,
                new KeyCounter(), RESERVATION_FREE, null);
        assertNotNull(prototype);
        ParallelCraftingCpuLogic.recordTakenInputs(job, THOUSAND_A_FIRING, prototype.holder());
        job.batchObligations.incur(THOUSAND_A_FIRING, declared.subtract(BigInteger.ONE));

        // 第一批：顺位第一的键被吃干，剩下的欠账原样留着
        var first = ParallelBatchExtraction.payDeclaredObligation(THOUSAND_A_FIRING, pool, null,
                ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                        job.batchObligations.pendingFirings(THOUSAND_A_FIRING)),
                RESERVATION_FREE, null);
        assertNotNull(first);
        ParallelCraftingCpuLogic.recordTakenInputs(job, THOUSAND_A_FIRING, first.holder());
        job.batchObligations.settle(THOUSAND_A_FIRING, first.executions());
        assertEquals(0L, pool.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE), "首批把首位候选吃干");
        assertEquals(eachKey, pool.extract(V2, Long.MAX_VALUE, Actionable.SIMULATE),
                "第二个候选是下一批的口粮，不能被首批改道吃掉");
        assertTrue(job.batchObligations.hasPending(THOUSAND_A_FIRING), "首批交不完就欠着");

        // 第二批：同一套顺位退到下一个候选，欠账清零
        var second = ParallelBatchExtraction.payDeclaredObligation(THOUSAND_A_FIRING, pool, null,
                ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                        job.batchObligations.pendingFirings(THOUSAND_A_FIRING)),
                RESERVATION_FREE, null);
        assertNotNull(second);
        ParallelCraftingCpuLogic.recordTakenInputs(job, THOUSAND_A_FIRING, second.holder());
        job.batchObligations.settle(THOUSAND_A_FIRING, second.executions());

        assertEquals(0, job.batchObligations.pendingFirings(THOUSAND_A_FIRING).signum(),
                "原型一发 + 分批交割 = 申报份数");
        assertEquals(BigInteger.valueOf(firings),
                BigInteger.valueOf(1L + first.executions() + second.executions()));
        assertEquals(eachKey, job.accountTaken.getLong(V1));
        assertEquals(eachKey, job.accountTaken.getLong(V2));
        assertFalse(progress.isFinished(), "1e19 发的任务推进 1e15 发远没收工");
        assertEquals(tableFirings, progress.exactRemaining().exactValue(),
                "本用例只演提取与交割，扣发数在推送那一刻按实收发生");
    }

    /**
     * 每发倍率 {@code 4}（一发 4 枚）、<b>没有回流容器</b>、一发产 1 枚：申报份数于是被
     * "申报 × 每发倍率 ≤ long"那道<b>输入</b>夹子钉在 {@code Long.MAX_VALUE / 4 = 2305843009213693951}
     * 发——实机 2026-09-26 04:20 那一跑 {@code got=2305843009213693951} 就是这个数。
     */
    private static final FakePattern FOUR_UNITS_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BARREL),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, 1L)}, 4L, null, Set.of(V1))},
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * 任务表真值 9e18 发、池子里摆着 2×{@code long} 枚 {@code V1}（每发 4 枚 ⇒ 现场撑得起
     * 4611686018427387903 发）：两数都越过 {@code Long.MAX/4}，正好卡出"输入侧那把尺夹的是谁的账"。
     */
    private static final BigInteger NINE_E18 = new BigInteger("9000000000000000000");
    private static final BigInteger POOL_FIRINGS = new BigInteger("4611686018427387903");
    private static final BigInteger INPUT_SIDE_CEILING = BigInteger.valueOf(Long.MAX_VALUE / 4L);

    /** 申报份数没有数值上界的宿主（useless bigint 通道那条形状）：capacity 报多大都不该被读。 */
    private static final class UnboundedHost extends CappedHost {
        private UnboundedHost() {
            super(BIG_LONG);
        }

        @Override
        public boolean declaredBatchFiringsUnbounded() {
            return true;
        }
    }

    /** 有真数值上界的宿主：装配机那条路径的形状，capacity 与输入侧夹子都必须照旧生效。 */
    private static class CappedHost implements DeclaredBatchHost {
        private final BigInteger capacity;

        private CappedHost(BigInteger capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean supportsDeclaredBatch(IPatternDetails pattern) {
            return true;
        }

        @Override
        public BigInteger declaredBatchCapacity(IPatternDetails pattern) {
            return capacity;
        }

        @Override
        public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger declaredExecutions) {
            return declaredExecutions.min(capacity);
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return true;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    /** 夹具：池子里放 2×{@code long} 枚 {@code V1}、任务表挂 9e18 发的越界形状。 */
    private static ListCraftingInventory fourUnitPoolWithBeyondLongStock() {
        var pool = widePool();
        pool.insertExact(V1, BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
                Actionable.MODULATE);
        return pool;
    }

    /**
     * 宿主报"申报份数没有数值上界"⇒ 申报不再被<b>每发件数</b>（输入侧那把尺）夹：
     * 每发 4 枚、剩余 9e18 发、池子撑得起 4.6e18 发 ⇒ 申报就是 4.6e18 发。
     *
     * <p>旧实现当场红：那一道夹子把申报钉在 {@code Long.MAX/4 = 2305843009213693951}（实机
     * {@code got=2305843009213693951} 逐字同一个数），而 bigint 通道侧申报=实收、根本没有数值上界。
     */
    @Test
    void unboundedHostDeclaredFiringsAreNotClampedByUnitsPerFiring() {
        var job = newJob();
        job.tasks.put(FOUR_UNITS_A_FIRING, wideProgress(NINE_E18));
        var pool = fourUnitPoolWithBeyondLongStock();

        var declared = ParallelCraftingCpuLogic.declaredBatchFirings(new UnboundedHost(),
                FOUR_UNITS_A_FIRING, job.taskProgress(FOUR_UNITS_A_FIRING), pool, null,
                RESERVATION_FREE, Long.MAX_VALUE, null);

        assertEquals(POOL_FIRINGS, declared,
                "剩下的夹子只有产出（每发 1 枚 ⇒ 不夹）、电力（long 天花板）与现场可付：申报 = 池子撑得起的 4.6e18 发");
        assertTrue(declared.compareTo(INPUT_SIDE_CEILING) > 0,
                "旧实现把申报夹在每发 4 件的 Long.MAX/4：那把尺量的宿主账本在这条腿上压根不存在");
    }

    /** 容器预期已经使用宽域账，支持大数的宿主不再被输入倍率的 long 投影限制。 */
    @Test
    void unboundedHostKeepsExactFiringsWhenThePatternHasRemainders() {
        var pattern = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(new GenericStack[]{new GenericStack(V1, 1L)},
                        4L, BUCKET, Set.of(V1))},   // 每发 4 枚 + 每发留一只桶
                List.of(new GenericStack(PRODUCT, 1)));
        var job = newJob();
        job.tasks.put(pattern, wideProgress(NINE_E18));
        var pool = fourUnitPoolWithBeyondLongStock();

        var declared = ParallelCraftingCpuLogic.declaredBatchFirings(new UnboundedHost(),
                pattern, job.taskProgress(pattern), pool, null, RESERVATION_FREE, Long.MAX_VALUE, null);

        assertEquals(POOL_FIRINGS, declared,
                "宽域容器预期保持精确，申报只受实际材料限制");
    }

    /**
     * 反向（不许把"放开"做成全局取消封顶）：报<b>真数值上界</b>的宿主（装配机那条）两道夹子一字不变。
     */
    @Test
    void boundedHostKeepsCapacityAndPerFiringInputClampsByteForByte() {
        var job = newJob();
        job.tasks.put(FOUR_UNITS_A_FIRING, wideProgress(NINE_E18));
        var progress = job.taskProgress(FOUR_UNITS_A_FIRING);
        var pool = fourUnitPoolWithBeyondLongStock();

        var capped = ParallelCraftingCpuLogic.declaredBatchFirings(new CappedHost(BigInteger.valueOf(1000L)),
                FOUR_UNITS_A_FIRING, progress, pool, null, RESERVATION_FREE, Long.MAX_VALUE, null);
        assertEquals(BigInteger.valueOf(1000L), capped, "宿主真能力仍然是硬上界：报 1000 就只申 1000");

        var longCapacity = ParallelCraftingCpuLogic.declaredBatchFirings(new CappedHost(BIG_LONG),
                FOUR_UNITS_A_FIRING, progress, pool, null, RESERVATION_FREE, Long.MAX_VALUE, null);
        assertEquals(INPUT_SIDE_CEILING, longCapacity,
                "有界宿主照旧被每发 4 件夹进可表示区间：这一支一个数都没动");
    }

    /**
     * 宿主<b>报多不收</b>：回执交回 {@code declared + 1} 时，入账份数、任务表扣减、义务账
     * {@code incur} 三处一律按<b>申报值 declared</b> 结算，绝不吃宿主虚报的那一份。
     *
     * <p>{@code ParallelCraftingCpuLogic#attemptDeclaredBatch} 在回执处取
     * {@code accepted = accepted.min(declared)}——申报被产出侧可表示上界夹在 long 域里，
     * 这份读数是精确值而非哨兵。删掉那一道 {@code min(declared)}（即直接吃宿主报数），本用例当场红：
     * 宿主虚报 {@code declared+1} ⇒ 我们按虚报的份数扣任务表、挂义务账 ⇒ 凭空多记一截欠账。
     */
    @Test
    void hostAcceptedReadoutIsExactWithinDeclaredCeiling() {
        var host = new OverReportingHost();
        var job = newJob();
        // 任务表挂 6000 发、池子只够 5000 发：现场可付那把尺把申报夹在 5000，宿主若虚报 5001 一律不收
        job.tasks.put(THOUSAND_A_FIRING, progress(6_000L));
        var pool = variantPool(5_000L * UNITS_PER_FIRING, 0L);

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job,
                job.tasks.entrySet(), pool,
                null, RESERVATION_FREE, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);

        assertNotNull(push, "宿主具备声明能力就要走新路径");
        assertFalse(push.rejected());
        assertEquals(BigInteger.valueOf(5_000L), host.lastDeclared,
                "申报如实交到宿主手上：现场可付把 6000 发夹成 5000 发");
        assertEquals(5_000L, push.accepted(),
                "入账份数恰等于 declared，不是宿主报的 declared+1");
        assertEquals(BigInteger.valueOf(4_999L),
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING),
                "义务账按 declared 挂：incur = declared - 1，不是宿主报数 - 1");
        assertEquals(1_000L, job.taskProgress(THOUSAND_A_FIRING).value,
                "任务表按 declared 扣：6000 - declared(5000)，虚报的那一发不许吃掉任务表");
    }

    @Test
    void rejectedDeclaredBatchLeavesPoolTableAndObligationsUntouched() {
        long stock = 5_000L * UNITS_PER_FIRING;
        var host = new RejectingHost();
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(5_000L));
        var pool = variantPool(stock, 0L);

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(host, THOUSAND_A_FIRING, job,
                job.tasks.entrySet(), pool,
                null, RESERVATION_FREE, new ParallelPoolLedger(), new KeyCounter(), new KeyCounter(),
                Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, 0L);

        assertNotNull(push, "宿主具备声明能力就要走新路径");
        assertTrue(push.rejected());
        assertEquals(0L, push.accepted());
        assertEquals(stock, pool.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE), "料原数回池");
        assertEquals(5_000L, job.taskProgress(THOUSAND_A_FIRING).value, "任务表一格都没动");
        assertFalse(job.batchObligations.hasPending(THOUSAND_A_FIRING), "拒收不挂义务账");
        assertTrue(job.accountTaken.isEmpty(), "拒收不提走任何料");
        assertTrue(job.waitingFor.list.isEmpty(), "拒收不挂任何产出预期");
        assertEquals(BigInteger.valueOf(5_000L), host.lastDeclared, "申报份数如实交到宿主手上");
        assertEquals(UNITS_PER_FIRING, host.lastPrototypeUnits, "宿主只收一发的原型材料");
    }

    @Test
    void hostWithoutTheCapabilityKeepsTheKFoldHolderShape() {
        // 默认不覆写 ⇒ CPU 探测阶段就把它当"没有声明能力"，继续走 k 倍 holder 路径
        IPatternDetails details = THOUSAND_A_FIRING;
        assertFalse(new PlainHost().supportsDeclaredBatch(details),
                "未覆写的宿主永远走现有 k 倍 holder 路径");
        assertEquals(5_000L, ParallelBatchExtraction.prepare(THOUSAND_A_FIRING,
                variantPool(5_000L * UNITS_PER_FIRING, 0L), null, 6_000L,
                new KeyCounter(), new KeyCounter(), RESERVATION_FREE).executions(),
                "老路径的口径一个字都不变");
    }

    /**
     * 内部缓冲只够一部分、网络还有料：欠账<b>跨批</b>从网络分批回补，一路清到 {@code obOwed} 归零。
     *
     * <p>同时钉住等价性——"分批（缓冲优先 + 网络回补）"的提料合计与同一
     * {@code (details, k, 供给)} 下一次性 k 倍 holder 路径的提料合计逐键相等。
     */
    @Test
    void obligationIsRefilledFromTheNetworkAcrossBatchesUntilOwedClears() {
        long firings = 5_000L;
        long stock = firings * UNITS_PER_FIRING;
        long buffered = 2_000L * UNITS_PER_FIRING;
        var reference = ParallelBatchExtraction.prepare(THOUSAND_A_FIRING,
                variantPool(stock, 0L), null, firings, new KeyCounter(), new KeyCounter(),
                RESERVATION_FREE);
        assertNotNull(reference);
        assertEquals(firings, reference.executions(), "参照：一次性 5000 发的 k 倍 holder 路径");

        var job = newJob();
        var progress = progress(firings);
        job.tasks.put(THOUSAND_A_FIRING, progress);
        var buffer = variantPool(buffered, 0L);
        var network = new KeyCounter();
        var probe = new ParallelBatchExtraction.Probe();
        var ledger = new ParallelPoolLedger();

        var prototype = ParallelBatchExtraction.prepareUnitPrototype(THOUSAND_A_FIRING, buffer, null,
                new KeyCounter(), RESERVATION_FREE, null);
        assertNotNull(prototype);
        ParallelCraftingCpuLogic.recordTakenInputs(job, THOUSAND_A_FIRING, prototype.holder());
        progress.consumeFirings(prototype.executions());
        job.batchObligations.incur(THOUSAND_A_FIRING, BigInteger.valueOf(firings - 1L));

        // 第一批：网络只到 1000 发的量。缓冲优先被吃干，回补只补缺口，交得出多少算多少
        network.add(V1, 1_000_000L);
        long first = ParallelCraftingCpuLogic.payObligationBatch(job, THOUSAND_A_FIRING,
                ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                        job.batchObligations.pendingFirings(THOUSAND_A_FIRING)),
                buffer, null, RESERVATION_FREE, ledger, networkRefill(network), probe);
        assertEquals(2_999L, first, "内部缓冲 1_999_000 枚优先 + 网络回补 1_000_000 枚 = 2999 发");
        assertEquals(BigInteger.valueOf(2_000L),
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING), "没交完的照旧欠着");
        assertEquals(0L, network.get(V1), "网络给得出的那一截搬进了缓冲");
        assertEquals(1_000_000L, probe.obligationRefillUnits, "回补只搬料：账仍由 recordTakenInputs 出");

        // 第二批：后到的料落到网络上，欠账继续被分批清掉
        network.add(V1, 2_000_000L);
        long second = ParallelCraftingCpuLogic.payObligationBatch(job, THOUSAND_A_FIRING,
                ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                        job.batchObligations.pendingFirings(THOUSAND_A_FIRING)),
                buffer, null, RESERVATION_FREE, ledger, networkRefill(network), probe);
        assertEquals(2_000L, second);
        assertEquals(0, job.batchObligations.pendingFirings(THOUSAND_A_FIRING).signum(),
                "obOwed 归零：欠着的料在网络上，CPU 现在拿得到了");
        assertEquals(0L, buffer.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE), "缓冲也交割干净");
        assertEquals(2, (int) probe.obligationBatches);
        assertEquals(2, (int) probe.obligationRefillBatches);

        assertEquals(stock, prototype.holder()[0].get(V1) + first * UNITS_PER_FIRING
                        + second * UNITS_PER_FIRING, "一发原型 + 两批交割 = 申报份数");
        assertEquals(reference.holder()[0].get(V1), job.accountTaken.getLong(V1),
                "分批（缓冲优先 + 网络回补）的提料合计 == 一次性 k 倍 holder 的提料合计");
        assertEquals(stock, job.accountTaken.getLong(V1));
    }

    /** 网络也没料时保持既有停滞语义：欠账保留、任务不判完成、告警闸口仍然成立。 */
    @Test
    void obligationStaysOwedWhenTheNetworkHasNothingEither() {
        long firings = 5_000L;
        var job = newJob();
        var progress = progress(firings);
        job.tasks.put(THOUSAND_A_FIRING, progress);
        var buffer = variantPool(UNITS_PER_FIRING, 0L);
        var network = new KeyCounter();
        var probe = new ParallelBatchExtraction.Probe();

        var prototype = ParallelBatchExtraction.prepareUnitPrototype(THOUSAND_A_FIRING, buffer, null,
                new KeyCounter(), RESERVATION_FREE, null);
        assertNotNull(prototype);
        ParallelCraftingCpuLogic.recordTakenInputs(job, THOUSAND_A_FIRING, prototype.holder());
        progress.consumeFirings(prototype.executions());
        job.batchObligations.incur(THOUSAND_A_FIRING, BigInteger.valueOf(firings - 1L));

        long paid = ParallelCraftingCpuLogic.payObligationBatch(job, THOUSAND_A_FIRING,
                ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                        job.batchObligations.pendingFirings(THOUSAND_A_FIRING)),
                buffer, null, RESERVATION_FREE, new ParallelPoolLedger(), networkRefill(network),
                probe);

        assertEquals(0L, paid, "缓冲与网络都拿不出料：一批都交割不了");
        assertEquals(BigInteger.valueOf(firings - 1L),
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING), "不许静默少付：欠账原样留着");
        assertFalse(progress.isFinished(), "不许把任务判完成");
        assertTrue(ParallelCraftingCpuLogic.obligationStalled(paid, job.batchObligations.totalPending()),
                "stall 告警的闸口仍然成立");
        assertEquals(UNITS_PER_FIRING, job.accountTaken.getLong(V1), "只有原型那一发离开池子");
        assertEquals(0L, probe.obligationRefillUnits, "没料可补就不动通道");
        assertTrue(MaterialAllocationFixtures.eatenSnapshot(job).isEmpty()
                        || MaterialAllocationFixtures.eatenSnapshot(job).get(THOUSAND_A_FIRING).getLong(V1)
                                == UNITS_PER_FIRING,
                "配给账只按真正提走的那一发记账");
    }

    /** 回补量夹在<b>这张样板自己还没销完的配给</b>里：别人那份口粮留在网络上。 */
    @Test
    void refillNeverPullsMoreThanThePatternOwnsRemainingShare() {
        var job = newJob();
        job.tasks.put(THOUSAND_A_FIRING, progress(4_999L));
        MaterialAllocationFixtures.assign(job, THOUSAND_A_FIRING, share(V1, 2_000_000L));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var buffer = variantPool(0L, 0L);
        var network = new KeyCounter();
        network.add(V1, 5_000_000L);
        var ledger = new ParallelPoolLedger();
        job.batchObligations.incur(THOUSAND_A_FIRING, BigInteger.valueOf(4_999L));

        long first = ParallelCraftingCpuLogic.payObligationBatch(job, THOUSAND_A_FIRING,
                ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                        job.batchObligations.pendingFirings(THOUSAND_A_FIRING)),
                buffer, null, reservation, ledger, networkRefill(network), null);

        assertEquals(2_000L, first, "本批只交得出配给允许的 2000 发");
        assertEquals(3_000_000L, network.get(V1), "配给之外的 300 万枚留在网络上，没被搬进缓冲");
        assertEquals(2_000_000L, job.accountTaken.getLong(V1));

        // 配给已销完：下一批一枚都不再回补，欠账继续等料
        long second = ParallelCraftingCpuLogic.payObligationBatch(job, THOUSAND_A_FIRING,
                ParallelBatchExtraction.obligationBatchFirings(THOUSAND_A_FIRING,
                        job.batchObligations.pendingFirings(THOUSAND_A_FIRING)),
                buffer, null, reservation, ledger, networkRefill(network), null);
        assertEquals(0L, second, "配给用尽就不该再伸手拿网络料");
        assertEquals(3_000_000L, network.get(V1), "网络存量一分没动");
        assertEquals(BigInteger.valueOf(2_999L),
                job.batchObligations.pendingFirings(THOUSAND_A_FIRING), "欠账继续等料");
    }

    @Test
    void obligationRidesTheTaskEntryNbtAndStaysAbsentWhenNothingIsOwed() {
        var owed = new BigInteger("100000000000000000000000000");
        var item = new CompoundTag();
        ParallelExecutingCraftingJob.writeTaskProgress(item, progress(7L));
        BatchObligationLedger.writeEntry(item, owed);
        assertTrue(item.contains(BatchObligationLedger.NBT_ENTRY_OBLIGATION_WIDE, Tag.TAG_BYTE_ARRAY));
        assertEquals(owed, BatchObligationLedger.readEntry(item));

        var bare = new CompoundTag();
        ParallelExecutingCraftingJob.writeTaskProgress(bare, progress(7L));
        BatchObligationLedger.writeEntry(bare, BigInteger.ZERO);
        assertFalse(bare.contains(BatchObligationLedger.NBT_ENTRY_OBLIGATION_WIDE,
                Tag.TAG_BYTE_ARRAY), "常态零欠账就一个键都不挂，不是迁移壳");
        assertEquals(BigInteger.ZERO, BatchObligationLedger.readEntry(bare));
    }

    /**
     * 「网络对同一笔请求 SIMULATE 答应、MODULATE 一枚都交不出」这一型：实机是
     * {@code minecraft:lava} 与 {@code ae2:matter_ball} —— stall 行
     * {@code alloc=[… left=Long.MAX taken=0 stored=0 toMake=0 src=NONE]}，全员等一份
     * 永远不会从网络来的料。无限元件在计划出门时看不见（它不在任何单元里），只能在真取
     * 那一次按<b>行为</b>认下来。
     *
     * <p>认下之后这一条腿<b>收手</b>：不向网络空抽、也不把需要量注进池子。注进池子那一写法
     * 会留下"本批没吃完的那一截"，而它唯一的去处是 {@code storeItems()} 投回网络 = 凭空多一份料
     * （本仓已因此回退过一次）。键交回作业后，下一 tick 的配给表认得它，取料点就地认量。
     */
    @Test
    void refillYieldsToTheTakePointInsteadOfMintingIntoThePool() {
        var buffer = variantPool(0L, 0L);
        var network = new KeyCounter();
        var claims = new KeyCounter();
        claims.add(V1, Long.MAX_VALUE);
        var lying = new ClaimingRefill(network, claims);

        long pulled = ParallelBatchExtraction.refillObligationBatch(
                THOUSAND_A_FIRING, buffer, null, 4L, RESERVATION_FREE, lying);

        assertEquals(0L, pulled, "认下即收手：这一格一枚都不搬");
        assertEquals(0L, buffer.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE),
                "凭空的量不许进池子：进池即被 storeItems() 当残池投回网络");
        assertEquals(Set.of(V1), lying.noted, "认中的键要交回作业，下一批取料点就地认量");
    }

    /**
     * 反面（判据的另一半）：向网络<b>报满 {@code Long.MAX_VALUE}</b> 的有限盘一定真交得出料。
     *
     * <p>实机形态是 {@code mekanism:redstone}（网络 1.47e20 枚）与 neoecoae／ExtendedAE_Plus
     * 那类宽域单元——用户给的这条事实就是前面按报量判无限被回退的原因。这里让它一边真给出
     * 1000 枚、一边 SIMULATE 仍答满 long，缺的那 3000 枚<b>不许</b>凭空注入。
     */
    @Test
    void hugeFiniteNetworkDeliveryIsNeverMinted() {
        var buffer = variantPool(0L, 0L);
        var network = new KeyCounter();
        network.add(V1, 1_000L);
        var claims = new KeyCounter();
        claims.add(V1, Long.MAX_VALUE);
        var finite = new ClaimingRefill(network, claims);

        long pulled = ParallelBatchExtraction.refillObligationBatch(
                THOUSAND_A_FIRING, buffer, null, 4L, RESERVATION_FREE, finite);

        assertEquals(1_000L, pulled, "有限盘给多少算多少，缺的 3000 枚不造");
        assertEquals(1_000L, buffer.extract(V1, Long.MAX_VALUE, Actionable.SIMULATE));
        assertTrue(finite.noted.isEmpty(), "真交得出料的键不能被认成无限源");
    }

    /** 就地认下的无限源按物品本体族放行：岩浆的同族变体也不能再走网络提取。 */
    @Test
    void runtimeDiscoveryMakesTheKeyUnboundedForTheRestOfTheJob() {
        var job = newJob();
        assertFalse(job.isUnboundedSupply(V1));

        job.noteUnboundedSupply(V1);

        assertTrue(job.isUnboundedSupply(V1));
        assertTrue(job.isUnboundedSupply(V2), "与规划侧同一把尺子：按本体族判");
    }

    // ==================================================================================
    // 回补侧候选顺位要跟取料点一致（改动 1）：首位候选配额已尽 ⇒ continue 到备选，
    // 不是 break 把整格欠账冻结（实机 2026-09-26 01:37 一条 WARN：obChunks=0 / 首位候选 配额=0）
    // ==================================================================================

    /**
     * 首位候选（V1）族配额连一发都付不出、池子里却躺着巨量：取料点 {@code primaryCandidate} 会靠
     * {@code executionsInsideFamilyQuota} 交回 0 而退到备选，回补侧必须用同一条可花判据退到备选，
     * 否则备选键永远进不了池子、取料点也永远挑不到它 ⇒ 该格欠账冻结。
     *
     * <p><b>旧实现下这条必红</b>：{@code exactOnSite(V1) >= exactNeed} 直接 break，BUCKET 一枚不进池子。
     */
    @Test
    void refillFallsToNextCandidateWhenFirstCandidatesQuotaIsSpent() {
        var pattern = TWO_FAMILY_TWO_CANDIDATE;
        var job = newJob();
        job.tasks.put(pattern, progress(100L));
        // 首位候选 V1 <b>在册、族配额已销穿</b>（配给 1 枚、已吃 1 枚 ⇒ 份额 0）→ quotaExact(V1)=0 → 不可花。
        // 这一档必须是"在册且花光"，不能再用"V1 不在表里"当代理：后者是"没配额口径"（quotaExact 交
        // null ⇒ 让回补伸手），正是本刀与旧判据分家的地方——"没口径"与"份额用完"两型不许互换。
        var plan = new WideKeyCounter();
        plan.setExact(V1, BigInteger.ONE);
        plan.setExact(BUCKET, BigInteger.valueOf(1000L));
        MaterialAllocationFixtures.assign(job, pattern, plan);
        job.addPlanMaterialTaken(pattern, V1, BigInteger.ONE);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        // 池子里只有首位候选 V1（远超本批 100 发的 need），备选 BUCKET 为 0
        var pool = widePool();
        pool.insertExact(V1, BigInteger.valueOf(1_000_000L), Actionable.MODULATE);
        // 假回补通道里只有备选 BUCKET 有货
        var network = new WideNetwork();
        network.add(BUCKET, BigInteger.valueOf(100L));

        var probe = new ParallelBatchExtraction.Probe();
        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 100L,
                reservation, network.refill(), probe);
        assertTrue(pulled > 0L,
                "首位候选配额已尽必须退到备选真补料，而不是 break 冻结（实机 obChunks=0 那条路）");
        assertTrue(probe.refillChunks > 0,
                "回补刀数必须 >0：旧实现此处 exactOnSite>=exactNeed 直接 break，备选永远进不来");
        assertEquals(BigInteger.valueOf(100L), WideCraftingInventory.amount(pool, BUCKET),
                "备选 BUCKET 被真补进池子");

        job.batchObligations.incur(pattern, BigInteger.valueOf(100L));
        long payable = ParallelBatchExtraction.obligationBatchFirings(pattern,
                job.batchObligations.pendingFirings(pattern));
        long paid = ParallelCraftingCpuLogic.payObligationBatch(job, pattern, payable, pool, null,
                reservation, new ParallelPoolLedger(), network.refill(), null);
        assertTrue(paid >= 1L, "备选进池后取料点就挑得到它：交割必须推进 ≥1 发");
    }

    /**
     * S1①：样板在配给表里、<b>这一族完全不在表里</b> ⇒ {@code quotaExact} 交 {@code null}
     * （没有配额口径，<b>不是</b>份额用完）⇒ 回补真的伸手。<b>旧实现交 {@code 0}</b> ⇒
     * {@code cellPayableFirings<1} ⇒ {@code continue} ⇒ {@code pulled=0} ⇒ 本用例红。
     * 判据复用 {@code ParallelPlanReservation#hasFamilyEntry}（与 {@code hasQuotaBasis} 同一条）。
     */
    @Test
    void familyAbsentFromPlanTableGivesRefillNoQuotaBasisToReachWith() {
        var pattern = ONE_UNIT_A_FIRING;   // 单候选 V1、每发 1 枚
        var job = newJob();
        job.tasks.put(pattern, progress(100L));
        // 表非空、却只记了<b>另一个族</b> BUCKET：V1 这一族压根没被配给。
        var plan = new WideKeyCounter();
        plan.setExact(BUCKET, BigInteger.valueOf(1000L));
        MaterialAllocationFixtures.assign(job, pattern, plan);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        assertNull(reservation.quotaExact(V1, pattern, pattern.getInputs()[0]),
                "样板在表里、这一族不在 ⇒ 没有配额口径（null），不是份额用完（0）");

        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, BigInteger.valueOf(100L));
        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 100L,
                reservation, network.refill(), null);
        assertTrue(pulled > 0L,
                "没有配额口径就不夹：回补必须真向网络伸手取 V1（旧实现交 0 ⇒ continue ⇒ pulled=0）");
    }

    /**
     * S1②（控制臂，防把两型一起放开）：这一族<b>在册、条目值为 0</b>（{@code hasFamilyEntry} 认 0 值条目）
     * ⇒ {@code quotaExact} 交 {@code 0}（夹死）⇒ 回补不伸手。02eda14 钉死"0=夹死"，两型各归各位。
     */
    @Test
    void familyInRegisterWithZeroShareStillClampsRefill() {
        var pattern = ONE_UNIT_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(100L));
        var plan = new WideKeyCounter();
        plan.setExact(V1, BigInteger.ZERO);                 // 在册、份额 0
        plan.setExact(BUCKET, BigInteger.valueOf(1000L));   // 排除"整本被跳过"
        MaterialAllocationFixtures.assign(job, pattern, plan);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        var quota = reservation.quotaExact(V1, pattern, pattern.getInputs()[0]);
        assertNotNull(quota, "在册（含 0 值条目）⇒ 有配额口径，不是 null");
        assertEquals(0, quota.signum(), "份额 0 ⇒ 夹成 0：与'没口径'那一型分家，不许一起放开");

        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, BigInteger.valueOf(100L));
        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 100L,
                reservation, network.refill(), null);
        assertEquals(0L, pulled, "份额 0 的族照样夹死：回补不许伸手");
    }

    /**
     * S1③：这一族在册、份额 &gt;0 但<b>已销穿</b>（已吃 ≥ 配给）⇒ {@code quotaExact} 夹成 {@code 0}，
     * 回补不伸手。这是"许过且已花光"的形状，与 S1①"从没许过这一族"（null）分家；
     * {@code over=} 那一型走 attach 事后封顶，不受这一刀放开影响。
     */
    @Test
    void familyWrittenOffBeyondAllocationStillClampsToZero() {
        var pattern = ONE_UNIT_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(100L));
        var plan = new WideKeyCounter();
        plan.setExact(V1, BigInteger.valueOf(3L));
        MaterialAllocationFixtures.assign(job, pattern, plan);
        job.addPlanMaterialTaken(pattern, V1, BigInteger.valueOf(5L));   // 已吃 5 > 配给 3 ⇒ 销穿
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        var quota = reservation.quotaExact(V1, pattern, pattern.getInputs()[0]);
        assertNotNull(quota, "在册 ⇒ 有配额口径（不是没口径的 null）");
        assertEquals(0, quota.signum(), "已销穿 ⇒ 夹成 0");

        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, BigInteger.valueOf(100L));
        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 100L,
                reservation, network.refill(), null);
        assertEquals(0L, pulled, "份额已销穿 ⇒ 回补不伸手");
    }

    /**
     * M1/M2 端到端主臂：模糊槽的<b>执行侧顺位第一候选现场量为 0</b>（池里没有），但计划要用的那一格
     * 其键根本没被 {@code validateFuzzyDrain} 的排水付到（规划 ranked 把备选排在前面先付清）⇒
     * 旧实现下这一族不在配给表 ⇒ {@code quotaExact} 两臂读数不一致（{@code slot!=null} 交 {@code null}、
     * {@code slot==null} 交 {@code 0}）⇒ 取料点夹 0 ⇒ 补进池子也取不走 ⇒ {@code executions=0}。
     *
     * <p>修复后 {@code buildMaterialPlan} 把这一格真会伸手的键（{@code SlotAccount#firstCandidate}）
     * 连同未付份数记进表 ⇒ 两臂同一真份额 ⇒ {@code refillObligationBatch} 真补进池子 ⇒
     * {@code prepareUniform} 真取到 ⇒ {@code executions > 0}。
     */
    @Test
    void fuzzyReachKeyZeroOnSiteShipsToQuotaTableAndExtractsEndToEnd() {
        var consumer = reachKeyConsumer();
        var graph = reachKeyFuzzyGraph(consumer, false);   // 规划 ranked: [IN 备选, REACH 首位]
        var table = MaterialClosureGate.buildMaterialPlan(reachKeyState(consumer), graph, 2L).assigned();
        assertFalse(table.isEmpty(), () -> "跨候选回落的整槽表不能扣下: " + table);

        var job = newJob();
        job.tasks.put(consumer, progress(2L));
        job.materialAllocation.adopt(table, Map.of());
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var slot = consumer.getInputs()[0];

        // 两臂同真值：带槽的伸手口径与不带槽的销账口径必须读同一份族份额（旧实现一 null 一 0）。
        var withSlot = reservation.quotaExact(REACH, consumer, slot);
        var withoutSlot = reservation.quotaExact(REACH, consumer, null);
        assertNotNull(withSlot, "补记后这一族在表里 ⇒ 有配额口径（不是没口径的 null）");
        assertNotNull(withoutSlot, "取料点臂不许把补记的族读成没口径");
        assertEquals(withSlot.exactValue(), withoutSlot.exactValue(),
                "两条臂必须读同一份真份额——修一条腿留一条腿正是本刀要消掉的形状");
        assertTrue(withoutSlot.signum() > 0, () -> "补记的份额必须为正: " + withSlot);

        var pool = widePool();   // REACH / IN 现场量都 0
        var network = new WideNetwork();
        network.add(REACH, BigInteger.valueOf(2L));
        long pulled = ParallelBatchExtraction.refillObligationBatch(consumer, pool, null, 2L,
                reservation, network.refill(), null);
        assertTrue(pulled > 0L, () -> "回补真伸手把首位候选补进池子, pulled=" + pulled);

        var batch = ParallelBatchExtraction.prepare(consumer, pool, null, 2L,
                new KeyCounter(), new KeyCounter(), reservation);
        assertNotNull(batch, "取料点必须挑得到补进来的首位候选（旧实现夹 0 ⇒ 整批 null）");
        assertTrue(batch.executions() > 0L, () -> "端到端必须推进 ≥1 发, executions="
                + (batch == null ? "null" : batch.executions()));
    }

    /**
     * M2 对照臂：该格现场量充足、排水付得出 ⇒ 记账与旧实现<b>逐字相同</b>。
     *
     * <p>首位候选 {@code REACH} 就是规划 ranked 的第一位、自己盖住整槽需求 ⇒ 排水真付过它 ⇒
     * {@code billReachKeySlot} 见族已在册就不动 ⇒ 只记 {@code REACH} 一笔、绝不把备选 {@code IN}
     * 也写上（防把"补记未付"做成"整格重写"）。
     */
    @Test
    void fuzzyReachKeyPaidByDrainShipsIdenticalTableAsBefore() {
        var consumer = reachKeyConsumer();
        var graph = reachKeyFuzzyGraph(consumer, true);    // 规划 ranked: [REACH 首位, IN 备选]
        var table = MaterialClosureGate.buildMaterialPlan(reachKeyState(consumer), graph, 2L).assigned();
        assertFalse(table.isEmpty(), () -> "首位候选付得出时表照常下发: " + table);

        var share = table.get(consumer);
        assertNotNull(share);
        assertEquals(2L, share.getLong(REACH), "首位候选被排水真付过的那一截逐字不变");
        assertEquals(0L, share.getLong(IN), "备选没被'整格重写'顺手记上：补记只碰这一格真会伸手的键");
    }

    /**
     * M3（纯读数）：{@code planBalance=} 那一行点名"这一格真会伸手、却被 {@code quotaExact} 判成没配额
     * 口径"的族，带 {@code assigned}/{@code eaten} 两笔宽域真值——一眼分出"配给没记这族"。
     *
     * <p>摘掉 {@code appendNoBasisReachKeys} 的那段 append 即红（读数不出现 ⇒ 本用例的 {@code contains}
     * 断言失败）；它不参与任何判据，所以这里只验字符串形状。
     */
    @Test
    void planBalanceNamesReachKeysWithNoQuotaBasis() {
        var pattern = ONE_UNIT_A_FIRING;   // 单候选 V1、每发 1 枚
        var job = newJob();
        job.tasks.put(pattern, progress(100L));
        // 表非空、却只记了<b>另一个族</b> BUCKET：V1 这一族压根没被配给 ⇒ quotaExact 交 null（没口径）。
        var plan = new WideKeyCounter();
        plan.setExact(BUCKET, BigInteger.valueOf(1000L));
        MaterialAllocationFixtures.assign(job, pattern, plan);

        var summary = new StringBuilder(" planBalance=[");
        ParallelCraftingCpuLogic.appendNoBasisReachKeys(summary, job,
                job.tasks.entrySet(), Map.of());
        String line = summary.toString();
        assertTrue(line.contains("noBasisReach="), () -> "没口径的伸手键要被点名: " + line);
        assertTrue(line.contains(String.valueOf(V1)), () -> "点名要带这一族的键: " + line);
        assertTrue(line.contains("assigned=") && line.contains("eaten="),
                () -> "读数要带 assigned/eaten 两个宽域真值: " + line);
    }

    /** 对照：这一族<b>在册</b>（有配额口径）时不进 {@code noBasisReach=} 读数（只读没口径那一型）。 */
    @Test
    void planBalanceOmitsReachKeysThatHaveAQuotaBasis() {
        var pattern = ONE_UNIT_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(100L));
        var plan = new WideKeyCounter();
        plan.setExact(V1, BigInteger.valueOf(100L));   // V1 在册 ⇒ quotaExact 非 null
        MaterialAllocationFixtures.assign(job, pattern, plan);

        var summary = new StringBuilder(" planBalance=[");
        ParallelCraftingCpuLogic.appendNoBasisReachKeys(summary, job,
                job.tasks.entrySet(), Map.of());
        assertFalse(summary.toString().contains("noBasisReach="),
                () -> "有配额口径的伸手键不许被读成没口径: " + summary);
    }

    private static final AEItemKey REACH = AEItemKey.of(Items.GRAVEL);
    private static final AEItemKey IN = AEItemKey.of(Items.COBBLESTONE);

    /** 消费样板：AE2 原始候选顺位 [REACH 首位, IN 备选]，每发各 1 枚，产出 PRODUCT×1。 */
    private static FakePattern reachKeyConsumer() {
        return new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(REACH, 1L), new GenericStack(IN, 1L)},
                        1L, null, Set.of(REACH, IN))},
                List.of(new GenericStack(PRODUCT, 1)));
    }

    private static GraphPlanningState reachKeyState(FakePattern consumer) {
        var state = new GraphPlanningState(Map.of(REACH, 10L, IN, 10L), Map.of());
        state.addCrafting(consumer, 2L);
        state.extract(REACH, 10L, false);
        state.extract(IN, 10L, false);
        return state;
    }

    private static CraftGraph reachKeyFuzzyGraph(FakePattern consumer, boolean reachFirst) {
        var graphPattern = new GraphPattern(consumer, PRODUCT, 1L,
                List.of(new GenericStack(PRODUCT, 1L)),
                List.of(new GraphInput(REACH, 1L, 1L, GraphInput.Mode.NORMAL, null,
                        reachFirst
                                ? List.of(new GraphInput.Candidate(REACH, 1L),
                                        new GraphInput.Candidate(IN, 1L))
                                : List.of(new GraphInput.Candidate(IN, 1L),
                                        new GraphInput.Candidate(REACH, 1L)),
                        Set.of(REACH, IN), false)));
        return new CraftGraph(PRODUCT,
                Map.of(PRODUCT, List.of(graphPattern)),
                Map.of(consumer, graphPattern),
                List.of(consumer),
                Map.of(), Map.of(), Map.of(),
                Map.of(REACH, 10L, IN, 10L),
                java.util.Set.of(), Map.of(), false, null, 1, 1);
    }

    /**
     * 反向守卫：首位候选<b>可花</b>且池内已够本批 ⇒ 回补一枚都不许多搬（旧实现的 break 语义要留住）。
     *
     * <p>这条守的是"别把退让做成多拿料"：可花的撑满候选仍就地收手，退让只发生在首位候选花不出去时。
     */
    @Test
    void refillStopsAtSpendableFirstCandidateCoveringTheBatch() {
        var pattern = TWO_FAMILY_TWO_CANDIDATE;
        var job = newJob();
        job.tasks.put(pattern, progress(100L));
        var plan = new WideKeyCounter();
        plan.setExact(V1, BigInteger.valueOf(1000L));   // 首位候选可花
        plan.setExact(BUCKET, BigInteger.valueOf(1000L));
        MaterialAllocationFixtures.assign(job, pattern, plan);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        var pool = widePool();
        pool.insertExact(V1, BigInteger.valueOf(1_000_000L), Actionable.MODULATE);
        var network = new WideNetwork();
        network.add(V1, BigInteger.valueOf(1_000_000L));
        network.add(BUCKET, BigInteger.valueOf(100L));  // 诱饵：不该被搬

        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 100L,
                reservation, network.refill(), null);
        assertEquals(0L, pulled, "首位候选可花且池内已够本批：一枚都不许多搬");
        assertEquals(BigInteger.valueOf(100L), network.amountOf(BUCKET),
                "退让不能被做成多拿料：备选诱饵一枚不许动");

        job.batchObligations.incur(pattern, BigInteger.valueOf(100L));
        long payable = ParallelBatchExtraction.obligationBatchFirings(pattern,
                job.batchObligations.pendingFirings(pattern));
        long paid = ParallelCraftingCpuLogic.payObligationBatch(job, pattern, payable, pool, null,
                reservation, new ParallelPoolLedger(), network.refill(), null);
        assertTrue(paid >= 1L, "交割仍由可花的首位候选 A 完成");
        assertEquals(0L, WideCraftingInventory.amount(pool, BUCKET).signum(),
                "备选 B 始终没进池子：交割吃的是 A");
    }

    /**
     * 可花判据的分母是<b>该族在整张样板全部槽上的每发合计</b>，不是本槽那一格那 4 枚。
     *
     * <p>形状：槽0 首位 {@code V1 x4}（备选 {@code BUCKET x1}）、槽1 {@code V1 x4} ⇒ 这一族每发合计
     * 8 枚，族剩配额只有 6。旧口径按<b>本槽</b> 4 枚判"可花"（{@code 4 ≤ 6}），池子里已躺着 4 枚
     * ⇒ 直接 {@code break} ⇒ 一枚都不补；取料点那边 {@code primaryCandidate} 用的是
     * {@code executionsInsideFamilyQuota}（{@code 6 ÷ 8 = 0}）⇒ 第二槽退备选键，而备选键从没进过
     * 池子 ⇒ {@code usableTemplates} 挑不到它 ⇒ 该格 {@code obChunks=0} 冻结。
     *
     * <p>新口径判"不可花"⇒ {@code continue} 到备选 ⇒ {@code BUCKET} 被真补进池子，取料点下一趟
     * 就挑得到它。分母只可能变大（每槽取该族候选里的最大项、跨槽相加），所以这条只会更少搬料、
     * 不会多搬：{@code V1} 那 4 枚一枚不动。
     */
    @Test
    void spendableDenominatorIsTheWholePatternFamilyAskNotASingleSlot() {
        var pattern = TWIN_FAMILY_FOUR_WITH_FALLBACK;
        var job = newJob();
        job.tasks.put(pattern, progress(1L));
        var plan = new WideKeyCounter();
        plan.setExact(V1, BigInteger.valueOf(6L));        // 族剩配额 6 < 该族每发合计 8
        plan.setExact(BUCKET, BigInteger.valueOf(100L));  // 备选键自己有份额，花得出去
        MaterialAllocationFixtures.assign(job, pattern, plan);
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        var pool = widePool();
        pool.insertExact(V1, BigInteger.valueOf(4L), Actionable.MODULATE);
        var network = new WideNetwork();
        network.add(BUCKET, BigInteger.valueOf(10L));

        var probe = new ParallelBatchExtraction.Probe();
        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 1L,
                reservation, network.refill(), probe);

        assertEquals(1L, pulled,
                "族剩配额 6 付不出该族每发合计 8：首位候选不可花必须 continue 到备选真补 1 枚");
        assertEquals(1L, probe.refillChunks, "只补备选键这一刀：不可花的首位候选一枚都不搬");
        assertEquals(BigInteger.ONE, WideCraftingInventory.amount(pool, BUCKET),
                "备选键进池子，取料点下一趟才挑得到它（旧口径在这里 break => 永冻结）");
        assertEquals(BigInteger.valueOf(4L), WideCraftingInventory.amount(pool, V1),
                "保守方向：不可花的那一族一枚都不许多搬");
    }

    /**
     * 可花判据的分母<b>只数取料点真会吃的槽</b>：同族在两格槽上都列着，但其中一格是原样退回的
     * 模具槽（{@code selfRepaying}）、另一型的槽1 对候选 {@code isValid=false}
     * ⇒ 取料点的族每发合计只算真吃的那一格（槽0 的 4 枚），上界口径必须<b>同样</b>只算它。
     *
     * <p>过计的后果不是超申造料（搬进量仍被族剩配额夹住），而是<b>首位候选被恒判"不可花"</b>
     * ＋取料点仍按精确分母持续消耗它：
     * <ul>
     *   <li>模具槽型：分母 4→8 ⇒ 族配额 4 判不可花 ⇒ 槽0 被逼改道把备选族 {@code BUCKET}
     *       拖进池子（白搬 1 枚），旧实现 {@code pulled=5}／{@code refillChunks=2}；</li>
     *   <li>不收候选型：同样 4→8 ⇒ 槽0 没有备选、一枚都不补（旧实现 {@code pulled=0}）
     *       ⇒ 池子里永远没有 V1，该槽见底即冻结。</li>
     * </ul>
     * 各摘掉 {@code exactAskPerFiring} 里对应的那一条收/不收，相应那一型当场变红。
     */
    @Test
    void spendableDenominatorCountsOnlySlotsTheExtractionActuallyUses() {
        // —— 型一：槽1 是原样退回的模具槽（remainder == V1），取料点不为它扣族配额
        var mold = TWIN_FAMILY_FOUR_MOLD_SLOT;
        var moldJob = newJob();
        moldJob.tasks.put(mold, progress(1L));
        var moldPlan = new WideKeyCounter();
        moldPlan.setExact(V1, BigInteger.valueOf(4L));        // 恰好够取料点那份真分母（4 枚）
        moldPlan.setExact(BUCKET, BigInteger.valueOf(100L));  // 备选族自己有份额：白搬看得见
        MaterialAllocationFixtures.assign(moldJob, mold, moldPlan);
        var moldReservation = ParallelPlanReservation.build(moldJob.tasks.entrySet(), Map.of(),
                moldJob.materialAllocation);

        assertEquals(BigInteger.valueOf(4L),
                ParallelBatchExtraction.wholePatternAskPerFiring(mold, V1, true, null),
                "模具槽（selfRepaying）不计入该族每发合计：quotaExact 对它交回 null，取料点不累加它");
        assertEquals(BigInteger.ONE, ParallelBatchExtraction.cellPayableFirings(
                BigInteger.valueOf(4L), BigInteger.valueOf(4L), BigInteger.valueOf(4L),
                ParallelBatchExtraction.wholePatternAskPerFiring(mold, V1, true, null)),
                "族配额 4 == 取料点真分母 4 ⇒ 这一格算得出 1 发（分母过计成 8 就恒算 0 发）");
        assertEquals(BigInteger.ZERO, ParallelBatchExtraction.cellPayableFirings(
                BigInteger.valueOf(4L), BigInteger.valueOf(4L), BigInteger.valueOf(3L),
                ParallelBatchExtraction.wholePatternAskPerFiring(mold, V1, true, null)),
                "这一条不是恒真：配额 3 连真分母那一发都付不出，算出来就是 0 发");

        var moldPool = widePool();
        var moldNetwork = new WideNetwork();
        moldNetwork.add(V1, BigInteger.valueOf(100L));
        moldNetwork.add(BUCKET, BigInteger.valueOf(100L));
        var moldProbe = new ParallelBatchExtraction.Probe();
        long moldPulled = ParallelBatchExtraction.refillObligationBatch(mold, moldPool, null, 1L,
                moldReservation, moldNetwork.refill(), moldProbe);
        assertEquals(4L, moldPulled,
                "补的是首位候选真需的那 4 枚，不是「首位 4 ＋ 备选 1」的改道合计");
        assertEquals(1L, moldProbe.refillChunks, "只补 V1 这一刀：首位候选可花就不该退备选");
        assertEquals(0, WideCraftingInventory.amount(moldPool, BUCKET).signum(),
                "备选族 BUCKET 一枚都不许被白搬进池子");
        assertEquals(BigInteger.valueOf(100L), moldNetwork.amountOf(BUCKET),
                "网络上 BUCKET 原样不动：白搬连网络侧读数都不许漂");

        // —— 型二：槽1 声明了 V1 但对它 isValid=false，取料点的候选宇宙里没有它
        var invalid = TWIN_FAMILY_FOUR_INVALID_SECOND;
        var invalidJob = newJob();
        invalidJob.tasks.put(invalid, progress(1L));
        MaterialAllocationFixtures.assign(invalidJob, invalid, wideShare(V1, BigInteger.valueOf(4L)));
        var invalidReservation = ParallelPlanReservation.build(invalidJob.tasks.entrySet(), Map.of(),
                invalidJob.materialAllocation);

        assertEquals(BigInteger.valueOf(4L),
                ParallelBatchExtraction.wholePatternAskPerFiring(invalid, V1, true, null),
                "槽1 对该候选 isValid=false ⇒ 不计入：usableTemplates 那一侧压根递不出它");
        assertEquals(BigInteger.ONE, ParallelBatchExtraction.cellPayableFirings(
                BigInteger.valueOf(4L), BigInteger.valueOf(4L), BigInteger.valueOf(4L),
                ParallelBatchExtraction.wholePatternAskPerFiring(invalid, V1, true, null)),
                "族配额 4 == 取料点真分母 4 ⇒ 这一格算得出 1 发（过计成 8 就恒算 0 发）");

        var invalidPool = widePool();
        var invalidNetwork = new WideNetwork();
        invalidNetwork.add(V1, BigInteger.valueOf(100L));
        var invalidProbe = new ParallelBatchExtraction.Probe();
        long invalidPulled = ParallelBatchExtraction.refillObligationBatch(invalid, invalidPool,
                null, 1L, invalidReservation, invalidNetwork.refill(), invalidProbe);
        assertEquals(4L, invalidPulled,
                "槽0 没有备选：恒判不可花就是该格一枚都不补（旧口径 pulled=0），见底即冻结");
        assertEquals(1L, invalidProbe.refillChunks, "补 V1 这一刀");
        assertEquals(BigInteger.valueOf(4L), WideCraftingInventory.amount(invalidPool, V1),
                "真分母那一发的料进了池子，取料点下一趟才挑得到");
    }

    /**
     * 分子与分母<b>同一个 scope</b>：分母是整张样板对该键的每发合计（8 枚），分子就必须是整张样板
     * 本批对该键的真需（8 × 份数），不能拿"本槽那 4 枚"去除整张的 8 枚。
     *
     * <p>形状：{@code V1} 被这张样板的<b>两个真槽各吃 4 枚</b>（槽1 没有备选可退）、族配额足够。
     * 混 scope 的写法交回 {@code 4 ÷ 8 = 0} 发 ⇒ 判"补了也没用" ⇒ {@code continue} ⇒ 这一键
     * <b>一枚都不补</b>（旧形状 pulled=0）⇒ 池子见底即冻结、义务账永不可付。
     *
     * <p>补满的目标同口径换成整键真需（8 枚）：只补到本槽那 4 枚时，取料点按每发 8 枚照样算出
     * 0 发，冻结只是从回补侧挪到取料侧——所以最后一句断言问的是<b>这一发真付得出</b>。
     */
    @Test
    void refillNumeratorAndDenominatorShareTheSamePatternScope() {
        var pattern = new FakePattern(AEItemKey.of(Items.HOPPER),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 4L)},
                                1L, null, Set.of(V1)),
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 4L)},
                                1L, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));
        assertEquals(BigInteger.valueOf(8L),
                ParallelBatchExtraction.wholePatternAskPerFiring(pattern, V1, false, null),
                "同一个键被两个真槽各吃 4 枚 ⇒ 该键每发合计 8 枚（这一份就是分母）");

        var job = newJob();
        job.tasks.put(pattern, progress(1L));
        MaterialAllocationFixtures.assign(job, pattern, wideShare(V1, BigInteger.valueOf(100L))); // 族配额足够
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, BigInteger.valueOf(100L));
        var probe = new ParallelBatchExtraction.Probe();

        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 1L,
                reservation, network.refill(), probe);

        assertEquals(8L, pulled,
                "本槽 4 ÷ 整张 8 = 0 发那把刀把这一键判成\"补了也没用\"：旧形状这里 pulled=0，永冻结");
        assertEquals(1L, probe.refillChunks, "槽0 一次补到整键真需，槽1 见已撑满即收手");
        assertEquals(BigInteger.valueOf(8L), WideCraftingInventory.amount(pool, V1),
                "补的是整张样板这一发的真需 8 枚，不是本槽那 4 枚");
        assertNotNull(ParallelBatchExtraction.payDeclaredObligation(pattern, pool, null, 1L,
                reservation, null), "补进来的那一截取料点真付得出一发：回补与交割同一个 scope");
    }

    /**
     * 识别无限源时 probe 的必须是<b>同一笔</b>请求（改动 2）：伸手那几笔用的是夹过配额的 room，
     * 而 probe 也必须问同一个数，不能问原始 exactShort。
     *
     * <p>一旦在 {@code room < exactShort}（配额夹掉尾段）的形状上拿 exactShort 去 probe，就会在
     * "room 交不出、exactShort 却报得出"上误认无限源，此后走 {@code isUnboundedSupply} 早退 +
     * 取料点就地认量 = 凭空造料（红线：extendedae_plus/neoeco 报 {@code Long.MAX} 却不真无限）。
     */
    @Test
    void unboundedProbeAsksTheSameAmountAsTheRealPull() {
        var pattern = ONE_UNIT_A_FIRING; // 单候选 V1，每发 = 1×1 = 1 枚
        var job = newJob();
        job.tasks.put(pattern, progress(1L));
        // 族配额 5：可花（5 ≥ 每发 1 枚），但把 room 从 exactShort=100 夹到 5
        MaterialAllocationFixtures.assign(job, pattern, wideShare(V1, BigInteger.valueOf(5L)));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = widePool(); // V1 池内 = 0，exactShort = 100

        final long[] pullArg = {-1L};
        final long[] probeArg = {-1L};
        var spy = new ParallelBatchExtraction.ObligationRefill() {
            @Override
            public long pull(@Nullable AEKey what, long wanted) {
                pullArg[0] = wanted;
                return 0L; // 网络这一刀一枚都不给：触发下面的无限源识别
            }

            @Override
            public long probe(@Nullable AEKey what, long wanted) {
                probeArg[0] = wanted;
                return 0L;
            }
        };
        ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 100L, reservation, spy, null);

        assertEquals(5L, pullArg[0], "伸手的量是被配额夹过的 room(5)，不是原始 exactShort(100)");
        assertEquals(pullArg[0], probeArg[0],
                "probe 问的量必须 == 真正伸手的量：旧实现问 exactShort(100) 就会在 room<exactShort 上误认无限源");
    }

    /**
     * 跨槽同键那份窄域除法别把已算对的精确商夹回去（改动 3）：池子 >long 时，饱和 {@code stored /
     * 饱和 demand} = {@code Long.MAX/Long.MAX=1} 会把宽域真商（2 发）再夹小一次。
     */
    @Test
    void beyondLongSameKeyBatchIsNotHalvedByTheSaturatedNarrowDivision() {
        var pool = widePool();
        pool.insertExact(V1, HUGE_FIRING_UNITS.multiply(BigInteger.TWO), Actionable.MODULATE);
        // 每发真需 1.8e19 枚饱和成 Long.MAX、池子读数也饱和成 Long.MAX ⇒ 窄域商 = 1；宽域商 = 2
        var paid = ParallelBatchExtraction.payDeclaredObligation(HUGE_A_FIRING, pool, null, 2L,
                RESERVATION_FREE, null);
        assertNotNull(paid);
        assertEquals(2L, paid.executions(),
                "宽域真商 2 发不许被窄域 Long.MAX/Long.MAX=1 再夹一次：>long 池子 + 每发件数饱和的整批发数不折半");
    }

    // ==================================================================================
    // 一发真需 >long：跨批把物料累进（宽域）池子，凑齐一整发才付/才推
    // ==================================================================================

    /**
     * 这一发本身就装不进 {@code long}：模板量 {@code Long.MAX/2} 枚、倍率 4 ⇒ 真需
     * 18446744073709551612 枚。
     *
     * <p><b>2026-09-26 口径变更</b>：回补从"一轮每键只喂一刀"改成"同一格连着喂到凑齐、
     * 或本轮墙钟用完为止"（每刀仍 ≤long，AE2 边界一字未动；上限仍是这一格本批真需的那一截）。
     * 于是整发的两刀现在落在同一轮里，下面三条用例的"第一批 0 发、第二批才凑齐"改成
     * "第一批就凑齐并交割"。照 neoeco 的执行侧形状：账本每笔都是 long 口，无上限靠<b>流式轮次</b>，
     * 不靠一次搬完（{@code CycleResourceLedger} 五张表全是 {@code Map<AEKey,Long>}）。
     *
     * <p>今天没有一条通道能把它付出来：每发件数饱和成 {@code Long.MAX} 后被当量用，批次被夹成
     * 一发，提取口要么把饱和读数当成付出去了（假通过、真值丢在池外），要么整批归位返回 null
     * （真值永远凑不齐）。缺的是<b>一发拆成几刀付</b>：网络每批每键仍 ≤long，池子是宽域的
     * 所以装得下真值，跨批累到一整发才一次付掉、按真值销账。
     */
    @Test
    void wholeFiringBeyondLongIsPaidInChunksAndSettledAtItsTrueValue() {
        var job = newJob();
        job.tasks.put(HUGE_A_FIRING, progress(2L));
        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, HUGE_FIRING_UNITS);
        job.batchObligations.incur(HUGE_A_FIRING, BigInteger.ONE);
        long payable = ParallelBatchExtraction.obligationBatchFirings(HUGE_A_FIRING,
                job.batchObligations.pendingFirings(HUGE_A_FIRING));
        assertEquals(1L, payable, "一发的真值就装不进 long：一批只能算一发");

        // 第一轮：同一格连喂两刀凑齐整发并当场交割（旧形状这一轮只喂一刀 ⇒ 0 发）
        long first = ParallelCraftingCpuLogic.payObligationBatch(job, HUGE_A_FIRING, payable, pool,
                null, RESERVATION_FREE, new ParallelPoolLedger(), network.refill(), null);
        assertEquals(1L, first, "两刀在同一轮里凑齐整发：当场就该付出一发");
        assertEquals(0, job.batchObligations.pendingFirings(HUGE_A_FIRING).signum(),
                "欠账按真值销干净");
        assertEquals(HUGE_FIRING_UNITS, job.accountTaken.getExact(V1),
                "销账记的是这一发的真值（1.8e19 枚），不是 long 天花板");
        assertEquals(0, WideCraftingInventory.amount(pool, V1).signum(),
                "付完即清空：留在池子里的不是半发的残料");

        // 第二轮：欠账已清，一枚都不许再向网络伸手
        long second = ParallelCraftingCpuLogic.payObligationBatch(job, HUGE_A_FIRING, payable, pool,
                null, RESERVATION_FREE, new ParallelPoolLedger(), network.refill(), null);
        assertEquals(0L, second, "欠账清了就没有第二批");
        assertEquals(0, job.batchObligations.pendingFirings(HUGE_A_FIRING).signum(),
                "欠账按真值销干净");
        assertEquals(HUGE_FIRING_UNITS, job.accountTaken.getExact(V1),
                "销账记的是这一发的真值，不是 long 天花板");
        assertEquals(0, WideCraftingInventory.amount(pool, V1).signum(), "池子跟着真值清空");
        assertEquals(0, network.amountOf(V1).signum(), "网络上那一整发一枚不少地走进了池子");
    }

    /**
     * <b>带真配额的 >long 一发</b>（实机样板的常态：几乎每张表都带配额口径）：网络把整发分两批给，
     * 第二刀必须补得进池子、凑齐整发就付得出去，且该槽的后续候选一枚不许多搬。
     *
     * <p>改前死锁在 {@code refillObligationBatch} 的配额差上：配给读数顶在 {@code Long.MAX_VALUE}、
     * 池子的族现量读的是<b>主表</b>那份同样顶在天花板的饱和值，两个哨兵相减得 {@code 0}
     * ⇒ {@code want=0} ⇒ 第二刀永远进不了池子 ⇒ 这一发在带配额的样板上压根开不了工。
     */
    @Test
    void trueQuotaLetsTheSecondChunkIntoThePoolAndPaysTheWholeFiring() {
        var pattern = HUGE_TWO_CANDIDATES_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(2L));
        MaterialAllocationFixtures.assign(job, pattern,
                wideShare(V1, HUGE_FIRING_UNITS.multiply(BigInteger.TWO)));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var slot = pattern.getInputs()[0];
        long quotaReadout = reservation.quotaFor(V1, pattern, slot);
        assertTrue(quotaReadout >= 0L, "本用例走的是真配额口径：quotaFor 必须报得出份额（>=0）");
        assertEquals(Long.MAX_VALUE, quotaReadout,
                "3.69e19 枚的配给在 long 读数上顶到天花板：它是「宁高估」的哨兵，不是量");

        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, HUGE_FIRING_UNITS);
        network.add(V2, BAIT);
        job.batchObligations.incur(pattern, BigInteger.ONE);
        long payable = ParallelBatchExtraction.obligationBatchFirings(pattern,
                job.batchObligations.pendingFirings(pattern));
        assertEquals(1L, payable, "一发的真值就装不进 long：一批只算一发");

        // 第一轮：真配额减去池子真值还剩一大截 ⇒ 第二刀补得进来，整发当场凑齐
        //（改前：配给读数与池子读数都是天花板，相减得 0 ⇒ "还能补的那一截"被判成配额用尽）
        long first = ParallelCraftingCpuLogic.payObligationBatch(job, pattern, payable, pool, null,
                reservation, new ParallelPoolLedger(), network.refill(), null);
        assertEquals(1L, first,
                "带配额的第二刀补不进池子：饱和的配额读数减饱和的池子读数 = 0，把还能补的那一截判成配额用尽");
        assertEquals(0, job.batchObligations.pendingFirings(pattern).signum(), "欠账第一轮就清了");

        // 第二轮：欠账清了就不该再伸手，配额也只销整发那一截
        long second = ParallelCraftingCpuLogic.payObligationBatch(job, pattern, payable, pool, null,
                reservation, new ParallelPoolLedger(), network.refill(), null);
        assertEquals(0L, second, "没有欠账就没有第二批");
        assertEquals(0, job.batchObligations.pendingFirings(pattern).signum(), "欠账按真值销干净");
        assertEquals(HUGE_FIRING_UNITS, job.accountTaken.getExact(V1),
                "流水账记的是整发的真值，不是一刀 long 的读数");
        assertEquals(HUGE_FIRING_UNITS, MaterialAllocationFixtures.eatenSnapshot(job).get(pattern).getExact(V1),
                "配给账同样按整发的真值销");
        assertEquals(BAIT, network.amountOf(V2),
                "该槽后续候选被伸手就是本批多搬：多一枚都算红");
        assertEquals(0, WideCraftingInventory.amount(pool, V2).signum(), "后续候选一枚没进池子");
        assertEquals(0, WideCraftingInventory.amount(pool, V1).signum(), "池子跟着真值清空");
    }

    /**
     * 钉 {@code refillObligationBatch} 的凑齐判据：缺口用<b>真值</b>比。
     *
     * <p>改前 {@code supplied >= shortBy} 里的 {@code shortBy} 是饱和值 ⇒ 一个越出 {@code long}
     * 的缺口永远凑不齐 ⇒ 本轮喂进一刀之后仍顺着候选表往下走，把该槽后续候选按各自读到的配额
     * 继续往池子里搬。真值口径下这一轮只喂一刀，剩下的那一截由下一批接着凑。
     */
    @Test
    void overLongGapTakesOneChunkThisRoundAndNeverReachsForTheLaterCandidate() {
        var pattern = HUGE_TWO_CANDIDATES_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(1L));
        MaterialAllocationFixtures.assign(job, pattern,
                wideShare(V1, HUGE_FIRING_UNITS.multiply(BigInteger.TWO)));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, PARTIAL_CHUNK);
        network.add(V2, BAIT);

        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 1L,
                reservation, network.refill());

        assertEquals(PARTIAL_CHUNK.longValue(), pulled,
                "缺口比一个天花板还长：本轮只喂网络交得出的这一刀，不许顺着候选表再搬第二刀");
        assertEquals(PARTIAL_CHUNK, WideCraftingInventory.amount(pool, V1));
        assertEquals(BAIT, network.amountOf(V2), "后续候选被伸手 = 本批多搬");
        assertEquals(0, WideCraftingInventory.amount(pool, V2).signum());
        assertTrue(HUGE_FIRING_UNITS.subtract(PARTIAL_CHUNK)
                .compareTo(BIG_LONG) > 0, "本用例的缺口确实越出 long：饱和值在这里当量用才看得见");
    }

    /**
     * 反向守卫（真红的那一条）：<b>池子的饱和读数不许当量用</b>。
     *
     * <p>池子里放着 {@code Long.MAX_VALUE + 100} 枚——主表只报得出天花板，侧表那 100 枚只有宽域
     * 读口看得见。配额侧报的是同样的天花板，于是"配额 − 现量"在饱和域里得 {@code 0}，
     * 而真缺口是 {@code 9223372036854775705} 枚：把哨兵当了量，还能补的那一截就被判成配额用尽。
     */
    @Test
    void saturatedPoolReadoutIsNeverSpentAsTheQuotaQuantity() {
        var pattern = HUGE_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(1L));
        MaterialAllocationFixtures.assign(job, pattern,
                wideShare(V1, HUGE_FIRING_UNITS.multiply(BigInteger.TWO)));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        var pool = widePool();
        BigInteger already = BIG_LONG.add(BigInteger.valueOf(100L));
        pool.insertExact(V1, already, Actionable.MODULATE);
        assertEquals(Long.MAX_VALUE, pool.list.get(V1), "主表读数确实顶在天花板：那 100 枚挂在侧表");
        var network = new WideNetwork();
        network.add(V1, HUGE_FIRING_UNITS);
        BigInteger exactShort = HUGE_FIRING_UNITS.subtract(already);
        assertTrue(exactShort.compareTo(BIG_LONG) < 0, "缺口本身装得进 long：夹住它的只能是配额差");

        long pulled = ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 1L,
                reservation, network.refill());

        assertEquals(exactShort.longValue(), pulled,
                "真缺口 9223372036854775705 枚必须补得进来：饱和配额读数减饱和现量读数得 0 就是把哨兵当了量");
        assertEquals(HUGE_FIRING_UNITS, WideCraftingInventory.amount(pool, V1),
                "补完正好是一发的真值");
        assertEquals(HUGE_FIRING_UNITS.subtract(exactShort), network.amountOf(V1),
                "网络只走出缺口那一截");
    }

    /**
     * 回补那道"族配给 ∩ 真缺口"的夹子，池子的<b>族现量</b>必须读<b>侧表真值</b>。
     *
     * <p>配给按<b>物品本体族</b>记（{@code ParallelPlanReservation#shareOf}），所以做差的分子是
     * "这一族在池子里躺着多少枚"，不是"我要伸手那个精确键有多少"。同一族两个变体键时这两件事
     * 分得很开：本批要补的 V1 在池子里是 0，备选变体 V2 却压着<b>越出 {@code long}</b> 的一截。
     * 溢出键在 {@code KeyCounter} 主表上恒等于 {@code Long.MAX_VALUE}，那一截只挂在侧表
     * （{@link WideCraftingInventory#amount}）——拿主表读数做差就是少算了侧表那一截，
     * 于是"还能补的那一截"被读得<b>比配给真值还大</b>，多搬进来的每一枚都是本批多搬。
     *
     * <p><b>独立手算表</b>（{@code LONG = 9223372036854775807}）：
     * <table border=1>
     *   <tr><th>量</th><th>算式</th><th>值</th></tr>
     *   <tr><td>每发真需（倍率 4 × 模板量 LONG/2）</td>
     *       <td>{@code 4 × 4611686018427387903}</td><td>{@code 18446744073709551612}</td></tr>
     *   <tr><td>池子里 V2（同族备选键，走 {@code insertExact}）</td>
     *       <td>{@code LONG + 500}</td><td>{@code 9223372036854776307}</td></tr>
     *   <tr><td>该族配给（记在 V1 上，族合计口径）</td>
     *       <td>{@code 池内族现量 + 1e19}</td><td>{@code 19223372036854776307}</td></tr>
     *   <tr><td>本批真需（{@code payableFirings = 1}）</td>
     *       <td>{@code 18446744073709551612 × 1}</td><td>{@code 18446744073709551612}</td></tr>
     *   <tr><td>V1 真缺口</td><td>{@code 真需 − V1 现量(0)}</td><td>{@code 18446744073709551612}</td></tr>
     *   <tr><td>族配给还能补的那一截（<b>侧表真值</b>）</td>
     *       <td>{@code 19223372036854776307 − 9223372036854776307}</td>
     *       <td>{@code 10000000000000000000}</td></tr>
     *   <tr><td>这一格实际伸手的那一截 = min(上面两行)</td>
     *       <td>{@code min(1.8446744073709551612e19, 1e19)}</td>
     *       <td>{@code 10000000000000000000}</td></tr>
     *   <tr><td>拆成的刀数（每刀 ≤LONG）</td>
     *       <td>{@code 9223372036854775807 + 776627963145224193}</td><td>两刀</td></tr>
     *   <tr><td>网络上备下的量</td><td>{@code 1e19 + 5000}</td>
     *       <td>{@code 10000000000000005000} ⇒ 交割后剩 {@code 5000}</td></tr>
     * </table>
     *
     * <p>把族现量换成主表那份饱和读数（{@code LONG}）当量用，分子就多出侧表那 500 枚：
     * {@code 19223372036854776307 − 9223372036854775807 = 10000000000000000500} ⇒ 多搬 500 枚。
     */
    @Test
    void familyQuotaClampReadsTheSideTableNotTheSaturatedMainTable() {
        var pattern = HUGE_TWO_CANDIDATES_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(1L));
        // 侧表那一截：同族备选键 V2 上压着 LONG+500 枚，主表只报得出天花板。
        BigInteger sideTable = BIG_LONG.add(BigInteger.valueOf(500L));
        MaterialAllocationFixtures.assign(job, pattern, wideShare(V1, sideTable.add(new BigInteger("10000000000000000000"))));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);

        var pool = widePool();
        pool.insertExact(V2, sideTable, Actionable.MODULATE);
        assertEquals(Long.MAX_VALUE, pool.list.get(V2),
                "先确认主表读数顶在天花板：侧表那 500 枚只有宽域读口看得见");
        assertEquals(sideTable, WideCraftingInventory.amount(pool, V2), "宽域读口给出真值");
        assertEquals(0, WideCraftingInventory.amount(pool, V1).signum(), "本批要伸手的那个键一枚都没有");

        var network = new WideNetwork();
        network.add(V1, new BigInteger("10000000000000005000"));
        network.add(V2, BAIT);

        ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, BigInteger.ONE,
                reservation, network.refill(), null);

        assertEquals(new BigInteger("10000000000000000000"), WideCraftingInventory.amount(pool, V1),
                "族配给那一刀必须按池子的真值夹：拿主表的饱和读数做差就多搬侧表那 500 枚");
        assertEquals(new BigInteger("5000"), network.amountOf(V1),
                "网络上走掉的只有配给还能补的那一截");
        assertEquals(BAIT, network.amountOf(V2), "该槽后续候选一枚不许被伸手");
        assertEquals(sideTable, WideCraftingInventory.amount(pool, V2),
                "备选变体键在池子里的那一截原样不动：它是族现量，不是本批的付款键");
    }

    /**
     * 带<b>真配额</b>的越界任务：任务侧那一趟回补的定数必须按<b>精确</b>剩余发数算。
     *
     * <p>改前拿的是 {@code TaskProgress.value} 那份<b>饱和镜像</b>（越界任务上它就是
     * {@code Long.MAX_VALUE}）去乘"这一格本批真需几枚"：定数被夹在天花板那一格，池子补到
     * {@code Long.MAX_VALUE} 枚就永远显示"已撑满"，任务真需的 {@code 1.4e19} 枚里那
     * {@code 4.78e18} 枚<b>永远补不进来</b>。这就是"把哨兵当量用"——不是保守估计。
     *
     * <p>对照段钉住普通 {@code long} 域一条不变：剩余发数装得进 {@code long} 时，精确值与那份
     * 镜像逐字同一个数，回补量一位不差。
     */

    /**
     * 停滞行的<b>分刀推进读数</b>：一发真需 {@code >long} 时前几轮 {@code obOwed} 不动，
     * 但刀数与<b>累计真值</b>必须看得见在前进——否则日志把"正在累付"报成"停滞"。
     *
     * <p>只加读数：{@code obligationStalled} 这条判据一字未改（第一轮仍然如实报停滞），
     * 也没有任何自动取消/自动终止。
     */
    @Test
    void chunkedProgressIsToldApartFromNoProgressAtAll() {
        var pattern = HUGE_TWO_CANDIDATES_A_FIRING;
        var job = newJob();
        job.tasks.put(pattern, progress(2L));
        MaterialAllocationFixtures.assign(job, pattern,
                wideShare(V1, HUGE_FIRING_UNITS.multiply(BigInteger.TWO)));
        var reservation = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        assertTrue(reservation.quotaFor(V1, pattern, pattern.getInputs()[0]) >= 0L,
                "本用例走真配额口径");
        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, HUGE_FIRING_UNITS);
        network.add(V2, BAIT);
        job.batchObligations.incur(pattern, BigInteger.ONE);
        long payable = ParallelBatchExtraction.obligationBatchFirings(pattern,
                job.batchObligations.pendingFirings(pattern));

        // 一轮一个探针换成同一枚探针累计：这才对得上停滞行的算法（StallProbe 就是把一轮里的
        // 探针读数累起来），也让"老那份 long 合计读数冻住"与"新那格真值在前进"同框可比。
        var probe = new ParallelBatchExtraction.Probe();
        long first = ParallelCraftingCpuLogic.payObligationBatch(job, pattern, payable, pool, null,
                reservation, new ParallelPoolLedger(), network.refill(), probe);
        assertEquals(1L, first, "一轮里两刀凑齐整发：当场就该交割");
        assertEquals(2L, probe.refillChunks, "本轮补进两刀（每刀 ≤long）");
        assertEquals(HUGE_FIRING_UNITS, probe.refillExactUnits,
                "补进的真值合计 == 整发的真值：合计读数才是「在推进」的证据");
        assertTrue(!ParallelCraftingCpuLogic.obligationStalled(first,
                        job.batchObligations.pendingFirings(pattern)),
                "停滞判据一字未改：交割出去了就不算停滞");

        long second = ParallelCraftingCpuLogic.payObligationBatch(job, pattern, payable, pool, null,
                reservation, new ParallelPoolLedger(), network.refill(), probe);
        assertEquals(0L, second, "整发在第一轮就凑齐交割了：没有第二批");
        assertEquals(2L, probe.refillChunks, "两轮各补一刀");
        assertEquals(HUGE_FIRING_UNITS, probe.refillExactUnits,
                "回补的真值合计第二轮仍在前进：这就是「在分刀推进」");
        assertEquals(BIG_LONG.longValue(), probe.obligationRefillUnits,
                "两刀落在同一轮：这份 long 合计读数当场饱和在天花板（冻住），"
                        + "只有同批的真值格 refillExactUnits 说得出 1.8e19 枚——这就是它当不了推进证据的原因");
        assertEquals(1L, probe.paidChunks, "宽域库存一次扣除整发材料真值");
        assertEquals(HUGE_FIRING_UNITS, probe.paidExactUnits, "付出去的真值合计 == 整发的真值");
        assertEquals(BAIT, network.amountOf(V2), "该槽后续候选仍一枚不许被伸手");
    }

    /**
     * 产出侧那条的<b>判断</b>（不改判据、不宽化宿主契约）：申报被
     * {@code representableOutputCeiling} 夹成"申报 × 每发产出 ≤ long"，但
     * <b>分轮申报</b>本来就把 {@code >long} 的产出总量推进得完。
     *
     * <p>每发产出 {@code 4e18} 枚、任务 4 发 ⇒ 真值合计 {@code 1.6e19} 枚。一轮最多申报 2 发
     * （夹子仍生效，交出的产出账永远是精确数），交割完再申报第二轮 ⇒ 报称账与产出缓冲按
     * 宽域累计到真值。结论：{@code crafting/hostbatch/DeclaredBatchHost} 不需要宽化。
     */
    @Test
    void declaredRoundsDeliverOutputBeyondLongWithoutWideningTheHostContract() {
        var job = newJob();
        job.tasks.put(BULKY_OUTPUT_A_FIRING, progress(4L));
        var pool = widePool();
        pool.insertExact(V1, BigInteger.valueOf(4L), Actionable.MODULATE);
        var reservation = MaterialAllocationFixtures.build(job.tasks.entrySet(), Map.of(),
                NO_PLAN, NO_TAKEN);

        var declared = ParallelCraftingCpuLogic.declaredBatchFirings(new PlainHost(),
                BULKY_OUTPUT_A_FIRING, job.taskProgress(BULKY_OUTPUT_A_FIRING), pool, null,
                reservation, Long.MAX_VALUE, null);
        assertEquals(BigInteger.TWO, declared,
                "申报仍被「每发产出 × 份数 ≤ long」夹住：这一轮最多两发");

        for (int round = 0; round < 2; round++) {
            var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(new PlainHost(),
                    BULKY_OUTPUT_A_FIRING, job, job.tasks.entrySet(), pool, null, reservation,
                    new ParallelPoolLedger(),
                    new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, null,
                    null, null, null, 0L);
            assertNotNull(push, "第二轮申报必须在义务清干净之后：一张样板同时只挂一笔欠账");
            assertEquals(2L, push.accepted(), "每轮按夹后的份数收料");
            long paid = ParallelCraftingCpuLogic.payObligationBatch(job, BULKY_OUTPUT_A_FIRING,
                    ParallelBatchExtraction.obligationBatchFirings(BULKY_OUTPUT_A_FIRING,
                            job.batchObligations.pendingFirings(BULKY_OUTPUT_A_FIRING)),
                    pool, null, reservation, new ParallelPoolLedger(), null, null);
            assertEquals(1L, paid, "剩下那一发的材料按真值交割干净");
        }

        assertTrue(job.taskProgress(BULKY_OUTPUT_A_FIRING).isFinished(), "四发推进完了");
        assertEquals(new BigInteger("16000000000000000000"), job.accountAnnounced.getExact(PRODUCT),
                "报称产出跨轮累计到真值 1.6e19 枚：long 域一行的账，累计走宽域账本");
        assertEquals(new BigInteger("16000000000000000000"),
                WideCraftingInventory.amount(job.waitingFor, PRODUCT),
                "产出缓冲同样按真值记账：分轮申报就是产出侧 >long 的通道");
    }

    /** 每发一枚、一发产一枚的样板：任务侧回补那条路径的最小夹具。 */
    private static final FakePattern ONE_UNIT_A_FIRING = new FakePattern(
            AEItemKey.of(Items.BREWING_STAND),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
            List.of(new GenericStack(PRODUCT, 1)));

    /** 每发一枚、<b>一发产 {@code 4e18} 枚</b>的样板：申报 × 产出 装不进 long 的产出侧形状。 */
    private static final FakePattern BULKY_OUTPUT_A_FIRING = new FakePattern(
            AEItemKey.of(Items.CAULDRON),
            new FakeInput[]{new FakeInput(
                    new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
            List.of(new GenericStack(PRODUCT, 4_000_000_000_000_000_000L)));

    /** 越出 {@code long} 的任务进度：走真实写入口（{@code addFirings}），镜像自动变哨兵。 */
    private static ParallelExecutingCraftingJob.TaskProgress wideProgress(BigInteger firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.addFirings(PlanAmount.of(firings));
        return progress;
    }

    /**
     * 反向（不许假通过）：网络真只有一枚时，>long 的一发<b>不许</b>被凭空凑出来。
     *
     * <p>这一条钉住"能记"不能换来"能凭空造发数"：凑不齐整发就照旧停滞、欠着、如实报缺。
     */
    @Test
    void aFiringBeyondLongIsNeverInventedWhenTheNetworkIsShort() {
        var job = newJob();
        job.tasks.put(HUGE_A_FIRING, progress(2L));
        var pool = widePool();
        var network = new WideNetwork();
        network.add(V1, BigInteger.ONE);
        job.batchObligations.incur(HUGE_A_FIRING, BigInteger.ONE);

        long paid = ParallelCraftingCpuLogic.payObligationBatch(job, HUGE_A_FIRING,
                ParallelBatchExtraction.obligationBatchFirings(HUGE_A_FIRING,
                        job.batchObligations.pendingFirings(HUGE_A_FIRING)),
                pool, null, RESERVATION_FREE, new ParallelPoolLedger(), network.refill(), null);

        assertEquals(0L, paid, "网络真只有一枚：这一发凑不齐，不许交出份数");
        assertEquals(BigInteger.ONE, job.batchObligations.pendingFirings(HUGE_A_FIRING),
                "欠账原样留着");
        assertTrue(job.accountTaken.isEmpty(), "没付出去就不许记销账");
        assertEquals(BigInteger.ONE, WideCraftingInventory.amount(pool, V1),
                "搬进来那一枚留在池子里等后续，不随退料蒸发");
        assertFalse(job.taskProgress(HUGE_A_FIRING).isFinished(), "凑不齐的一发不许判完成");
    }

    /** 申报上界读的是<b>池子的真值</b>：long 天花板读数不能把两发夹成一发。 */
    @Test
    void declaredCeilingCountsFiringsHiddenByTheLongPoolReadout() {
        var pool = widePool();
        pool.insertExact(V1, HUGE_FIRING_UNITS.multiply(BigInteger.TWO), Actionable.MODULATE);

        var declared = ParallelBatchExtraction.declaredPayableCeiling(HUGE_A_FIRING, pool, null,
                RESERVATION_FREE, BigInteger.TEN);

        assertEquals(BigInteger.TWO, declared,
                "池子里放着两发的真量就要申报两发：按 long 读数除饱和的每发件数只剩一发");
    }

    /**
     * 端到端：申报 → 单发原型（一刀跨 {@code long} 付出去）→ 宿主收 2 发 → 剩下 1 发分批交割。
     *
     * <p>改前这一条连声明形态都进不去：申报上界被 long 读数夹成 1 发，
     * {@code declaredBatchFirings} 直接交 {@code null} 回 k 倍 holder 老路。
     */
    @Test
    void declaredPushOfAFiringBeyondLongPaysItsWholeObligation() {
        var job = newJob();
        job.tasks.put(HUGE_A_FIRING, progress(2L));
        var pool = widePool();
        pool.insertExact(V1, HUGE_FIRING_UNITS.multiply(BigInteger.TWO), Actionable.MODULATE);
        var network = new WideNetwork();
        network.add(V1, HUGE_FIRING_UNITS);

        var push = ParallelCraftingCpuLogic.attemptDeclaredBatch(new PlainHost(), HUGE_A_FIRING, job,
                job.tasks.entrySet(), pool, null, RESERVATION_FREE, new ParallelPoolLedger(),
                new KeyCounter(), new KeyCounter(), Long.MAX_VALUE, Long.MAX_VALUE, null, null, null,
                null, 0L);
        assertNotNull(push, "申报上界夹成 1 发即声明形态压根不适用，这一发于是永远开不了工");
        assertEquals(2L, push.accepted(), "宿主照收申报份数");
        assertEquals(HUGE_FIRING_UNITS, job.accountTaken.getExact(V1),
                "原型那一发按真值离开池子");
        assertEquals(BigInteger.ONE, job.batchObligations.pendingFirings(HUGE_A_FIRING),
                "剩下的一发挂在义务账上等交割");

        long paid = ParallelCraftingCpuLogic.payObligationBatch(job, HUGE_A_FIRING,
                ParallelBatchExtraction.obligationBatchFirings(HUGE_A_FIRING,
                        job.batchObligations.pendingFirings(HUGE_A_FIRING)),
                pool, null, RESERVATION_FREE, new ParallelPoolLedger(), network.refill(), null);
        assertEquals(1L, paid);
        assertEquals(HUGE_FIRING_UNITS.multiply(BigInteger.TWO), job.accountTaken.getExact(V1),
                "两发的真值逐批累进同一本销账，不饱和也不回绕");
        assertFalse(job.batchObligations.hasPending(HUGE_A_FIRING));
        assertEquals(0, WideCraftingInventory.amount(pool, V1).signum(), "池子按真值清空");
    }

    /**
     * 真失败的那一格在<b>第 3 格往后</b>时也点得出名：前两格在 >long 的份数下常常全都放行
     * （配额 ÷ 族每发合计远大于它），只印前两格就永远看不见因（实机 2026-09-26 那一跑
     * {@code mismatch=0} 而 {@code obPayChunks=0} 就是这个形状）。
     *
     * <p>点名用的现场与取料点<b>同一条</b>算式：{@code 现场商} 就是 {@code cellPayableFirings}
     * 的分子分母现算现印，判据说"这一格算不出 0 发"，读数印出来的也是同一个 0。
     */
    @Test
    void failingCellBeyondTheFirstTwoIsNamedWithItsOwnMath() {
        var pattern = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 1L)},
                                1L, null, Set.of(V1)),
                        new FakeInput(new GenericStack[]{new GenericStack(V2, 1L)},
                                1L, null, Set.of(V2)),
                        new FakeInput(new GenericStack[]{new GenericStack(BUCKET, 4L)},
                                1L, null, Set.of(BUCKET)),
                },
                List.of(new GenericStack(PRODUCT, 1)));
        // 槽2 的候选必须<b>落在池子里</b>才进得了取料点的候选宇宙（usableTemplates 走
        // findFuzzyTemplates），但只有 2 枚：付不出每发 4 枚的那一发 ⇒ 真失败格就是它
        var pool = widePool();
        pool.insertExact(V1, BigInteger.TEN, Actionable.MODULATE);
        pool.insertExact(V2, BigInteger.TEN, Actionable.MODULATE);
        pool.insertExact(BUCKET, BigInteger.TWO, Actionable.MODULATE);
        var probe = new ParallelBatchExtraction.Probe();

        var prototype = ParallelBatchExtraction.prepareUnitPrototype(pattern, pool, null,
                new KeyCounter(), RESERVATION_FREE, probe);

        assertNull(prototype, "第三格付不出：整批提不出来，probe 却要点名到它");
        // 停滞行那一格与 per100r 的 minCell 同一把尺（cellReadout 一个出口）：格内子字段用不带
        // 空白的 ","，键的第三方空白折成 "_"，整格不含任何空白。
        assertTrue(probe.blockedCell.startsWith("slot#2," + oneToken(BUCKET) + ",每发=4,"),
                probe.blockedCell);
        assertTrue(probe.blockedCell.contains(",池内=2,"), probe.blockedCell);
        assertTrue(probe.blockedCell.contains(",现场商=0,"), probe.blockedCell);
        assertFalse(probe.blockedCell.chars().anyMatch(Character::isWhitespace),
                "停滞行那一格不许含任何空白（与 minCell 同一形状）：" + probe.blockedCell);

        var job = newJob();
        job.tasks.put(pattern, progress(5L));
        job.batchObligations.incur(pattern, BigInteger.ONE);
        var blocked = ParallelCraftingCpuLogic.describeBlockedObligation(job,
                job.tasks.entrySet(), pool, RESERVATION_FREE, probe);
        assertTrue(blocked.contains("真失败格[slot#2,"), blocked);
        assertFalse(blocked.contains("可花="), "上界那把尺的 yes/no 读数已随尺一起删");
    }

    /**
     * 笔 3a（台账 #226）：{@code inventory.insert} 交回 {@code void}，而累账累的一直是
     * {@code refill.pull} 那个 {@code got} ⇒ "池子真收到几枚"从没被测过。
     * 这一格在 {@code insert} 前后各读一次<b>同一把尺</b>（{@code exactStored}）的池内量，
     * 差值印成 {@code got=} 旁边的 {@code landed=}。网络交 2 枚、池子只收 1 枚那一型，
     * <b>两个数都要在同一格里出来</b>——只印 got 的写法红在这里。
     */
    @Test
    void refillBlockedCellPrintsBothWhatNetworkGaveAndWhatPoolActuallyLanded() {
        var pool = new PartiallyFillingPool(1L); // 每一次伸手只收得下 1 枚
        var probe = new ParallelBatchExtraction.Probe();

        // 本批真需 3 枚（每发 1 枚 × 3 发），网络那一头一次交 2 枚：<want ⇒ ranDry 那一格
        assertEquals(2L, ParallelBatchExtraction.refillObligationBatch(ONE_UNIT_A_FIRING, pool,
                null, 3L, RESERVATION_FREE, (what, wanted) -> 2L, probe));

        assertEquals("slot#0_" + oneToken(V1) + "_got=2_landed=1_why=ranDry",
                probe.refillBlockedCell(),
                "got=网络交出的那一截，landed=池子真收到的那一截，两个数都要在同一格里："
                        + probe.refillBlockedCell());
        assertEquals(1L, ParallelBatchExtraction.exactStored(pool, V1).longValue(),
                "夹具前提：池子里真躺着的只有 1 枚（landed 量的就是它，不是猜的）");
    }

    /**
     * 笔 3a 的对照臂：池子收得下时 {@code landed} 与 {@code got} 同数。
     * 把 {@code landed} 恒印成 0（或恒印成 got）的写法各红在一边——这一格必须是<b>量出来的</b>。
     */
    @Test
    void refillBlockedCellLandedEqualsGotWhenThePoolAcceptsEverythingItWasHanded() {
        var pool = widePool();
        var probe = new ParallelBatchExtraction.Probe();

        assertEquals(2L, ParallelBatchExtraction.refillObligationBatch(ONE_UNIT_A_FIRING, pool,
                null, 3L, RESERVATION_FREE, (what, wanted) -> 2L, probe));

        assertEquals("slot#0_" + oneToken(V1) + "_got=2_landed=2_why=ranDry",
                probe.refillBlockedCell(),
                () -> "收得下的那一型两个数必须相等：" + probe.refillBlockedCell());
        assertEquals(2L, ParallelBatchExtraction.exactStored(pool, V1).longValue(),
                "夹具前提：两枚都真躺在池子里");
    }


    /**
     * "网络交出 N、池子只收 M（M&lt;N）"那一型的桩池：每一次 {@code insert} 只把 {@code capPerInsert}
     * 那一截真放进池，其余静默收不下——这正是 {@code inventory.insert} 交回 {@code void} 时
     * 下游看不见的那一截（台账 #226 笔 3a 的 {@code landed=} 就是要把它量出来）。
     */
    private static final class PartiallyFillingPool extends WideCraftingInventory {

        private final long capPerInsert;

        private PartiallyFillingPool(long capPerInsert) {
            super(ignored -> {
            });
            this.capPerInsert = capPerInsert;
        }

        @Override
        public void insert(AEKey what, long amount, Actionable mode) {
            super.insert(what, Math.min(amount, capPerInsert), mode);
        }
    }

    private static WideCraftingInventory widePool() {
        return new WideCraftingInventory(ignored -> {
        });
    }

    /**
     * 留物扫描的候选宇宙必须与取料点<b>同源</b>（P2-1）。
     *
     * <p>形状：bigint（无份数上界）宿主 + 一张<b>声明候选本身无留物、模糊替代键有留物</b>的样板。
     * 取料点真正写 {@code expectedContainerItems} 的是它伸手的那个键，而那个键出自
     * {@code usableTemplates}（{@code findFuzzyTemplates} + {@code isValid}）——受损镐 V1 与全新镐 V2
     * 同物品本体，池子里只躺着 V2 ⇒ 取料点吃的是 V2，而 V2 每发交回一只桶。
     *
     * <p>旧实现只遍历 {@code getPossibleInputs()} 的声明候选（V1）⇒ 判成"无留物"⇒ 对 bigint 宿主
     * 跳过 {@code representableInputCeiling} ⇒ 申报冲到池子可付的 4.6e18 发，{@code k × 倍率 4}
     * 塞进按 key 的 {@code long} 容器 ⇒ 顶到 {@code Long.MAX} 的幽灵挂账永不销。这一条<b>必须</b>
     * 仍被输入尺夹在 {@code Long.MAX/4}。
     */
    @Test
    void fuzzyAlternateWithRemainderKeepsTheInputClampOnAnUnboundedHost() {
        var pattern = new FakePattern(AEItemKey.of(Items.BARREL),
                new IPatternDetails.IInput[]{new KeyedRemainderInput(
                        new GenericStack[]{new GenericStack(V1, 1L)},   // 声明的是 V1
                        4L, Set.of(V1, V2), Map.of(V2, BUCKET))},       // 有留物的是模糊替代 V2
                List.of(new GenericStack(PRODUCT, 1)));
        var job = newJob();
        job.tasks.put(pattern, wideProgress(NINE_E18));
        // 池子里只躺着模糊替代 V2：取料点的候选宇宙 = {V2}，声明表上的 V1 压根不在场
        var pool = widePool();
        pool.insertExact(V2, BIG_LONG.multiply(BigInteger.TWO), Actionable.MODULATE);

        var declared = ParallelCraftingCpuLogic.declaredBatchFirings(new UnboundedHost(),
                pattern, job.taskProgress(pattern), pool, null, RESERVATION_FREE, Long.MAX_VALUE, null);

        assertEquals(POOL_FIRINGS, declared,
                "模糊替代的回流同样进入宽域预期，不以输入倍率的 long 投影截断整批");
    }

    /**
     * 停滞行的份数读数<b>只有一把尺</b>（P2-2）：同一行里 {@code 该格份数=} 与
     * {@code 真失败格[… 现场商= 族商=]} 必须是<b>同一次提取的那同一个数</b>；取料点本轮没算过的
     * 那些格明写"非本轮该格"，不许另摆一个整张样板 scope 的数。
     *
     * <p>形状：占料锁把本槽真正可动的量夹到 0（现场躺着 20 枚），于是
     * <ul>
     *   <li>批内 scope（取料点真扣配额那一份）：分子被 {@code usableFor} 夹到 0 ⇒ 该格 0 发；</li>
     *   <li>池子 scope 的{@code 可付发}：20 ÷ 4 = 5 ⇒ 旧形状在同一行里印出 5 与 0 两个数。</li>
     * </ul>
     * 断言按<b> parse 出来的数比数</b>，不靠肉眼比对。
     */
    @Test
    void stallLineReportsTheSameCellTwiceWithOneRulerNotTwo() {
        var self = new FakePattern(AEItemKey.of(Items.HOPPER),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 4L), new GenericStack(BUCKET, 4L)},
                        1L, null, Set.of(V1, BUCKET))},
                List.of(new GenericStack(PRODUCT, 1)));
        var locker = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 4L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(AEItemKey.of(Items.BOOKSHELF), 1)));
        var pool = widePool();
        pool.insertExact(V1, BigInteger.valueOf(20L), Actionable.MODULATE);
        pool.insertExact(BUCKET, BigInteger.valueOf(20L), Actionable.MODULATE);
        // 锁主：V1 是它唯一候选（无退路）⇒ 池里那 20 枚被判"别人唯一能用"，取料点 usableFor 夹到 0
        var tasks = new java.util.LinkedHashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>();
        tasks.put(self, progress(1L));
        tasks.put(locker, progress(5L));
        var reservation = MaterialAllocationFixtures.build(tasks.entrySet(), Map.of(), NO_PLAN, NO_TAKEN);
        assertEquals(0L, reservation.usableFor(V1, 20L, self, 1L, self.getInputs()[0], true),
                "本用例必须真走「占料锁夹到 0」那一型，否则两把尺的差异压根没造出来");
        var probe = new ParallelBatchExtraction.Probe();
        assertNotNull(ParallelBatchExtraction.prepareUnitPrototype(self, pool, null,
                new KeyCounter(), reservation, probe), "首位候选被锁住即退到备选：这一发照样开得出");
        assertTrue(probe.blockedCell.contains(",现场商=0"), probe.blockedCell);

        var job = newJob();
        job.tasks.put(self, progress(1L));
        job.batchObligations.incur(self, BigInteger.ONE);
        var blocked = ParallelCraftingCpuLogic.describeBlockedObligation(job,
                job.tasks.entrySet(), pool, reservation, probe);

        // 现场那两格仍在行里（可付发=5 是"池子里躺着 5 发"的搬运视角），但"这一格几发"只有一个数
        assertTrue(blocked.contains("可付发=5"), blocked);
        var named = blocked.substring(blocked.indexOf("真失败格["));
        var onSite = new BigInteger(readOut(named, "现场商="));
        var family = "无口径".equals(readOut(named, "族商=")) ? null : new BigInteger(readOut(named, "族商="));
        var oneRuler = family == null ? onSite : onSite.min(family);
        assertEquals("0", oneRuler.toString(),
                "被点名的那一格本轮算出的就是 0 发：" + named);
        // V1 那一格（首位候选）的读数段：从 slot#0 点名起到下一个候选分隔符（"/"，与 obWhy= 的
        // 格间分隔同字面——blocked= 整格按空白切必须只落一段，候选之间不再用带空白的 " | "）
        var v1From = blocked.indexOf("slot#0[");
        assertTrue(v1From >= 0, blocked);
        var v1To = blocked.indexOf('/', v1From);
        assertTrue(v1To > v1From, "这一格必须有第二个候选可数，否则测不到「非本轮该格」那一支：" + blocked);
        var v1Cell = blocked.substring(v1From, v1To);
        assertEquals(oneRuler.toString(), readOut(v1Cell, "该格份数="),
                "同一行里 V1 那一格的份数读数必须就是真失败格那两份商的min（同一次提取同一个数）："
                        + v1Cell);
        assertTrue(blocked.contains(",该格份数=非本轮该格"),
                "取料点本轮没算过的那一格必须明写非本轮该格，不许拿第二把尺算一个看起来对的数："
                        + blocked);
    }

    /**
     * 从读数串里抠出 {@code label=} 后面的那一段：到<b>下一个边界</b>为止——格界（空格）、
     * 格内子字段界（{@code ,}，停滞行的 {@code 真失败格[...]} 与 per100r 的 {@code minCell} 都用它）、
     * 或那一格的收尾 {@code ]}。
     */
    private static String readOut(String line, String label) {
        var from = line.indexOf(label);
        assertTrue(from >= 0, line + " 里读不出 " + label);
        var start = from + label.length();
        for (var i = start; i < line.length(); i++) {
            var c = line.charAt(i);
            if (c == ' ' || c == ',' || c == ']') {
                return line.substring(start, i);
            }
        }
        return line.substring(start);
    }

    /**
     * 同轮两张样板都欠账时，点名必须落到<b>第一张欠账样板自己</b>的那一格（P2-3），而<b>另一张
     * 现在带着它自己那一段与它自己那一格</b>（台账 #192 把 {@code blocked=} 从一张扩到最多三张）：
     * "不张冠李戴"这条判据的落点从"整格只许有第一张"精确到"每一段只许有自己那一格"。
     *
     * <p>形状：先给 P1 跑一次提取（它的首失败格在 {@code slot#0}），再给 P2 跑一次（首失败格在
     * {@code slot#1}）——旧实现那份单格 {@code blockedCell} 每次 {@code prepareUniform} 开头清零，
     * 停在里面的是<b>最后一次</b>提取那一格，而旧点名只出一张 ⇒ 恒落成"真失败格=非本轮该样板"，
     * 点名功能退化。两张 owed 并列（各 1）：并列按 {@code orderedTasks} 顺位，头段仍是第一张。
     */
    @Test
    void stallLineNamesTheFirstOwedPatternOwnFailingCellWhenTwoAreOwedThisRound() {
        var first = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 4L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));
        var second = new FakePattern(AEItemKey.of(Items.HOPPER),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 1L)},
                                1L, null, Set.of(V1)),
                        new FakeInput(new GenericStack[]{new GenericStack(BUCKET, 4L)},
                                1L, null, Set.of(BUCKET)),
                },
                List.of(new GenericStack(AEItemKey.of(Items.BOOKSHELF), 1)));
        var pool = widePool();
        pool.insertExact(V1, BigInteger.TWO, Actionable.MODULATE);   // 够 second 槽0 的 1 枚，不够 4 枚
        pool.insertExact(BUCKET, BigInteger.TWO, Actionable.MODULATE);   // 槽1 每发 4 枚：这一格付不出
        var probe = new ParallelBatchExtraction.Probe();
        assertNull(ParallelBatchExtraction.prepareUnitPrototype(first, pool, null,
                new KeyCounter(), RESERVATION_FREE, probe));
        assertNull(ParallelBatchExtraction.prepareUnitPrototype(second, pool, null,
                new KeyCounter(), RESERVATION_FREE, probe));
        assertTrue(probe.blockedCell.startsWith("slot#1,"),
                "本轮<b>最后</b>一次提取留的是第二张的那一格（旧实现唯一的那格）：" + probe.blockedCell);

        var job = newJob();
        var ordered = new java.util.LinkedHashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>();
        ordered.put(first, progress(1L));
        ordered.put(second, progress(1L));
        ordered.forEach(job.tasks::put);
        job.batchObligations.incur(first, BigInteger.ONE);
        job.batchObligations.incur(second, BigInteger.ONE);

        var blocked = ParallelCraftingCpuLogic.describeBlockedObligation(job,
                // 实机那份顺序由 RecursiveTaskOrdering 交出；这里把它钉成"第一张欠账 = first"
                // （job.tasks 是 HashMap，按 entrySet 的顺序点不到人）
                new java.util.ArrayList<>(ordered.entrySet()), pool, RESERVATION_FREE, probe);
        assertFalse(blocked.contains("真失败格=非本轮该样板"),
                "点名不许退化：两张都欠账时它恒落到别人那一格上 " + blocked);
        // 点名不许张冠李戴：判据落在<b>每一段自己</b>——头段必须是被点名的第一张、带它自己那一格；
        // 第二段带着第二张自己的那一格（扩到最多三张后的真形状，不再是"整格只许有一张"）。
        assertTrue(blocked.startsWith("<minecraft:iron"), blocked);
        var firstSegment = blockedSegment(blocked, "<" + PRODUCT);
        assertTrue(firstSegment.contains("真失败格[slot#0,"), firstSegment);
        assertFalse(firstSegment.contains("真失败格[slot#1,"),
                "第一张那一段里不许混进第二张的那一格：" + firstSegment);
        assertTrue(firstSegment.contains("真失败格[slot#0," + oneToken(V1) + ",每发=4,"),
                "印出来的必须是第一张欠账样板自己那一格：" + firstSegment);
        var secondSegment = blockedSegment(blocked, "<" + AEItemKey.of(Items.BOOKSHELF));
        assertTrue(secondSegment.contains("真失败格[slot#1," + oneToken(BUCKET) + ",每发=4,"),
                "第二张自己那一段必须带它自己那一格（旧单张形状里这一段压根不出现）：" + secondSegment);
    }

    /**
     * 从 {@code blocked=} 整格里切出<b>一张样板一段</b>：段界是前段收尾的 {@code >} 紧接后段的
     * {@code <}（台账 #192 扩点名的逐字形状：每段各自 {@code <…>} 原样拼接，格出口折完空白后
     * 段界仍可寻址）。
     */
    private static String blockedSegment(String blocked, String headPrefix) {
        var from = blocked.indexOf(headPrefix + "#i");
        assertTrue(from >= 0, blocked + " 里找不到段头 " + headPrefix);
        var to = blocked.indexOf("><", from);
        return to >= 0 ? blocked.substring(from, to) : blocked.substring(from);
    }

    /**
     * 回补伸手拿到 0 时，必须一眼分出「生产方入了网但精确键不等」与「网络真 0」两型（R1）。
     *
     * <p>实机那一行 {@code obWhy=slot#1 extendedae_plus:oblivion_singularity got=0 why=ranDry}
     * 只证得了"这一格没补进料"，证不了网络侧这一族到底有什么——两型的修法完全不同（改精确键口径
     * vs 真缺料）。现在 {@code obWhy=} 那一格里就带着：同一笔请求该精确键的 SIMULATE 现量、
     * 同族逐精确键现量与键数。
     *
     * <p>读数口径是<b>下界</b>：键集来自聚合表（只取键、一个金额都不取），量走现成的
     * {@code ObligationRefill#probe} 逐键问一次，一次问不出 {@code >long}。
     */
    @Test
    void refillReadoutNamesTheFamilyThatIsOnTheNetworkWhenTheExactKeyPaysZero() {
        var pattern = ONE_UNIT_A_FIRING; // 单候选 V1、每发 1 枚
        var pool = widePool();           // 池内 0：本批真需 3 枚全要向网络伸手
        var probe = new ParallelBatchExtraction.Probe();
        var refill = new ParallelBatchExtraction.ObligationRefill() {
            @Override
            public long pull(@Nullable AEKey what, long wanted) {
                return 0L; // 网络一枚不交：走 got==0 那一支
            }

            @Override
            public long probe(@Nullable AEKey what, long wanted) {
                return V2.equals(what) ? 7L : 0L; // 精确键 V1 真 0，同族 V2 躺着 7 枚
            }

            @Override
            public java.util.List<AEKey> sameFamilyKeys(@Nullable AEKey sample) {
                return V1.equals(sample) ? java.util.List.of(V2) : java.util.List.of();
            }
        };

        assertEquals(0L, ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 3L,
                RESERVATION_FREE, refill, probe));

        assertEquals("slot#0_" + oneToken(V1) + "_got=0_landed=0_why=ranDry"
                + "_网络sim=0_同族键数=1_同族[" + oneToken(V2) + "=7]下界", probe.refillBlockedCell(),
                "got==0 那一支必须把网络同族现量拼进同一格：只印 why=ranDry 分不出两型；"
                        + "格出口把空白折成 _（含带组件键的 (with patches)），per100r 按空白切才是一格一 token");
    }

    /**
     * 批次塌成个位数时，必须点名"钉住它的那一格"（R2）。
     *
     * <p>实机 {@code lost=1 worst(req=4751999962 got=1 … bind=minecraft:netherrack
     * stored=4751999962 demand=1)}：{@code bind} 那格自己算得出 4.75e9 发，钉住 1 的<b>不是</b>它，
     * 而 {@code 真失败格} 只记 {@code executions < 1} 的那一格——算得出 1 发的那一格两个读数都点不到。
     * 现在 {@code minCell=} 那一格直接报"本批算出的最小份数那一格"，槽号/键/每发/池内/配额/
     * 现场商/族商/cap 与取料点同一条算式（{@code Probe#cellReadout} 一把尺）。
     */
    @Test
    void minCellNamesTheCellThatPinnedTheBatchToOneFiring() {
        var pattern = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 1L)},
                                1L, null, Set.of(V1)),      // 池内只有 1 枚：这一格钉死 1 发
                        new FakeInput(new GenericStack[]{new GenericStack(BUCKET, 1L)},
                                1L, null, Set.of(BUCKET)),   // 池内 1000 枚：它不是钉住的那一格
                },
                List.of(new GenericStack(PRODUCT, 1)));
        var pool = widePool();
        pool.insertExact(V1, BigInteger.ONE, Actionable.MODULATE);
        pool.insertExact(BUCKET, BigInteger.valueOf(1000L), Actionable.MODULATE);
        var probe = new ParallelBatchExtraction.Probe();

        var batch = ParallelBatchExtraction.prepare(pattern, pool, null, 10L, new KeyCounter(),
                new KeyCounter(), RESERVATION_FREE, probe);

        assertNotNull(batch);
        assertEquals(1L, batch.executions(), "槽0 现场只够一发：整批就该被它钉成 1 发");
        assertEquals("", probe.blockedCell, "每一格都算得出发：这不是失败格那一型");
        assertEquals("minCell[原型]=-,"
                + "minCell[holder]=slot#0," + oneToken(V1) + ",每发=1,池内=1,配额=无口径,usableFor=1"
                + ",现场商=1,族商=无口径,cap=10,份数=1,minCell[义务]=-", probe.describeMinCell(),
                "minCell= 必须点名算出那份最小值的那一格（槽0），不是同批里另一格 1000 枚的料");
    }

    /**
     * 停滞行点不到失败格时，必须再说清"本轮算的是谁"（R3）。
     *
     * <p>两型过去长得一模一样：{@code 这张样板压根没被算}（每 tick 批数闸／tick 预算闸把它挡在
     * {@code payDeclaredBatchObligations} 的 {@code continue} 上，回补那条腿一次没为它开跑）与
     * {@code 算了但交 0}。现在 {@code 真失败格=本轮未算} 后面紧跟 {@code 本轮最后算的那张样板=}，
     * 报的就是回补真开跑过的那一张（{@code Probe} 每 tick 每 lane 新建，不跨 tick、不落 NBT）。
     */
    @Test
    void stallLineSaysNeverComputedAndNamesThePatternTheRefillLegActuallyRan() {
        var owedButNeverComputed = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 4L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));
        var ranThisRound = new FakePattern(AEItemKey.of(Items.HOPPER),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(AEItemKey.of(Items.BOOKSHELF), 1)));
        var pool = widePool(); // V1 池内 0：回补这条腿照跑，只是补不进料
        var probe = new ParallelBatchExtraction.Probe();
        ParallelBatchExtraction.refillObligationBatch(ranThisRound, pool, null, 1L,
                RESERVATION_FREE, (what, wanted) -> 0L, probe);

        var job = newJob();
        var ordered = new java.util.LinkedHashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>();
        ordered.put(owedButNeverComputed, progress(1L));
        ordered.forEach(job.tasks::put);
        job.batchObligations.incur(owedButNeverComputed, BigInteger.ONE);

        var blocked = ParallelCraftingCpuLogic.describeBlockedObligation(job,
                new java.util.ArrayList<>(ordered.entrySet()), pool, RESERVATION_FREE, probe);

        assertTrue(blocked.contains(",真失败格=本轮未算,本轮最后算的那张样板="
                        + AEItemKey.of(Items.BOOKSHELF) + "(FakePattern,槽数=1)"),
                "点不到失败格时必须报出本轮真算过的那一张，否则\"没被算\"与\"算了交 0\"又是同一个形状："
                        + blocked);
    }

    /**
     * 原型趟的 {@code cap=1} 不许冒充批处理上限（读数修正）。
     *
     * <p>实机那一跑：{@code ParallelBatchExtraction.Probe#noteMinCell} 被三趟取料腿喂（k 倍 holder、
     * 义务交割、单发原型那一趟份数写死 {@code 1L}），而它留的是"份数最小的那一格" ⇒ 原型那一趟
     * <b>永远赢</b>，打印出 {@code minCell=slot#0 … 现场商=28235520000 族商=28235520000 cap=1 份数=1}，
     * 把批处理上限印成了 1；同一窗 {@code req=23969280000} 却证明 holder 腿拿到了 2.4e10。
     * 现在每趟各留自己最紧的一格：{@code minCell[原型]=} 那份 1、{@code minCell[holder]=} 它自己那份
     * 批量，各说各话；没跑过的那趟明写 {@code -}，不冒充"这一趟不存在"。
     */
    @Test
    void minCellSplitsPrototypeLegFromHolderLegSoCapOneStopsImpersonatingTheBatchCeiling() {
        var pattern = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));
        var probe = new ParallelBatchExtraction.Probe();

        // holder 腿（k 倍批量）：同一格算得出 100 发
        var holderPool = widePool();
        holderPool.insertExact(V1, BigInteger.valueOf(1_000_000L), Actionable.MODULATE);
        var holderBatch = ParallelBatchExtraction.prepare(pattern, holderPool, null, 100L,
                new KeyCounter(), new KeyCounter(), RESERVATION_FREE, probe);
        assertNotNull(holderBatch);
        assertEquals(100L, holderBatch.executions());

        // 原型趟（份数写死 1L）：同一把尺、同一个现场量，它那一格的 cap=1 只是这一趟的口径
        var prototypePool = widePool();
        prototypePool.insertExact(V1, BigInteger.valueOf(1_000_000L), Actionable.MODULATE);
        assertNotNull(ParallelBatchExtraction.prepareUnitPrototype(pattern, prototypePool, null,
                new KeyCounter(), RESERVATION_FREE, probe));

        assertEquals("minCell[原型]=slot#0," + oneToken(V1) + ",每发=1,池内=1000000,配额=无口径"
                + ",usableFor=1000000,现场商=1000000,族商=无口径,cap=1,份数=1"
                + ",minCell[holder]=slot#0," + oneToken(V1) + ",每发=1,池内=1000000,配额=无口径"
                + ",usableFor=1000000,现场商=1000000,族商=无口径,cap=100,份数=100"
                + ",minCell[义务]=-", probe.describeMinCell(),
                "三趟取料腿各一格：原型那份 1 发不许盖掉 holder 自己算出的 100 发");
        // 一格一 token：三格连成一串后仍不许残留空白（键的 "(with patches)" 在格出口折成 "_"）
        assertFalse(probe.describeMinCell().chars().anyMatch(Character::isWhitespace),
                "minCell= 整串不许含任何空白：" + probe.describeMinCell());
    }

    /**
     * 停滞行 {@code blocked=} 必须印<b>被点名的那张样板自己那一格</b>的回补读数（含它自己的
     * {@code 网络sim=/同族[…]下界}），而 {@code obWhy=} 不再把别张样板的格冒充成这条 lane 的卡点。
     *
     * <p>实机那一跑：{@code noteRefillBlockedCell} 是"每个 Probe 只留第一格"（非空即 {@code return}），
     * 于是印出 {@code obWhy=slot#1 mekanism_extras:cosmic_induction_cell … 网络sim=0 同族键数=0}，
     * 而真卡住这条 lane 的是<b>另一张样板</b>的 slot#3；那一格自己的网络读数一个字都没印出来，
     * {@code topUp=0/0} 就被读成"没伸手"，其实它证的是"伸了手、网络交回 0 枚"。
     */
    @Test
    void blockedObligationLinePrintsTheNamedPatternOwnRefillCellNotAnotherPatterns() {
        var named = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V2, 1L)}, 1L, null, Set.of(V2))},
                List.of(new GenericStack(PRODUCT, 1)));
        var ranFirst = new FakePattern(AEItemKey.of(Items.HOPPER),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(AEItemKey.of(Items.BOOKSHELF), 1)));
        // 三格都伸手都拿到 0：与前两张合起来正好顶穿每 tick 每 lane 的四格封顶
        var overflow = new FakePattern(AEItemKey.of(Items.CHEST),
                new FakeInput[]{
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 1L)},
                                1L, null, Set.of(V1)),
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 1L)},
                                1L, null, Set.of(V1)),
                        new FakeInput(new GenericStack[]{new GenericStack(V1, 1L)},
                                1L, null, Set.of(V1)),
                },
                List.of(new GenericStack(AEItemKey.of(Items.OAK_BOAT), 1)));
        var pool = widePool(); // 池内 0：两趟都走 got==0 那一支
        var probe = new ParallelBatchExtraction.Probe();

        // 先跑"不是被点名那一张"：旧那本单格账就是被它先占了位子
        ParallelBatchExtraction.refillObligationBatch(ranFirst, pool, null, 3L, RESERVATION_FREE,
                readoutRefill(V1, 5L), probe);
        ParallelBatchExtraction.refillObligationBatch(named, pool, null, 3L, RESERVATION_FREE,
                readoutRefill(V2, 11L), probe);
        ParallelBatchExtraction.refillObligationBatch(overflow, pool, null, 3L, RESERVATION_FREE,
                readoutRefill(V1, 5L), probe);

        var job = newJob();
        var ordered = new java.util.LinkedHashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>();
        ordered.put(named, progress(1L));
        ordered.put(ranFirst, progress(1L));
        ordered.forEach(job.tasks::put);
        job.batchObligations.incur(named, BigInteger.ONE);
        job.batchObligations.incur(ranFirst, BigInteger.ONE);

        var blocked = ParallelCraftingCpuLogic.describeBlockedObligation(job,
                new java.util.ArrayList<>(ordered.entrySet()), pool, RESERVATION_FREE, probe);

        assertTrue(blocked.contains("回补受阻[slot#0_" + oneToken(V2) + "_got=0_landed=0_why=ranDry"
                        + "_网络sim=11_同族键数=0_同族[]下界]"),
                "blocked= 必须带被点名那张自己那一格与它自己的网络读数：" + blocked);
        // 台账 #192 把 blocked= 扩到最多三张：别张现在带<b>它自己那一段</b>出现——
        // "不许顶名"的判据落在段界上：被点名那一段里不许混进别张的网络读数，别张只许在自己的段里带自己的。
        var namedSegment = blockedSegment(blocked, "<" + PRODUCT);
        assertFalse(namedSegment.contains("网络sim=5"),
                "别张样板的网络读数不许混进被点名那一张自己的段里：" + namedSegment);
        var otherSegment = blockedSegment(blocked, "<" + AEItemKey.of(Items.BOOKSHELF));
        assertTrue(otherSegment.contains("slot#0_" + oneToken(V1) + "_got=0_landed=0_why=ranDry_网络sim=5"),
                "第二张那一段必须带它自己的那一格与它自己的网络读数：" + otherSegment);

        var cells = probe.describeRefillBlockedCells();
        assertTrue(cells.contains("<" + PRODUCT + "_FakePattern>_slot#0_" + oneToken(V2)
                        + "_got=0_landed=0_why=ranDry_网络sim=11"),
                "obWhy= 每一格要点名是谁的那一格：" + cells);
        assertTrue(cells.contains("<" + AEItemKey.of(Items.BOOKSHELF) + "_FakePattern>_slot#0_"
                        + oneToken(V1) + "_got=0_landed=0_why=ranDry_网络sim=5"),
                "两张样板各留一格，不再只留最早那一格：" + cells);
        assertTrue(cells.contains("<" + AEItemKey.of(Items.OAK_BOAT) + "_FakePattern>_slot#1_"), cells);
        assertFalse(cells.contains("<" + AEItemKey.of(Items.OAK_BOAT) + "_FakePattern>_slot#2_"),
                "每 tick 每 lane 封顶四格，第五格只能记进更多=：" + cells);
        assertTrue(cells.startsWith("obWhy更多=1/"),
                "印不下的更多计数要紧跟在 obWhy= 后面同一格（不粘在最后一格尾巴上，空白切才分得出独立的一段）：" + cells);
        assertFalse(cells.contains(" | "),
                "格与格的边界不许带空白：' | ' 的两段空白会被 per100r 那把按空白切 token 的尺切成三段：" + cells);
        // 硬判据（一格一 token）：整串——含带组件键的 "(with patches)"、同族读数的三段前导空白、
        // 格自带的连接空白——不许残留任何空白；per100r 按空白切时 obWhy= 整串恰落在一格里。
        assertFalse(cells.chars().anyMatch(Character::isWhitespace),
                "obWhy= 整串不许含任何空白（一格一 token）：" + cells);
    }

    /**
     * {@code obWhy=} 那一格走<b>真渲染口</b>（{@code StallProbe#noteBatch} 真打进日志的 per100r 行）：
     * 带<b>数据组件</b>的键（{@code AEItemKey#toString} 的 {@code (with patches)}——damage=50 的
     * 真补丁，不是自写 {@code toString()} 的假样本）在候选键与样板头两处都必须在格出口折成
     * 一个 token——按空白切它恰占一段，紧挨着的下一段是 {@code declNull=} 字段本身。
     *
     * <p>不直调静态格式化方法：格由 {@code refillObligationBatch} 现造、行由 appender 捕获日志取回
     * （同 {@link ParallelDeclaredBatchRejectFallThroughTest} 的渲染口用法）。
     */
    @Test
    void per100rRenderSitePrintsRefillCellsAsOneWhitespaceToken() {
        // 样板产出也用带组件的 V1：样板头 refillPatternLabel 是另一处漏口，渲染口一并钉住。
        var pattern = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(V1, 1)));
        var pool = widePool(); // 池内 0：回补伸手拿到 0，走 got==0 那一支造格
        var probe = new ParallelBatchExtraction.Probe();
        ParallelBatchExtraction.refillObligationBatch(pattern, pool, null, 3L, RESERVATION_FREE,
                readoutRefill(V1, 5L), probe);

        var line = renderPer100rRefillLine(probe, pool, newJob(), 0L);
        var tokens = List.of(line.split(" "));
        var idx = firstTokenIndexStartingWith(tokens, "obWhy=");
        var cell = tokens.get(idx);
        // 硬判据①：格内不含任何空白（split(" ") 只切 ASCII 空格，tab 一类杂空白归这条抓）。
        assertFalse(cell.chars().anyMatch(Character::isWhitespace),
                "obWhy= 那一格不许含任何空白：日志行=" + line);
        // 硬判据②：整行按空白切出的这一格恰占一段（期望格数=1）——漏出空白时紧挨着的下一段
        // 是格碎片而不是 declNull= 字段。
        assertTrue(tokens.get(idx + 1).startsWith("declNull="),
                "obWhy= 一格切完必须直接落到下一格 declNull=：日志行=" + line);
        // 形状逐字钉死：样板头与候选键两处 "(with patches)" 都折成了 "_"，格自带空白也折成 "_"。
        assertEquals("obWhy=<minecraft:iron_pickaxe_(with_patches)_FakePattern>_slot#0_"
                        + "minecraft:iron_pickaxe_(with_patches)_got=0_landed=0_why=ranDry"
                        + "_网络sim=5_同族键数=0_同族[]下界",
                cell, "obWhy= 整格的逐字形状：日志行=" + line);
    }

    /**
     * per100r 那一行的<b>格名清单</b>（按空白切 token 的唯一口径）：顺序即格式串里的顺序。
     *
     * <p>前两段是日志标签，其余每一段都是一个格名；{@code worst(} 与 {@code minCell=} 也是格名，
     * 它们的子字段（{@code req}/{@code got}/{@code cls}/{@code bind}、三趟取料腿）在格内用 {@code ,}
     * 连，<b>不</b>各占一段。
     *
     * <p><b>这份清单刻意留在用例里、不由被测对象交出</b>：段数判据要证的不是"清单自洽"，而是
     * "格式串交出的那 45 个格名，与<b>实参拼出来的那些值</b>合起来只切得出 45 段"。左边那一个数只有
     * 独立写死才成立：清单一旦由渲染口自己交出（或由格式串 parse 出来），"整格消失""格名漂了"这两型
     * 就永远红不起来——被测对象说什么，判据就信什么。还有一条
     * 硬事实：{@code declaredReject=} 那一个格名压根<b>不在格式串里</b>（它由
     * {@code describeDeclaredRejectReadout} 交回的那一个<b>前导空格</b>带进来），从格式串 parse
     * 不出这 45 个名字，"由被测对象交出"这件事在这里根本没有干净的交出口。
     * 清单里每一个名字都还带着"这一格该由谁交出"的责任，改格式串的人必须同时改这里，那是
     * <b>有意为之的摩擦</b>，不是重复劳动。
     */
    private static final List<String> PER_100R_CELLS = List.of(
            "[chexsonsaeutils-batch]", "per100r",
            "lane=", "pushed=", "evaluated=", "cap=", "req=", "got=", "pushK=", "foldHost=",
            "foldNull=", "pwr=", "rem=", "uni=", "uniAvg=", "mix=", "noCand=", "mismatch=",
            "resClamp=", "ncSplit[百窗累计]=", "locked=", "familyClamp=", "declared=", "declaredK=",
            "declaredReject=", "obBatch=", "obPaid=", "obOwed=", "obNetBatch=", "obNetPull=",
            "obChunks=", "obPullExact=", "obPayChunks=", "obPaidExact=", "obWhy=", "declNull=",
            "materialBackoff=", "extractNull=",
            // 在飞那一族（#210）：停滞行只在"欠料且一批都没交割"那一档才打，纯"申报出去没回执、
            // 又不欠料"那一跑今天只有这两格看得见——读的是台账现成的两格读数，一格一 token。
            "inflightBatches=", "oldestInflightTicks=",
            // 申报批的覆盖面自证十格（台账 #225 读数 1–3）：同一本台账交出的累计快照＋回执侧的
            // 全场 JVM 累计。八格 [累计] 与一行里的 declared=/obBatch=（百轮窗）不是同一个窗，
            // 格名把窗写在 [] 里就是为了让按空白切出来的每一段自己说清覆盖面。
            "declaredBigint[累计]=", "declaredOther[累计]=", "inflightRegistered[累计]=",
            "noReceipt[累计]=", "noBatchId[累计]=",
            "terminalSuccess[累计]=", "terminalCancelled[累计]=", "terminalOther[累计]=",
            "terminalUnmatched[累计]=", "terminalDropped[全场JVM]=",
            // 读数 5（台账 #225）：机器回网队列的深度——2.3.9 那两只 public 口换算出来的真数，
            // 问不到那一档才是问号；cov 那一格自报"问到几台/这轮碰到几台"。两格都是一格一 token。
            "queuePending[useless侧]=", "queuePendingCov=",
            "lost=", "worst(", "minCell=", "inv=");

    /**
     * per100r 那一行<b>整行</b>一格一 token（走真渲染口 {@code StallProbe#noteBatch}）：
     * 按空白切出的<b>段数必须恰等于</b> {@link #PER_100R_CELLS} 的长度，且第 i 段以第 i 个格名开头。
     *
     * <p>补的是"只断单个格"的盲区：{@code locked=}、{@code worst(...)}、{@code minCell=}、{@code inv=}
     * 四格过去都在格内留连接空格（格式串里 {@code cls=%s %s} 那一个空格正是断 token 的位置），
     * 单格用例各看各的那一格，数不清"这一行到底切得出几段"。把清单写死成判据后：
     * 多一段＝格内漏了空白，少一段＝格名或顺序漂了。
     *
     * <p>同时钉住另一半口径——<b>子字段仍可寻址</b>：折完不许把 {@code stored=}/{@code got=}/
     * {@code 每发=}/{@code total=} 这些子字段名一起吞掉，它们必须挂在 {@code ,} 后面。
     * {@code "_"} 只许来自第三方串（{@code AEItemKey#toString} 带组件时的 {@code " (with patches)"}，
     * 夹具是 {@code damage=50} 的真补丁镐，不是自写的假 {@code toString()}）。
     *
     * <p>格全部由<b>真取料腿</b>现造：{@code noteNoCandidate}（配给把这格夹成 usable&lt;units）出
     * {@code locked=}；{@code prepare}（请求 10 发、现场 3 枚）同一次提取出 {@code minCell[holder]=}
     * 与 {@code worst(req=10,got=3,…)}；{@code declNull=} 由 {@code declaredBatchFirings} 真走到
     * {@code cap} 那一支夹子、再补一档<b>带空白的机器 reason</b>（同一本账的写入口）；{@code inv=}
     * 取渲染瞬间的池子。
     *
     * <p>那一档带空白的 reason 是这条判据<b>对 {@code declNull=} 的牙</b>：自家六个 reason 串恰好
     * 都不含空白，只喂自家串时"这一格没折"数得出来的是 45 段、折了也是 45 段，用例永远绿。
     *
     * <p>{@code inflightBatches=} 与 {@code oldestInflightTicks=} 两格（#210 从停滞行搬过来）在这一条
     * 判据上<b>喂真值</b>：挂一笔永不回终局的在飞记录、基准 tick 写死，于是"把这两格接成一个常量
     * {@code 0}/{@code 0@none}"的写法也会红在这里——段数判据只证"这一格在"，值判据才证"这一格读的是
     * 台账那一本现成的账"。
     */
    @Test
    void per100rLineSplitsIntoExactlyTheKnownCellNames() {
        var pattern = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));
        var pool = widePool();
        pool.insertExact(V1, BigInteger.valueOf(3L), Actionable.MODULATE); // 只够 3 发：钉住 holder 腿
        pool.insertExact(BUCKET, BigInteger.valueOf(7L), Actionable.MODULATE);
        var probe = new ParallelBatchExtraction.Probe();

        // locked= 那一格：配给把这格夹成 usable<units（算得出发、却被账夹死）
        var job = newJob();
        job.tasks.put(pattern, progress(10L));
        MaterialAllocationFixtures.assign(job, pattern, share(V1, 0L));
        var locking = ParallelPlanReservation.build(job.tasks.entrySet(), Map.of(),
                job.materialAllocation);
        probe.noteNoCandidate(0, pattern.getInputs()[0], pool, null, pattern, locking);

        // minCell= 与 worst(...)：同一趟 holder 腿，请求 10 发、现场只够 3 发
        probe.noteRequestContext(10L, Long.MAX_VALUE, 9L, pattern);
        var batch = ParallelBatchExtraction.prepare(pattern, pool, null, 10L, new KeyCounter(),
                new KeyCounter(), RESERVATION_FREE, probe);
        assertNotNull(batch);
        assertEquals(3L, batch.executions(), "现场 3 枚、每发 1 枚：这一趟只算得出 3 发");
        assertEquals(1L, probe.lostCount, "请求 10 发只拿到 3 发：worst(...) 那一格才有配对读数");

        // declNull= 那一格的两档 reason，都走<b>真数据源</b>（这本账唯一的写入口是
        // ParallelBatchExtraction.Probe#noteDeclaredRejectReason，自家六支夹子经
        // declaredBatchFirings → noteDeclaredNull 也是走它）：
        // ①自家串——宿主容量那一道夹子当场把申报判成不适用，reason 就是 "cap"（不带空白）；
        var declaredNull = ParallelCraftingCpuLogic.declaredBatchFirings(new CappedHost(BigInteger.ONE),
                pattern, job.taskProgress(pattern), pool, null, RESERVATION_FREE, Long.MAX_VALUE,
                null, probe);
        assertNull(declaredNull, "申报被宿主容量夹到 1 发：这一支夹子必须当场交回 null 并记下 reason");
        // ②机器给的串——reason 键按外来串对待（宿主报回原因时点名是哪个键，形状同
        // UselessBigIntegerDeclaredHost#lastDeclaredBatchRejectReason），带组件的 AEItemKey#toString
        // 必带 " (with patches)"。少了这一档，"段数==格名数"对 declNull= 永远看不见：自家那六个串
        // 怎么拼都不出空白，"再有一格没折"这件事于是没有判据。
        probe.noteDeclaredRejectReason("cap " + V1);

        // 渲染前的库存由池子现快照：inv= 必须点名带组件的那个键（取料腿只用 SIMULATE，料不动；
        // 这里再补一枚，免得"腿吃掉了料"这一无关变化把 inv= 的断言变成跑运气的红）。
        pool.insertExact(V1, BigInteger.ONE, Actionable.MODULATE);
        // inflightBatches= 与 oldestInflightTicks= 喂真值：挂一笔<b>永不回终局</b>的在飞记录（台账
        // 那本现成的账唯一的写入口），基准 tick 写死。接成常量 0/0@none 的写法要红在下面那两条上。
        var inflightBatchId = java.util.UUID.randomUUID();
        var inflightReceipt = new UselessBigIntegerReceipt("overworld@1,2,3");
        inflightReceipt.noteBatchId(inflightBatchId);
        job.declaredTerminals.noteAdmitted(inflightReceipt, pattern, BigInteger.valueOf(4_096L),
                10L, null);
        var line = renderPer100rRefillLine(probe, pool, job, 10L + 4_213L);
        var tokens = List.of(line.split(" "));

        // 硬判据①（段数 == 格名数）：多一段就是格内漏空白，少一段就是格名漂了。
        assertEquals(PER_100R_CELLS.size(), tokens.size(),
                "per100r 按空白切出的段数必须等于已知格名数。整行=" + line + " 切分=" + tokens);
        for (int i = 0; i < PER_100R_CELLS.size(); i++) {
            assertTrue(tokens.get(i).startsWith(PER_100R_CELLS.get(i)),
                    "第 " + i + " 段必须以格名 " + PER_100R_CELLS.get(i) + " 开头，实得："
                            + tokens.get(i) + "。整行=" + line);
        }

        // 硬判据②（子字段仍可寻址）+（"_" 只来自外来空格）：四格逐格钉。
        var locked = tokens.get(PER_100R_CELLS.indexOf("locked="));
        assertTrue(locked.contains(",stored=") && locked.contains(",units=") && locked.contains(",usable=")
                        && locked.contains(oneToken(V1)) && locked.endsWith("]"),
                "locked= 的子字段要挂在 ',' 后面、带组件的键要折成 '_'：" + locked);
        var worst = tokens.get(PER_100R_CELLS.indexOf("worst("));
        assertTrue(worst.startsWith("worst(req=10,got=3,pwr=-1,cap=9,cls=FakePattern,bind=")
                        && worst.contains(oneToken(V1) + ",stored=") && worst.endsWith(",demand=1)"),
                "worst(...) 必须一格装下 req/got/pwr/cap/cls/bind，且 bind 的子字段仍用 ',' 相连：" + worst);
        var minCell = tokens.get(PER_100R_CELLS.indexOf("minCell="));
        assertTrue(minCell.startsWith("minCell=minCell[原型]=-,minCell[holder]=slot#0,")
                        && minCell.contains(oneToken(V1) + ",每发=1,池内=")
                        && minCell.contains(",份数=3,minCell[义务]=-"),
                "minCell= 的三格与每格子字段都要用 ',' 连（键的 (with patches) 折成 '_'）：" + minCell);
        var inv = tokens.get(PER_100R_CELLS.indexOf("inv="));
        assertTrue(inv.startsWith("inv=kinds=") && inv.contains(",total=") && inv.contains(",top=")
                        && inv.contains(oneToken(V1) + ",x"),
                "inv= 的 kinds/total/top 与每个键的现量都要挂在 ',' 后面：" + inv);
        // declNull= 逐字钉死：两档 reason 合成一格，机器给的那个串里三个空白全折成 "_"。
        // 每档计数 100——同一份 Probe 被 noteBatch 喂满 100 轮，每轮各并一次（不是新口径，
        // 只是这一格过去没有内容可并，看不见累计）。
        assertEquals("declNull=cap=100/" + oneToken("cap " + V1) + "=100",
                tokens.get(PER_100R_CELLS.indexOf("declNull=")),
                "declNull= 必须是一格一 token：reason 键里的外来空格在格出口折掉。整行=" + line);
        // 硬判据③（在飞那一族喂真值）：这两格不许被接成常量 0／0@none——纯"申报出去没回执、又不欠料"
        // 那一跑今天只有这两格看得见（停滞行在那一档压根不打）。
        assertEquals("inflightBatches=1", tokens.get(PER_100R_CELLS.indexOf("inflightBatches=")),
                "inflightBatches= 必须读台账那本现成的账（这一笔永不到终局）：" + line);
        assertEquals("oldestInflightTicks=4213@" + inflightBatchId.toString().substring(0, 8),
                tokens.get(PER_100R_CELLS.indexOf("oldestInflightTicks=")),
                "oldestInflightTicks= 的龄与 batchId 前缀同格点名，整格一 token：" + line);
    }

    /**
     * 停滞行那一行的<b>格名清单</b>（按空白切 token 的唯一口径）：顺序即
     * {@code ParallelCraftingCpuLogic#describeObligationStallLine} 格式串里的顺序。
     *
     * <p>与 {@link #PER_100R_CELLS} 同一判定：清单由用例手写，<b>不</b>由被测对象交出。停滞行有没有
     * {@code declaredReject=} 那种"格名不在格式串里、由返回串前导空格带进来"的格，这里核过一遍：
     * <b>没有</b>——13 个格名全部逐字写在格式串里，而每个实参（UUID、BigInteger、
     * {@code describeInventory(Exact)}、{@code blocked=} 出口折完的单 token）都不带空白。
     * 清单仍不许自交：自交时"整格消失""格名漂了"两型恒绿，只剩自洽。
     */
    private static final List<String> STALL_LINE_CELLS = List.of(
            "[chexsonsaeutils-declared]", "lane=", "obligation_stalled", "owed=", "pool=",
            "claimed=", "prodUntrusted=", "obChunks=", "obPull=", "obPayChunks=", "obPaid=",
            // 欠交付那一族（#208）：前 11 格是「欠料」，这几格是「万象炉申报批的交付口径」。
            // oldestInflightTicks= 一格带最老一笔的 batchId 前缀（<龄>@<id8>），仍是一格一 token。
            // pendingDelivery= 是 #216 交割账的待交割读数（<总欠量>枚[clamped]），一格一 token、无空白。
            // blocked= 保持在最后一格（三处按"最后一格"取它的用例不许跟着漂）。
            "inflightBatches=", "oldestInflightTicks=", "undelivered=", "pendingDelivery=", "blocked=");

    /**
     * 停滞行与 per100r 同一把尺（32c44e1 + 9a3bd59 三条口径）：整行按空白切后<b>段数必须恰等于</b>
     * {@link #STALL_LINE_CELLS} 的长度，且第 i 段以第 i 个格名开头。
     *
     * <p>两档都走<b>唯一渲染口</b> {@code describeObligationStallLine}（生产侧 {@code AELog.warn}
     * 打的就是它交出的整行）：
     * ①没有真失败格那一档——整格里有三处真带空白的第三方串：欠账样板头（带组件键的
     * {@code " (with patches)"}）、候选键头、{@code 本轮最后算的那张样板=} 的键头；
     * ②有真失败格那一档——候选网格与 {@code 回补受阻[...]} 必须折完仍按 {@code ","} 寻址。
     * 照 9a3bd59 的教训喂带空白的键：只喂自家不带空白的串时"又一格没折"数出来恒是 15 段，
     * 判据没有牙；带组件的 V1（damage=50 真补丁）的两个空白就是这一行的牙。
     *
     * <p>①那一档再把 {@code blocked=} 整格<b>逐字</b>钉死：光折空白而把自家子字段边界留回空格
     * （折点照样折成 {@code "_"}）段数判据看不见——逐字串把 {@code ","} 边界一并钉住。
     */
    @Test
    void stallLineSplitsIntoExactlyTheKnownCellNames() {
        var owedWithPatches = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 4L), new GenericStack(BUCKET, 1L)},
                        1L, null, Set.of(V1, BUCKET))},
                List.of(new GenericStack(V1, 1)));
        var ranFirst = new FakePattern(AEItemKey.of(Items.HOPPER),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(AEItemKey.of(Items.BOOKSHELF), 1)));

        // 档①：欠账样板本轮没被算（回补跑的是别张）⇒ 真失败格=本轮未算 + 本轮最后算的那张样板=。
        var poolNever = widePool();
        poolNever.insertExact(BUCKET, BigInteger.valueOf(3L), Actionable.MODULATE);
        var neverProbe = new ParallelBatchExtraction.Probe();
        ParallelBatchExtraction.refillObligationBatch(ranFirst, poolNever, null, 1L,
                RESERVATION_FREE, (what, wanted) -> 0L, neverProbe);
        var neverJob = newJob();
        var neverOrdered = new java.util.LinkedHashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>();
        neverOrdered.put(owedWithPatches, progress(1L));
        neverOrdered.forEach(neverJob.tasks::put);
        neverJob.batchObligations.incur(owedWithPatches, BigInteger.ONE);
        var neverLine = ParallelCraftingCpuLogic.describeObligationStallLine(
                java.util.UUID.randomUUID(), BigInteger.valueOf(7L), neverJob,
                new java.util.ArrayList<>(neverOrdered.entrySet()), poolNever,
                RESERVATION_FREE, neverProbe, 0L);
        assertStallLineOneCellOneToken(neverLine);
        assertEquals("blocked=<" + oneToken(V1) + "#i<TAG1>,owed=1,"
                        + "slot#0[" + oneToken(V1) + ",每发=4,池内=0,配额=无口径,可付发=0,该格份数=非本轮该格/"
                        + "minecraft:bucket,每发=1,池内=3,配额=无口径,可付发=3,该格份数=非本轮该格],"
                        + "真失败格=本轮未算,本轮最后算的那张样板=minecraft:bookshelf(FakePattern,槽数=1)"
                        + "#i<TAG2>>",
                stallBlockedCell(neverLine),
                "blocked= 整格逐字钉死：格内子字段一律 ','、候选间 '/'、第三方空白在格出口折成 '_'"
                        + "（旧空格 join 的候选网格与样板格在这一档必须红）：" + neverLine);

        // 档②：欠账样板本轮真算了且首格算不出发 ⇒ 真失败格[...] 点名，候选网格与 回补受阻[...] 同格。
        var poolFailing = widePool();
        poolFailing.insertExact(V1, BigInteger.ONE, Actionable.MODULATE);
        var failingProbe = new ParallelBatchExtraction.Probe();
        assertNull(ParallelBatchExtraction.prepareUnitPrototype(owedWithPatches, poolFailing, null,
                        new KeyCounter(), RESERVATION_FREE, failingProbe),
                "池里 1 枚、每发 4 枚：档②必须真走「这一格算不出 1 发」那一型");
        ParallelBatchExtraction.refillObligationBatch(owedWithPatches, poolFailing, null, 1L,
                RESERVATION_FREE, (what, wanted) -> 0L, failingProbe);
        var failingJob = newJob();
        var failingOrdered = new java.util.LinkedHashMap<IPatternDetails,
                ParallelExecutingCraftingJob.TaskProgress>();
        failingOrdered.put(owedWithPatches, progress(1L));
        failingOrdered.forEach(failingJob.tasks::put);
        failingJob.batchObligations.incur(owedWithPatches, BigInteger.ONE);
        var failingLine = ParallelCraftingCpuLogic.describeObligationStallLine(
                java.util.UUID.randomUUID(), BigInteger.valueOf(7L), failingJob,
                new java.util.ArrayList<>(failingOrdered.entrySet()), poolFailing,
                RESERVATION_FREE, failingProbe, 0L);
        assertStallLineOneCellOneToken(failingLine);
        var failingBlocked = stallBlockedCell(failingLine);
        assertTrue(failingBlocked.startsWith("blocked=<" + oneToken(V1)
                        + "#i<TAG1>,owed=1,slot#0["
                        + oneToken(V1) + ",每发=4,池内=1,配额=无口径,可付发=0,"),
                "候选网格的键头带两个空白（with patches）必须在格出口折完、子字段仍按 ',' 寻址："
                        + failingLine);
        assertTrue(failingBlocked.contains(",该格份数=非本轮该格],真失败格[slot#0,"
                        + oneToken(V1) + ",每发=4,"),
                "候选收尾接 ,真失败格[——这一格的点名不许再被空格切成段：" + failingLine);
        assertTrue(failingBlocked.contains(",回补受阻[slot#0_" + oneToken(V1)
                        + "_got=0_landed=0_why=ranDry"),
                ",回补受阻[ 的 join 折完且与格内自带下划线同字面：" + failingLine);
    }

    /** 按空白切的那把尺：段数必须恰等于 {@link #STALL_LINE_CELLS}，且第 i 段以第 i 个格名开头。 */
    private static void assertStallLineOneCellOneToken(String line) {
        var tokens = List.of(line.split(" "));
        assertEquals(STALL_LINE_CELLS.size(), tokens.size(),
                "停滞行按空白切出的段数必须等于已知格名数。整行=" + line + " 切分=" + tokens);
        for (int i = 0; i < STALL_LINE_CELLS.size(); i++) {
            assertTrue(tokens.get(i).startsWith(STALL_LINE_CELLS.get(i)),
                    "第 " + i + " 段必须以格名 " + STALL_LINE_CELLS.get(i) + " 开头，实得："
                            + tokens.get(i) + "。整行=" + line);
        }
    }

    /**
     * 从停滞行里取 {@code blocked=} 那一格（段数判据已保证它是最后一段），并把<b>样板身份标</b>
     * 换成占位符——{@code #i<hex>} 是运行期实例哈希，逐字比对要先把它钉成 {@code #i<TAGn>}。
     */
    private static String stallBlockedCell(String line) {
        return normalizeIdentityTags(
                List.of(line.split(" ")).get(STALL_LINE_CELLS.indexOf("blocked=")));
    }

    /** 身份标按出现次序换成 {@code #i<TAG1>}/{@code #i<TAG2>}…（占位符本身不带空白）。 */
    private static String normalizeIdentityTags(String line) {
        // 样板身份标今天交两段：{@code #i<hex>} 实例哈希 + {@code #s<hex>} 跨会话摘要。
        // 逐字比对前把<b>整个</b>身份标折成一个占位符——摘要段跟着哈希段一起吞掉，
        // 期望串里的 {@code #i<TAGn>} 才仍逐字对得上（往格内加 token 不新增"格"）。
        var matcher = java.util.regex.Pattern.compile("#i[0-9a-f]{1,8}(?:#s[0-9a-f?]{1,16})?")
                .matcher(line);
        var described = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            matcher.appendReplacement(described, "#i<TAG" + (++index) + ">");
        }
        return matcher.appendTail(described).toString();
    }

    /**
     * {@code [chexsonsaeutils-stall]} 行 {@code topUp=…quota=} 那一块的<b>已知格名清单</b>
     * （台账 #199 补的尺：{@code c17c20a} 在这一块加了 {@code noCandWhy=[末次]} 与
     * {@code 每发件数=} 两格，全仓却没有任何一处段数锚点——"又一格被空格劈开"与"格名并了"
     * 在这一行今天都看不见）。
     */
    private static final List<String> STALL_TOP_UP_BLOCK_CELLS = List.of(
            "topUp=", "topUpChunks=", "topUpExact=",
            // 读数 6（台账 #225）：聚合两格拆到逐枚键的流水 + 自报覆盖面，跟着真形状各占一格。
            "topUpKeys=", "topUpKeysCov=",
            "noCandWhy=", "每发件数=", "stored=", "quota=");

    /**
     * 那一块的<b>段数 == 格名数</b>判据（与 {@link #STALL_LINE_CELLS} 同一把尺，只是范围收在
     * {@code topUp=} 到 {@code quota=} 这一块——行首到 {@code topUp} 之间还有 {@code output=}/{@code
     * backoffBlind=} 等第三方原文格，块尾还有 {@code inv=}/{@code tasks=}，整行段数不由这一块定）。
     *
     * <p>两档都从<b>真渲染口</b> {@code StallProbe#describeStallLine} 取整行原文：
     * 档①本轮没记过 noCand ⇒ {@code noCandWhy=[末次]} 只剩身份标，块内恰九段、逐段以格名开头，
     * 块尾紧跟 {@code inv=}；
     * 档②喂 {@code ParallelBatchExtraction#noteNoCandidate} 今天真交回的那份串形状（身份标 +
     * 一个空格 + 键原文）⇒ 块内<b>十</b>段：这一段是 census 跟真形状的地方——noCandWhy 的键尾今天
     * 自带一个空格，判据把它逐字钉成第七段，并钉住第八段必须是 {@code 每发件数=9}（槽倍率），
     * 谁把这两格并了、或把 {@code 每发件数=} 挤到键尾前面，这一档就红。
     */
    @Test
    void stallLineTopUpCellBlockSplitsIntoExactlyTheKnownCellNames() {
        // 档①：noCandKey 为空（本轮一次 noCand 都没记）
        var bareJob = newJob();
        var barePool = widePool();
        var bareProbe = new ParallelCraftingLaneState(null, java.util.UUID.randomUUID(), 10L)
                .logic().new StallProbe();
        bareProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                new ParallelBatchExtraction.Probe(), new java.util.HashMap<>(), barePool,
                bareJob.declaredTerminals, 0L);
        var bareLine = bareProbe.describeStallLine(java.util.UUID.randomUUID(), 1, bareJob, barePool,
                null, "…", "", 0L, -2L);
        assertTopUpBlockCells(bareLine, STALL_TOP_UP_BLOCK_CELLS);
        assertTrue(tokenAfterTopUpBlock(bareLine).startsWith("inv="),
                () -> "块尾必须接 inv=，块内不许夹带别的格：" + bareLine);

        // 档②：真形状的身份标 + 键尾
        var stuck = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(BUCKET, 1L)}, 9L, null, Set.of(BUCKET))},
                List.of(new GenericStack(PRODUCT, 1)));
        var keyedJob = newJob();
        keyedJob.tasks.put(stuck, progress(1L));
        var keyedPool = widePool();
        keyedPool.insertExact(BUCKET, BigInteger.valueOf(7L), Actionable.MODULATE);
        var keyedNoCand = stuck.getDefinition()
                + ParallelCraftingCpuLogic.patternIdentityTag(stuck) + "#slot0 " + BUCKET;
        var keyedProbe = new ParallelCraftingLaneState(null, java.util.UUID.randomUUID(), 10L)
                .logic().new StallProbe();
        keyedProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                new ParallelBatchExtraction.Probe(), new java.util.HashMap<>(), keyedPool,
                keyedJob.declaredTerminals, 0L);
        var keyedLine = keyedProbe.describeStallLine(java.util.UUID.randomUUID(), 1, keyedJob,
                keyedPool, null, "…", keyedNoCand, 7L, -1L);
        var keyedCells = List.of(topUpBlockTokens(keyedLine));
        assertEquals(STALL_TOP_UP_BLOCK_CELLS.size() + 1, keyedCells.size(),
                () -> "真形状的 noCandWhy 键尾今天自带一个空格＝第十段，多一段少一段都算红, 切分="
                        + keyedCells + " 整行=" + keyedLine);
        assertTrue(keyedCells.get(5).startsWith("noCandWhy=[末次]"),
                () -> "第六段仍是 noCandWhy=（[末次] 身份标不许漂）：" + keyedCells);
        assertEquals("minecraft:bucket", keyedCells.get(6),
                () -> "第七段是 noCandWhy 的键尾原文（今天就是这个形状）：" + keyedCells);
        assertEquals("每发件数=9", keyedCells.get(7),
                () -> "第八段必须是每发件数=，值取自本轮任务表那一格的槽倍率 9：" + keyedCells);
        assertEquals("stored=7", keyedCells.get(8), () -> "第九段 stored=：" + keyedCells);
        assertEquals("quota=无配额口径(-1)", keyedCells.get(9), () -> "第十段 quota=：" + keyedCells);
        assertTrue(tokenAfterTopUpBlock(keyedLine).startsWith("inv="),
                () -> "块尾必须接 inv=：" + keyedLine);
    }

    /** 取 {@code topUp=} 起、到 {@code quota=} 止（含）的那一块 token。 */
    private static String[] topUpBlockTokens(String line) {
        var tokens = line.split(" ");
        int start = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].startsWith("topUp=")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "整行里没有 topUp= 那一块：" + line);
        int end = -1;
        for (int i = start + 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("quota=")) {
                end = i;
            }
        }
        assertTrue(end >= 0, "整行里没有 quota= 那一格：" + line);
        return java.util.Arrays.copyOfRange(tokens, start, end + 1);
    }

    /** 那一块之后的第一段（块尾边界，必须是 {@code inv=}）。 */
    private static String tokenAfterTopUpBlock(String line) {
        var tokens = line.split(" ");
        return tokens[indexOfFirst(tokens, "topUp=") + topUpBlockTokens(line).length];
    }

    private static int indexOfFirst(String[] tokens, String cellPrefix) {
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].startsWith(cellPrefix)) {
                return i;
            }
        }
        throw new AssertionError("整行里没有 " + cellPrefix + " 那一格");
    }

    /** 段数 == 格名数，且第 i 段以第 i 个格名开头（与 {@link #assertStallLineOneCellOneToken} 同尺）。 */
    private static void assertTopUpBlockCells(String line, List<String> cells) {
        var tokens = topUpBlockTokens(line);
        assertEquals(cells.size(), tokens.length,
                () -> "topUp 块按空白切出的段数必须等于已知格名数。切分=" + List.of(tokens)
                        + " 整行=" + line);
        for (int i = 0; i < cells.size(); i++) {
            assertTrue(tokens[i].startsWith(cells.get(i)),
                    "第 " + i + " 段必须以格名 " + cells.get(i) + " 开头，实得：" + tokens[i]
                            + " 切分=" + List.of(tokens) + " 整行=" + line);
        }
    }

    /** 走真渲染口：把这份 {@code Probe} 喂满 100 轮，取回 per100r 那一行原文（不直调格式化方法）。 */
    private static String renderPer100rRefillLine(
            ParallelBatchExtraction.Probe probe, ListCraftingInventory pool,
            ParallelExecutingCraftingJob job, long currentTick) {
        var appender = new CapturingRefillAppender();
        var context = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        try {
            appender.start();
            configuration.addAppender(appender);
            configuration.getRootLogger()
                    .addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
            context.updateLoggers();

            var stallProbe = new ParallelCraftingLaneState(
                    ParallelRecursiveFinalOutputDeliveryTest.diagnosticCluster(), java.util.UUID.randomUUID(), 10L)
                    .logic().new StallProbe();
            for (int round = 0; round < 100; round++) {
                stallProbe.noteBatch(BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, Long.MAX_VALUE, 0L,
                        probe, new java.util.HashMap<>(), pool, job.declaredTerminals, currentTick);
            }
            return appender.lastLine("[chexsonsaeutils-batch] per100r");
        } finally {
            configuration.getRootLogger().removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        }
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

    /** 日志出口那把尺：toString 里的空白折成 {@code "_"}（与格出口同一把）。 */
    private static String oneToken(Object key) {
        return String.valueOf(key).replace(' ', '_');
    }

    /** 只收 INFO 及以上的原文行：渲染点的产物除了日志没有第二个出口。 */
    private static final class CapturingRefillAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        private CapturingRefillAppender() {
            super("obWhy-per100r-capture", null,
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

    /** 只交回固定 SIMULATE 现量、一枚都不搬的回补通道（把"网络真 0"与"精确键不等"分开读数）。 */
    private static ParallelBatchExtraction.ObligationRefill readoutRefill(AEKey key, long simulated) {
        return new ParallelBatchExtraction.ObligationRefill() {
            @Override
            public long pull(@Nullable AEKey what, long wanted) {
                return 0L; // 伸手拿到 0：走 got==0 那一支
            }

            @Override
            public long probe(@Nullable AEKey what, long wanted) {
                return key.equals(what) ? simulated : 0L;
            }
        };
    }

    /**
     * 候选键各带各的留物（{@code FakeInput} 那份固定 {@code remainder} 测不出"声明键无、模糊键有"）：
     * {@code getRemainingKey} 按<b>伸手的键</b>交回，与 AE2 合成样板对模糊替代的形状一致。
     */
    private record KeyedRemainderInput(GenericStack[] candidates, long multiplier,
            Set<AEKey> accepts, Map<AEKey, AEKey> remainders) implements IPatternDetails.IInput {

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
            return remainders.get(template);
        }
    }

    /**
     * 宽域假网络：库存按 {@link BigInteger} 记，一次 {@code extract} 最多交出 {@code long}
     * ——与 AE2 存储服务同一条形状，所以 >long 的一发天然要跨批才搬得完。
     */
    private static final class WideNetwork {
        private final Map<AEKey, BigInteger> stock = new HashMap<>();

        private void add(AEKey key, BigInteger amount) {
            stock.merge(key, amount, BigInteger::add);
        }

        private BigInteger amountOf(AEKey key) {
            return stock.getOrDefault(key, BigInteger.ZERO);
        }

        private ParallelBatchExtraction.ObligationRefill refill() {
            return new ParallelBatchExtraction.ObligationRefill() {
                @Override
                public long pull(@Nullable AEKey what, long wanted) {
                    if (what == null || wanted <= 0L) {
                        return 0L;
                    }
                    long got = amountOf(what).min(BigInteger.valueOf(wanted))
                            .min(BIG_LONG).longValue();
                    if (got > 0L) {
                        stock.put(what, amountOf(what).subtract(BigInteger.valueOf(got)));
                    }
                    return got;
                }

                @Override
                public long probe(@Nullable AEKey what, long wanted) {
                    if (what == null || wanted <= 0L) {
                        return 0L;
                    }
                    return amountOf(what).min(BigInteger.valueOf(wanted)).min(BIG_LONG).longValue();
                }
            };
        }
    }

    /** 谎报网络：SIMULATE 有求必应，MODULATE 只按 {@code network} 里真有的量给。 */
    private static final class ClaimingRefill implements ParallelBatchExtraction.ObligationRefill {
        private final KeyCounter network;
        private final KeyCounter claims;
        private final java.util.Set<AEKey> noted = new java.util.HashSet<>();

        private ClaimingRefill(KeyCounter network, KeyCounter claims) {
            this.network = network;
            this.claims = claims;
        }

        @Override
        public long pull(@Nullable AEKey what, long wanted) {
            if (what == null || wanted <= 0L) {
                return 0L;
            }
            long got = Math.min(wanted, network.get(what));
            if (got > 0L) {
                network.remove(what, got);
            }
            return got;
        }

        @Override
        public long probe(@Nullable AEKey what, long wanted) {
            return what == null ? 0L : Math.min(wanted, claims.get(what));
        }

        @Override
        public void noteUnboundedSupply(@Nullable AEKey what) {
            if (what != null) {
                noted.add(what);
            }
        }
    }

    private static ParallelExecutingCraftingJob newJob() {
        return new ParallelExecutingCraftingJob(
                emptyPlan(), ignored -> {
                }, (CraftingLink) null, null);
    }

    /**
     * 越界任务不许把占料锁乘成一堵墙（实机 2026-09-25 19:08 那一跑的形状）。
     *
     * <p>锁主单槽无退路、一发吃 1e6 枚、还剩 1e13 发 ⇒ 它真需 1e19 枚，越出 {@code long}。
     * 旧形状拿 {@code TaskProgress.value} 那份<b>饱和镜像</b>（在越界任务上它就是 {@code Long.MAX_VALUE}
     * 那道哨兵）去乘每发件数，锁当场被乘成 {@code Long.MAX} ⇒ 池子里躺着 9.2E 枚同键料而别人
     * {@code usable=0}——日志原文 {@code locked=[minecraft:lava stored=9223372036854775807 usable=0]
     * noCand=3154}。可信口径算不出来就是不锁：宁可少一道悲观预留，也不把"锁主自己都要分几万轮
     * 才吃得下"的那一截当成一笔当场可兑现的债权。
     */
    @Test
    void beyondLongTaskRemainingNeverBecomesAnUnscalableLock() {
        var locker = new FakePattern(AEItemKey.of(Items.BARREL),
                new FakeInput[]{new FakeInput(
                        new GenericStack[]{new GenericStack(V1, 1_000_000L)}, 1L, null, Set.of(V1))},
                List.of(new GenericStack(PRODUCT, 1)));
        var job = newJob();
        job.tasks.put(locker, wideProgress(new BigInteger("10000000000000")));

        var pool = new java.util.HashMap<AEKey, Long>();
        pool.put(V1, Long.MAX_VALUE);
        pool.put(V2, 1_000L);
        var reservation = MaterialAllocationFixtures.build(
                job.tasks.entrySet(), pool, NO_PLAN, NO_TAKEN);

        assertEquals(Long.MAX_VALUE, reservation.usableFor(V1, Long.MAX_VALUE,
                        ANOTHER_THOUSAND_A_FIRING, 5L, ANOTHER_THOUSAND_A_FIRING.getInputs()[0], false),
                "锁主自己都吃不下的一堵墙，不配把 9.2E 枚料判成别人取不动");
    }

    private static ParallelExecutingCraftingJob.TaskProgress progress(long firings) {
        var progress = new ParallelExecutingCraftingJob.TaskProgress();
        progress.value = firings;
        return progress;
    }

    private static ListCraftingInventory variantPool(long v1, long v2) {
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(V1, v1, Actionable.MODULATE);
        inventory.insert(V2, v2, Actionable.MODULATE);
        return inventory;
    }

    /** 假网络：只按现存多少给多少，给不出就少给——与 AE2 存储服务的 {@code extract} 同语义。 */
    private static ParallelBatchExtraction.ObligationRefill networkRefill(KeyCounter network) {
        return (what, wanted) -> {
            if (what == null || wanted <= 0L) {
                return 0L;
            }
            long got = Math.min(wanted, network.get(what));
            if (got > 0L) {
                network.remove(what, got);
            }
            return got;
        };
    }

    /** 收料但整批拒收的宿主：用来钉"拒收零副作用"这条底线。 */
    private static final class RejectingHost implements DeclaredBatchHost {
        @Nullable
        private BigInteger lastDeclared;
        private long lastPrototypeUnits;

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
            lastDeclared = declaredExecutions;
            lastPrototypeUnits = unitPrototype.length > 0 && unitPrototype[0] != null
                    ? unitPrototype[0].get(V1) : 0L;
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

    /** 报多不收：回执永远交回 {@code declared + 1}，用来钉"入账只认申报值"那道 {@code min(declared)}。 */
    private static final class OverReportingHost implements DeclaredBatchHost {
        @Nullable
        private BigInteger lastDeclared;

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
            lastDeclared = declaredExecutions;
            return declaredExecutions.add(BigInteger.ONE);
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return true;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }

    /** 只实现接口、不覆写能力探测的宿主：默认口径必须永远是"不支持"。 */
    private static final class PlainHost implements DeclaredBatchHost {
        @Override
        public BigInteger declaredBatchCapacity(IPatternDetails pattern) {
            return BIG_LONG;
        }

        @Override
        public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
                BigInteger declaredExecutions) {
            return declaredExecutions;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return true;
        }

        @Override
        public boolean isBusy() {
            return false;
        }
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

    private record FakePattern(AEItemKey definition, IPatternDetails.IInput[] slots,
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
