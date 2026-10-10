package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三方<b>自定义 {@code AEKey} 类型</b>（Mekanism 那型：同一种资源、自己的 {@code AEKeyType}）躺在
 * CPU 宽域池子里时，取料点的那一格必须<b>算得出份数、推得动</b>。
 *
 * <h2>实机症状</h2>
 * 停滞行同一格里并排出过两个互相矛盾的读数：{@code noCandWhy=…#slot1 MekanismKey{stack=mekanism:redstone}}
 * 后面紧跟 {@code declared=[MekanismKey{stack=mekanism:redstone} x1 pool=8 …]}，而 {@code ncSplit} 把这格
 * 记进 {@code nolist}（"样板写了候选、池里一枚没有"）。池账说 {@code pool=8}，候选宇宙说"没有这一族"
 * ⇒ 这一格永远递不出候选 ⇒ 万象样板那一发永远开不了工 ⇒ {@code mekanism:alloy_infused} 从未被产出，
 * 下游整条义务链饿死。
 *
 * <h2>两把尺子的出处（本文件钉住的就是这一条）</h2>
 * <ul>
 * <li>候选宇宙那一半走 AE2 的 {@code ListCraftingInventory#findFuzzyTemplates} →
 * {@code KeyCounter#findFuzzy}，而 {@code KeyCounter} 的主索引是
 * {@code Reference2ObjectMap<Object,VariantCounter>}（声明在
 * {@code appeng/api/stacks/KeyCounter.java:48}，实现是 {@code Reference2ObjectOpenHashMap}）
 * ——按 <b>{@code getPrimaryKey()} 的引用</b>分格；</li>
 * <li>停滞行 {@code pool=} 那一格与我们的全部族/配额 machinery 走 <b>{@code equals}</b>：
 * {@code WideCraftingInventory#amount} 的溢出侧表是 {@code Map<AEKey,BigInteger>}（{@code HashMap}），
 * {@code ParallelPlanReservation#isUnboundedSupply} 与 {@code shareOf} 也按 {@code equals} 认族。</li>
 * </ul>
 *
 * <p>{@code AEItemKey} 的主键是注册表里的 {@code Item} 单例（引用恒等），两把尺子恰好同值，所以这条缝
 * 在物品键上看不见。自定义键类型只要把主键存成<b>值对象</b>（注册名一类，{@code equals} 相等而
 * 引用不等——从 NBT／网络包重建出来的键就是这一型），同一枚池料就一边看得见（{@code pool=8}）、
 * 一边看不见（{@code nolist}）。
 *
 * <p><b>溢出那一档不是这条缝的全部</b>（上一刀只修到这里）：{@code pool=} 读出的数若是
 * {@code long} 天花板之上那截余数，兜住它的是<b>溢出侧表</b>（按 {@code equals}）；而池料塞得进
 * {@code long} 时<b>压根没有侧表条目</b>，那一格只有主表那一腿——主表按引用分格，读数照旧是 0，
 * 第三支候选因此照样不触发。实机最常见的那一档正是这一型。所以 {@code exactStored} 的<b>主表那一腿</b>
 * 也必须先按 {@code equals} 找到桶（复用同一本池账的 {@code resolvePoolKey}），两条腿落在同一格上；
 * {@link #theCustomKeySlotStillReadsItsEightWhenNothingOverflowed()} 钉的就是这一档。
 *
 * <h2>修法</h2>
 * 取料点与申报腿的候选宇宙改成读<b>同一本池账</b>（{@link ParallelBatchExtraction} 的
 * {@code exactStored}，与停滞行 {@code pool=} 那一句是同一个调用），不再让"池里有没有这一格"
 * 由 AE2 的引用索引单独说了算；这一本读口内部先把键按 {@code equals} 落到池子真装着的那一枚，
 * 主表与侧表从此同一把尺。不新增问网络的探针、不新增账本、不加闸门；份数、配额、销账
 * 那些算式一字未动；"认量绝不进池"那条不变式也不碰（这里只多递候选，付款仍走池子自己的
 * {@code extract}，交不出就整批归位）。
 *
 * <p><b>同族还有七条腿伸的是同一把尺上的声明键</b>：NeoEco 销账那两腿（push 前的现量快照
 * {@code ParallelCraftingCpuLogic#readPoolWide} 与 push 后的现量差 {@code #measurePoolOutflow}）、
 * 占料账本交出去的池子现量读口（{@code #pendingPoolClaims} 递给 {@code BatchObligationLedger} 的
 * 那一个函数），两处<b>诊断读数</b>（义务停滞行 {@code blocked=} 与停滞行 {@code backoffBlind=}
 * 的 {@code 池内=} 那一格），以及<b>进判据</b>的那两脚——倒终点产物
 * （{@code #flushDyeableRecursiveFinalOutput}：池里躺着终点产物却按申报键读出现量 0 ⇒ 早退 ⇒
 * 产物永远留在 CPU 库存里）与实物回补（{@code #insertInternal} 问产出缓冲还欠几枚：挂账那格是
 * 样板申报的那一枚、交回来的是交货那一刻的另一枚 ⇒ {@code EMPTY} ⇒ 这笔回补不认）。
 * 下面七条各钉一处、各自红在自己那一行，反向两条钉的是普通
 * {@code AEItemKey} 不因为换读法而读空。
 *
 * <p>诊断读数不是"无所谓"：下一轮排障发回来的就是 {@code blocked=}／{@code backoffBlind=}／
 * {@code minCell=} 里的 {@code 池内=}——值对象主键的第三方键在那几格里报 0，就被读成"池子里真没有"，
 * <b>读数以 0 撒谎比没有读数更坏</b>。
 */
final class ParallelCustomKeyPoolRulerTest {

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /**
     * 自定义键类型：{@code codec}/{@code readFromPacket} 这些注册表口径本文件用不到，
     * 也不碰 {@code AEKeyType#getRawId()}（那要 AE2 的 keytypes 注册表）。
     */
    private static final class TestChemicalType extends AEKeyType {
        private TestChemicalType() {
            super(ResourceLocation.fromNamespaceAndPath("testmek", "chemical"),
                    TestChemicalKey.class, Component.literal("testmek:chemical"));
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            return null;
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return new TestChemicalKey();
        }
    }

    private static final AEKeyType CHEMICAL_TYPE = new TestChemicalType();

    /**
     * 形状照 {@code me.ramidzkh.mekae2.ae2.MekanismKey}（{@code reference-sources} 里没有它的源码，
     * 口径取自 {@code applied-mekanistics-574300-5978711.jar} 的反编译）：{@code getType} 交回自己的
     * 键类型、{@code equals} 只比资源本体（不比数量）、{@code hashCode = Objects.hash(本体)}、
     * {@code getFuzzySearchMaxValue} 沿用 {@code AEKey} 默认的 0（不可做耐久模糊区间）。
     * 唯一被本用例放大的差别：主键交回<b>值对象</b>而不是注册表单例——那正是"池账看得见、
     * AE2 的模糊索引看不见"这一缝的用力点。
     */
    private static final class TestChemicalKey extends AEKey {
        /** 值对象主键：每个键实例一个新对象，{@code equals} 相等、引用不等。 */
        private final ResourceLocation primary =
                ResourceLocation.fromNamespaceAndPath("mekanism", "redstone");

        @Override
        public AEKeyType getType() {
            return CHEMICAL_TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag(HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            tag.putString("id", primary.toString());
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return primary;
        }

        @Override
        public ResourceLocation getId() {
            return primary;
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal("mekanism:redstone");
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return false;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            return Objects.equals(primary, ((TestChemicalKey) other).primary);
        }

        @Override
        public int hashCode() {
            return Objects.hash(primary);
        }

        @Override
        public String toString() {
            return "TestChemicalKey{stack=" + primary + "}";
        }
    }

    /** 样板声明的那一枚（万象样板候选表里的键）。 */
    private static final AEKey DECLARED = new TestChemicalKey();
    /** 池子里躺着的那一枚：与声明键 {@code equals} 相等，但主键是另一个引用。 */
    private static final AEKey IN_POOL = new TestChemicalKey();
    private static final AEItemKey ORE = AEItemKey.of(Items.IRON_ORE);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey MISSING = AEItemKey.of(Items.NETHER_STAR);

    /** 每发每格各吃 1 枚。 */
    private static final long UNITS_PER_FIRING = 1L;

    /** 没有占料、没有提交期配给：这一档不夹份数，把"候选宇宙"这一条腿单独摆出来。 */
    private static final ParallelPlanReservation RESERVATION = MaterialAllocationFixtures.build(
            Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>of().entrySet(),
            Map.of(), Map.of(), Map.of());

    /** 万象样板那一型：slot0 是自定义键，slot1 是同批的普通物品格。 */
    private static final OmniPattern OMNIVERSAL = new OmniPattern(
            AEItemKey.of(Items.GRINDSTONE),
            new OmniInput[]{
                    new OmniInput(new GenericStack[]{new GenericStack(DECLARED, UNITS_PER_FIRING)},
                            UNITS_PER_FIRING),
                    new OmniInput(new GenericStack[]{new GenericStack(ORE, UNITS_PER_FIRING)},
                            UNITS_PER_FIRING)
            },
            List.of(new GenericStack(PRODUCT, 1)));

    /** 只吃 {@code IRON_ORE} 那一格的一张：反向守门用，免得把自定义键那格卷进同一条断言。 */
    private static final OmniPattern ORE_ONLY = new OmniPattern(
            AEItemKey.of(Items.FURNACE),
            new OmniInput[]{new OmniInput(
                    new GenericStack[]{new GenericStack(ORE, UNITS_PER_FIRING)}, UNITS_PER_FIRING)},
            List.of(new GenericStack(PRODUCT, 1)));

    /**
     * 取料点：这一格算得出份数、推得动。
     *
     * <p>改前红在 {@code prepare} 直接交 {@code null}（slot0 递不出候选 ⇒ 整批判死），
     * 并且 {@code ncNoListing} 涨 1 —— 实机那一跑把这一档报了 1285 次。
     */
    @Test
    void theCustomKeySlotPaysItsFiringsInsteadOfReportingNoListing() {
        var pool = poolWithOverflow();
        pool.insert(ORE, 8L, Actionable.MODULATE);
        var probe = new ParallelBatchExtraction.Probe();

        var batch = ParallelBatchExtraction.prepare(OMNIVERSAL, pool, null, 4L,
                new KeyCounter(), new KeyCounter(), RESERVATION, probe);

        assertNotNull(batch, "池账里躺着这格要的那一枚，就不是缺料：不许把能推的一步判死");
        assertEquals(4L, batch.executions(), "份数由这一格与别的格的现场量一起定，"
                + "不受「模糊索引看不见这枚自定义键」影响");
        assertEquals(4L, batch.holder()[0].get(IN_POOL), "自定义键那一格交出 4 枚");
        assertEquals(0L, probe.ncNoListing, "「样板写了候选、池里一枚没有」这一档不许再涨");
        assertEquals(0L, probe.ncEmpty, "也不是真缺料");
        assertEquals(4L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                "另一格真扣走 4 枚（8 枚起点剩下 4 枚）");
        assertEquals(LONG_MAX.add(BigInteger.valueOf(4L)),
                WideCraftingInventory.amount(pool, IN_POOL),
                "这一格付掉 4 枚后池账读出 LONG_MAX+4：伸手的是池子真装着的那一枚，"
                        + "同一份料不许被拆进两个分格");
    }

    /**
     * FIX-E：池料<b>塞得进 {@code long}</b>（实机最常见那一档，这里 8 枚）时同一格照样算得出份数、推得动。
     *
     * <p>上一刀只修了溢出那一档：那一档的 {@code pool=8} 是天花板之上的余数，读的是<b>溢出侧表</b>
     * （{@code HashMap}，按 {@code equals}）；而 8 枚这一档没有侧表条目，唯一那一腿是主表
     * {@code list.get()}，它按 {@code getPrimaryKey()} 的<b>引用</b>分格 ⇒ 声明键落在另一个桶里，
     * 读数仍是 0 ⇒ 第三支候选不触发 ⇒ 这一格照样被报成"池里一枚没有"。
     *
     * <p>改前红在 {@code prepare} 直接交 {@code null}（slot0 递不出候选 ⇒ 整批判死）＋
     * {@code ncNoListing} 涨 1，与溢出那一档改前的红字同一形状。
     */
    @Test
    void theCustomKeySlotStillReadsItsEightWhenNothingOverflowed() {
        var pool = poolWithPlainEight();
        pool.insert(ORE, 4L, Actionable.MODULATE);
        // 夹具前提：这一档只有主表那一腿，侧表帮不上忙
        assertEquals(8L, pool.list.get(IN_POOL),
                "夹具前提：8 枚躺在池子那个实例的分格里（没越界 ⇒ 主表是真值、侧表无条目）");
        assertEquals(0L, pool.list.get(DECLARED),
                "夹具前提：主表那一腿按 getPrimaryKey() 的引用分格，声明键落在另一个桶里 ⇒ 读 0");
        assertTrue(!iteratorHasAny(pool.findFuzzyTemplates(DECLARED)),
                "夹具前提：AE2 的模糊索引同样看不见这一格");

        var probe = new ParallelBatchExtraction.Probe();
        var batch = ParallelBatchExtraction.prepare(OMNIVERSAL, pool, null, 4L,
                new KeyCounter(), new KeyCounter(), RESERVATION, probe);

        assertNotNull(batch, "池子里躺着 8 枚这一格要的那一枚（塞得进 long）：不是缺料，不许判死整批");
        assertEquals(4L, batch.executions(), "份数由这一格（8 枚）与另一格（4 枚）的现场量一起定");
        assertEquals(4L, batch.holder()[0].get(IN_POOL), "自定义键那一格交出 4 枚");
        assertEquals(0L, probe.ncNoListing, "「样板写了候选、池里一枚没有」这一档不许再涨");
        assertEquals(0L, probe.ncEmpty, "也不是真缺料");
        assertEquals(0L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE),
                "另一格那 4 枚真扣走（起点 4 枚 ⇒ 清零）");
        assertEquals(BigInteger.valueOf(4L), WideCraftingInventory.amount(pool, IN_POOL),
                "这一格付掉 4 枚后池账读出 4 枚：伸手的是池子真装着的那一枚，不留 8 枚的幽灵");
    }

    /**
     * 申报腿同尺：{@code declaredPayableCeiling} 不许因为候选宇宙看不见这一格而把份数算成 0。
     *
     * <p>改前红在 {@code declared=0}（实机停滞行的 {@code declared=0}＋{@code declaredReject} 累加那一型）。
     */
    @Test
    void theDeclaredCeilingReadsTheSamePoolLedger() {
        var pool = poolWithOverflow();
        pool.insert(ORE, 4L, Actionable.MODULATE);

        var declared = ParallelBatchExtraction.declaredPayableCeiling(OMNIVERSAL, pool, null,
                RESERVATION, new BigInteger("1000000000000000000"));

        assertEquals(BigInteger.valueOf(4L), declared,
                "上界由两格的现场量取最小：自定义键那一格池账有量就不是 0");
    }

    /** 义务回补那条腿也认这枚自定义键：先补料、再照单付得出去。 */
    @Test
    void obligationPaymentReachesTheCustomKeySlotThroughTheSameUniverse() {
        var pool = poolWithOverflow();
        pool.insert(ORE, 3L, Actionable.MODULATE);

        var paid = ParallelBatchExtraction.payDeclaredObligation(OMNIVERSAL, pool, null, 3L,
                RESERVATION, null);

        assertNotNull(paid, "欠账交割伸手的那套候选与取料点同一本池账");
        assertEquals(3L, paid.executions());
        assertEquals(0L, pool.extract(ORE, Long.MAX_VALUE, Actionable.SIMULATE), "另一格 3 枚真付出去");
        assertEquals(LONG_MAX.add(BigInteger.valueOf(5L)),
                WideCraftingInventory.amount(pool, IN_POOL), "自定义键那一格付掉 3 枚：余 5 枚在侧表");
    }

    /**
     * 反向半边（不许把缺料报成有料）：池账压根没有的这一格仍按"递不出候选"记，申报仍是 0。
     *
     * <p>放开候选宇宙不等于放行：这里判的是"池账有没有这一枚"，没有就一枚也不凭空给。
     */
    @Test
    void aSlotThePoolLedgerReallyDoesNotHoldStillShorts() {
        var pool = poolWithOverflow();
        pool.insert(ORE, 4L, Actionable.MODULATE);
        var pattern = new OmniPattern(AEItemKey.of(Items.CRAFTING_TABLE),
                new OmniInput[]{
                        new OmniInput(new GenericStack[]{new GenericStack(MISSING,
                                UNITS_PER_FIRING)}, UNITS_PER_FIRING),
                        new OmniInput(new GenericStack[]{new GenericStack(ORE, UNITS_PER_FIRING)},
                                UNITS_PER_FIRING)
                },
                List.of(new GenericStack(PRODUCT, 1)));
        var probe = new ParallelBatchExtraction.Probe();

        assertNull(ParallelBatchExtraction.prepare(pattern, pool, null, 4L,
                new KeyCounter(), new KeyCounter(), RESERVATION, probe),
                "池账里没有的就是缺料：放开候选宇宙不许变成凭空给料");
        assertEquals(1L, probe.ncNoListing, "这一格仍然记「递不出候选」，不是被跳过");
        assertEquals(BigInteger.ZERO, ParallelBatchExtraction.declaredPayableCeiling(pattern, pool,
                null, RESERVATION, BigInteger.TEN), "申报上界照旧归零");
        assertEquals(4L, WideCraftingInventory.amount(pool, ORE).longValueExact(),
                "没付出去就一枚都不许动池子");
    }

    // ---------- 销账与占料那三条腿：读池子没过池账口径 ----------

    /**
     * 销账快照（{@code ParallelCraftingCpuLogic#readPoolWide}）：push <b>前</b>那一次现量读数按声明键
     * 裸读。要点名的键出自宿主自报清单与样板候选表（{@code neoEcoOutflowKeys}），正是"声明那一份实例"
     * ⇒ 落在另一个桶里恒 0。
     *
     * <p>这一条只问快照那一腿：期望 8 枚（池里真躺着的量），改前红在 0。
     */
    @Test
    void theOutflowSnapshotReadsThePoolLedgerInstanceOfAThirdPartyKey() {
        var pool = poolWithPlainEight();
        var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(OMNIVERSAL, null, null);

        assertEquals(BigInteger.valueOf(8L),
                ParallelCraftingCpuLogic.readPoolWide(pool, keys).get(DECLARED),
                "push 前的现量快照读出池里那 8 枚：按声明键裸读落在另一个桶里，读回来的是 0");
    }

    /**
     * 销账差值（{@code ParallelCraftingCpuLogic#measurePoolOutflow}）：push <b>后</b>那一次现量读数同样
     * 按声明键裸读 ⇒ 池子被读成空 ⇒ 差值把<b>还留在池里的那一截</b>也算成出账（真扣 5 枚却报 8 枚），
     * 两腿一起裸读时更是恒 0（{@code ate=0} ⇒ 配额账不扣、旁人可反复申报同一份存量）。
     *
     * <p>push 前的快照在这里<b>手填</b>成 8 枚——那是快照那一腿改后本就交回的读数：为的是这一条只红在
     * 差值那一行，不被同用例里靠前的快照断言替它挡刀。
     */
    @Test
    void theOutflowDeltaSubtractsThePoolLedgerReadingTakenByThePush() {
        var pool = poolWithPlainEight();
        var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(OMNIVERSAL, null, null);
        var before = new java.util.HashMap<AEKey, BigInteger>();
        before.put(DECLARED, BigInteger.valueOf(8L));

        assertEquals(5L, pool.extract(IN_POOL, 5L, Actionable.MODULATE),
                "夹具动作：宿主 push 真从池子里提走 5 枚，池里还剩 3 枚");

        assertEquals(BigInteger.valueOf(5L),
                ParallelCraftingCpuLogic.measurePoolOutflow(pool, keys, before).getExact(DECLARED),
                "出账＝8 枚现量减池里剩下的 3 枚：改后读的是池子真装着的那一枚；按声明键裸读把池子读成空"
                        + " ⇒ 剩下那 3 枚也被当成出账销掉");
    }

    /**
     * 占料（{@code ParallelCraftingCpuLogic#pendingPoolClaims} 递给
     * {@code BatchObligationLedger#seedRemaining} 的那一个池子现量读口）：点名的键出自欠账样板的
     * <b>候选表</b> ⇒ 裸读恒 0 ⇒ 顺位排水"排出去 0 枚"⇒ 欠着的料一枚也不遮，同一份存量被旁人反复申报。
     */
    @Test
    void thePoolClaimForPendingObligationsReadsThePoolLedgerInstance() {
        var pool = poolWithPlainEight();
        var obligations = new BatchObligationLedger();
        obligations.incur(OMNIVERSAL, BigInteger.valueOf(3L));
        var tasks = List.of(Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>entry(
                OMNIVERSAL, taskWith(3L)));

        var claims = ParallelCraftingCpuLogic.pendingPoolClaims(obligations, tasks, pool, RESERVATION);

        assertEquals(BigInteger.valueOf(3L), claims.getExact(DECLARED),
                "欠 3 发就把池里那 3 枚口粮遮住：排水先按池账找到池子真装着的那一枚才排得出量，"
                        + "裸读恒 0 时这份占料是空的");
    }

    /**
     * 反向守门（普通 {@code AEItemKey}）：声明键与池子里那一份本来就在<b>同一个分格</b>（主键是注册表
     * 单例）⇒ 换读法不许把本来看得见的那一格读没。三条腿各给一个数，逐字与改前同口径。
     */
    @Test
    void anOrdinaryItemKeyReadsTheSameThreeNumbersThroughTheNewRuler() {
        var keys = ParallelCraftingCpuLogic.neoEcoOutflowKeys(ORE_ONLY, null, null);

        var snapshotPool = poolWithPlainOre();
        assertEquals(BigInteger.valueOf(8L),
                ParallelCraftingCpuLogic.readPoolWide(snapshotPool, keys).get(ORE),
                "快照：物品键走哪把尺都是同一格，8 枚不许读成 0");

        var outflowPool = poolWithPlainOre();
        var before = new java.util.HashMap<AEKey, BigInteger>();
        before.put(ORE, BigInteger.valueOf(8L));
        assertEquals(5L, outflowPool.extract(ORE, 5L, Actionable.MODULATE), "夹具动作：真扣走 5 枚");
        assertEquals(BigInteger.valueOf(5L),
                ParallelCraftingCpuLogic.measurePoolOutflow(outflowPool, keys, before).getExact(ORE),
                "差值：池里剩 3 枚就只销 5 枚，与改前同一个数");

        var obligations = new BatchObligationLedger();
        obligations.incur(ORE_ONLY, BigInteger.valueOf(4L));
        assertEquals(BigInteger.valueOf(4L), ParallelCraftingCpuLogic.pendingPoolClaims(
                        obligations,
                        List.of(Map.<IPatternDetails, ParallelExecutingCraftingJob.TaskProgress>entry(
                                ORE_ONLY, taskWith(4L))),
                        poolWithPlainOre(), RESERVATION).getExact(ORE),
                "占料：欠 4 发×每发 1 枚 ⇒ 遮 4 枚，与改前同一个数");
    }

    // ---------- 两处诊断读数的池内=：同一个桶里的 0 会骗下一轮排障 ----------

    // ---------- 倒终点产物与实物回补那两脚：读与扣都伸容器真装着的那一枚 ----------

    /**
     * 脚一（{@code ParallelCraftingCpuLogic#flushDyeableRecursiveFinalOutput} 的
     * {@code inventory.extract(finalKey, …, SIMULATE)} 现量 {@code <= 0} 即早退）：
     * 终点产物<b>已经在池里</b>（交进来的是交付那一刻的那一枚实例），收尾要倒出去时伸的却是
     * 计划申报的那一枚 ⇒ 落在另一个桶里 ⇒ 现量 0 ⇒ 整脚跳过 ⇒ 用户报的
     * "补种的东西卡在 cpu 库存里"就是这一型。
     *
     * <p>这一条<b>只</b>红在交付那一格：靠前的三条断言（8 枚真在池里、按申报键向 AE2 那条
     * {@code getStored} 读是 0、requester 一笔没收）改前改后同绿，替不了这一刀的刀口。
     */
    @Test
    void theFinalOutputFlushPoursOutTheInstanceThePoolActuallyHolds() throws Exception {
        var logic = new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L).logic();
        assertEquals(8L, logic.insertBuffered(IN_POOL, 8L, Actionable.MODULATE),
                "夹具动作：终点产物按交付那一刻的那一枚进池子");
        assertEquals(8L, logic.getStored(IN_POOL), "夹具前提：池子里真躺着 8 枚");
        assertEquals(0L, logic.getStored(DECLARED),
                "夹具前提：按申报键向 AE2 那条池子读口伸手落在另一个桶里（那是 AE2 契约面，本刀未动）");

        var requester = new RecordingRequester();
        var job = newDyeableRecursiveJob(new GenericStack(DECLARED, 8L), newCpuLinkTiedTo(requester));
        logic.flushDyeableRecursiveFinalOutput(job);

        assertEquals(8L, requester.delivered(IN_POOL),
                "终点产物必须按池里那一枚整笔倒给收货方：改前那一句按申报键裸读现量读出 0 ⇒ 早退 ⇒ 一枚都不交");
        assertEquals(0L, logic.getStored(IN_POOL),
                "交出去的 8 枚必须真从池子那一格里抽走：只放开读数而扣落在另一个桶，收尾的 storeItems() 会把同一份料再投一次网");
    }

    /**
     * 脚二（{@code ParallelCraftingCpuLogic#insertInternal} 问产出缓冲的
     * {@code job.waitingFor.extract(what, …, SIMULATE)} 现量 {@code <= 0} 即 {@code EMPTY}）：
     * 挂账那一格是 {@code reserveOne} 按<b>样板申报</b>的键实例记的，实物回来时 AE2 交的是
     * 交货那一刻的另一枚 ⇒ 落在另一个桶里 ⇒ 这一笔回补被判成"没在等它"，一枚不收。
     *
     * <p>{@code lanesByKey}（{@code HashMap}，按 {@code equals}）本来就认得出这一枚——
     * "找得到 lane 却认不出账"正是这一族的形状。摘账那一脚也钉住：改后缓冲必须清零，
     * 否则 {@code waitingFor.list.isEmpty()} 那一条收工判据永远不成立。
     */
    @Test
    void theReturnedPhysicalProductIsAccountedAgainstTheBufferThatHoldsTheOwedEntry() throws Exception {
        var logic = logicAwaiting(DECLARED);

        assertEquals(8L, logic.getWaitingFor(DECLARED),
                "夹具前提：挂账真挂在样板申报的那一枚上");
        assertEquals(0L, logic.getWaitingFor(IN_POOL),
                "夹具前提：交回来的那一枚落在另一个桶里（这一条改前改后同绿）");

        assertEquals(8L, logic.insert(IN_POOL, 8L, Actionable.SIMULATE),
                "实物回到账口必须认这笔回补：改前问账按交来的那一枚裸读 ⇒ EMPTY ⇒ 一枚都不收");
        assertEquals(8L, logic.insert(IN_POOL, 8L, Actionable.MODULATE),
                "实收那一笔同样要认：改前这里也是 EMPTY，回补整段作废");
        assertEquals(0L, logic.getWaitingFor(DECLARED),
                "摘账必须落在挂账那一格上：只放开问账而摘走另一格，这一格永远清不掉、作业永远收不了工");
    }

    /**
     * 反向守门（普通 {@code AEItemKey} 的那两脚）：物品键的主键是注册表单例 ⇒ 申报的那一枚与容器里
     * 那一份本来就在同一个分格 ⇒ 换读法之后两脚的数与改前逐字相同（各 8 枚、各清零）。
     */
    @Test
    void anOrdinaryItemKeyPoursOutAndAccountsTheSameTwoAmounts() throws Exception {
        var declaredItem = AEItemKey.of(new ItemStack(Items.CHEST));
        var deliveredItem = AEItemKey.of(new ItemStack(Items.CHEST));
        assertTrue(declaredItem != deliveredItem, "夹具前提：两枚是两个实例（主键仍是同一个 Item 注册表单例）");
        assertEquals(declaredItem.getPrimaryKey(), deliveredItem.getPrimaryKey(),
                "夹具前提：物品键的主键是注册表单例 ⇒ 两把尺子本来就同值");

        var logic = new ParallelCraftingLaneState(null, UUID.randomUUID(), 0L).logic();
        logic.insertBuffered(deliveredItem, 8L, Actionable.MODULATE);
        var requester = new RecordingRequester();
        logic.flushDyeableRecursiveFinalOutput(
                newDyeableRecursiveJob(new GenericStack(declaredItem, 8L), newCpuLinkTiedTo(requester)));
        assertEquals(8L, requester.delivered(declaredItem), "倒出去的还是 8 枚，换读法不许把看得见的那格读没");
        assertEquals(0L, logic.getStored(deliveredItem), "扣走的还是同一格那 8 枚");

        var bufferLogic = logicAwaiting(declaredItem);
        assertEquals(8L, bufferLogic.insert(deliveredItem, 8L, Actionable.MODULATE),
                "回补认的还是 8 枚：与改前同一个数");
        assertEquals(0L, bufferLogic.getWaitingFor(declaredItem),
                "摘账摘的还是同一格");
    }

    // ---------- 那两脚的夹具 ----------

    /** 通过真实提交入口把已申报产出挂入等待账，使用同一作业完成模拟和实际到账验证。 */
    private static ParallelCraftingCpuLogic logicAwaiting(AEKey awaited) {
        var network = new appeng.me.storage.NetworkStorage();
        var cluster = ParallelRecursiveFinalOutputDeliveryTest.offlineCluster(network);
        var logic = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L).logic();
        var emitted = new KeyCounter();
        emitted.add(awaited, 8L);
        var plan = new CraftingPlan(new GenericStack(PRODUCT, 1L), 0L, false, false,
                new KeyCounter(), emitted, new KeyCounter(), Map.of());
        assertTrue(logic.trySubmitJob(cluster.grid(), plan,
                appeng.api.networking.security.IActionSource.empty(), null).successful());
        return logic;
    }

    /** 递归计划作业：终点产物与契约量都按交进来的那份 {@code finalOutput} 定版。 */
    private static ParallelExecutingCraftingJob newDyeableRecursiveJob(
            GenericStack finalOutput, @Nullable CraftingLink link) {
        var base = new CraftingPlan(finalOutput, 0L, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(),
                finalOutput.amount(), Map.of(), Map.of(), null, null);
        return new ParallelExecutingCraftingJob(plan, ignored -> {
        }, link, null);
    }

    /** CPU 侧那根 link，并按产线的成对口径接到收货方那根上（同一颗 nexus）。 */
    private static CraftingLink newCpuLinkTiedTo(ICraftingRequester requester) {
        var cpuLink = new CraftingLink(
                CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                (appeng.api.networking.crafting.ICraftingCPU) null);
        var nexus = new CraftingLinkNexus(cpuLink.getCraftingID());
        cpuLink.setNexus(nexus);
        var requesterLink = new CraftingLink(
                CraftingCpuHelper.generateLinkData(cpuLink.getCraftingID(), false, true), requester);
        requesterLink.setNexus(nexus);
        return cpuLink;
    }

    /**
     * 收货方：按 {@code equals} 记账（{@code HashMap}）——{@code KeyCounter} 的主表按主键引用分格，
     * 用它记会把"交进来的是哪一枚"这个夹具事实混进断言里。
     */
    private static final class RecordingRequester implements ICraftingRequester {
        private final java.util.Map<AEKey, Long> received = new java.util.HashMap<>();

        long delivered(AEKey what) {
            return received.getOrDefault(what, 0L);
        }

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
            if (mode == Actionable.MODULATE) {
                received.merge(what, amount, Long::sum);
            }
            return amount;
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return ImmutableSet.of();
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
        }

        @Override
        public IGridNode getActionableNode() {
            return null;
        }
    }

    /** 欠账排水只读任务表上的顺位：这一档给一张挂在表上的任务条目（发数读数本身不参与排水）。 */
    private static ParallelExecutingCraftingJob.TaskProgress taskWith(long firings) {
        var task = new ParallelExecutingCraftingJob.TaskProgress();
        task.value = firings;
        return task;
    }

    /** 池子里 8 枚普通物品键：主键是注册表单例 ⇒ 声明键与池内实例本来就是同一格。 */
    private static WideCraftingInventory poolWithPlainOre() {
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insertExact(ORE, BigInteger.valueOf(8L), Actionable.MODULATE);
        return pool;
    }

    /** 池子里 {@code LONG_MAX+8} 枚自定义键：主表钉在天花板、侧表躺着多出来的 8 枚。 */
    private static WideCraftingInventory poolWithOverflow() {
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insertExact(IN_POOL, LONG_MAX.add(BigInteger.valueOf(8L)), Actionable.MODULATE);
        return pool;
    }

    /**
     * 池子里 8 枚自定义键（塞得进 {@code long}，实机最常见那一档）：不挂侧表条目，
     * 这一格的读数<b>只有</b>主表那一腿。
     */
    private static WideCraftingInventory poolWithPlainEight() {
        var pool = new WideCraftingInventory(ignored -> {
        });
        pool.insertExact(IN_POOL, BigInteger.valueOf(8L), Actionable.MODULATE);
        return pool;
    }

    private static boolean iteratorHasAny(Iterable<AEKey> keys) {
        for (AEKey ignored : keys) {
            return true;
        }
        return false;
    }

    /** 模糊／万象样板那一型：候选表只写一枚精确键，{@code isValid} 按 {@code equals} 认。 */
    private record OmniInput(GenericStack[] candidates, long multiplier)
            implements IPatternDetails.IInput {

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

    private record OmniPattern(AEItemKey definition, OmniInput[] slots,
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
