package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchRemainders;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.bigint.WideCraftingInventory;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 只按现场量决定"一次推进几发"的提取：不读任何规划期附属信息。
 *
 * <p>批量口径：每槽按样板候选顺位挑第一个撑得住的键，能撑几发由该键存量算出来，整任务的份数取各槽最小值。
 * 一次批量里每发的取料组成完全一致，按 holder 实际量反推份数的宿主才吃得下；
 * 混用多个候选会破坏这个一致性，所以宁可用同一个候选、把零碎留给下轮。
 *
 * <p>挑不出候选的槽（必须混用多个键才凑得满一发，AE2 原生允许此形态）退到单发混取，
 * 保持与 AE2 相同的可开工范围。
 *
 * <p><b>无限源不经池子</b>：创造盘／KubeJS 注册的无限岩浆在提交侧就不向网络抽（池子里恒为 0），
 * 而 AE2 的候选宇宙只看池子里现存的键 —— 于是这一格连候选都递不出来。这一条腿在这里补齐：
 * 候选回到样板声明的表（{@link #usableTemplates}），份数不被它的"现场量"钉住
 * （{@link #primaryCandidate}），付款就地认量、申报上界不算它（{@link #declaredPayableCeiling}），
 * 整批归位时那一格跳过（{@link #reinjectTaken}）：认量一旦变成往池子里 {@code insert}，
 * 就会被 {@code storeItems()} 当残池投回网络 = 凭空多一份料。
 *
 * <p>环催化物的起步份数不在这里解决：那是规划期多算一发产出的余料，随 {@code usedItems}
 * 一次提进 CPU 库存（排序那一半见 {@code RingSeedLedger}）。这里管的是<b>当场能取几枚</b>：
 * 池子现量先被环种留底削成可花量（{@code ParallelPlanReservation#usableFor}），环分量自己
 * 还没跑完的那一截不许被旁人从这一格抽走。
 *
 * <p><b>某一格一发真需 {@code >long} 枚</b>（倍率 × 模板量越出 {@code long}）时，这一发按
 * {@link #payInChunks} 分几刀从池子里累付：每刀 ≤{@code long}（AE2 的 {@code extract} 只能吃
 * {@code long}），而池子是宽域的（{@code WideCraftingInventory}），装得下这一发的真值也就给得出。
 * 凑不齐整发即整批归位、这批一枚不付——半份料不算一发，绝不交出一个"看着像一发"的批量；
 * 交出去的那一份真值记在 {@link Batch#exactPaid()}，销账按它出（见
 * {@code ParallelCraftingCpuLogic#recordTakenInputs}），{@code holder} 上的长读数只给宿主与诊断看。
 */
final class ParallelBatchExtraction {

    private ParallelBatchExtraction() {
    }

    /**
     * 候选表读数最多点几个键：这一串要进每 100 tick 一条的停滞行，带 NBT 的键名动辄七八十字符，
     * 点三个已经看得出"是哪一型候选"，剩下的报个数。
     */
    private static final int CANDIDATE_READOUT_LIMIT = 3;

    /**
     * 停滞行的网络同族读数最多点几个精确键：这一串要进每 100 tick 一条的停滞行，且每点一个键就
     * 多一次 {@code extract(SIMULATE)}。只在"伸手拿到 0"那一支算一次（不进热循环），一轮一行读数
     * 封顶四个键；命中上限时行里明写 {@code 同族键数=N+}，不把"看到的四个"报成"一共四个"。
     */
    static final int NETWORK_FAMILY_READOUT_LIMIT = 4;

    /**
     * 一次批量决策交出的这批料。
     *
     * <p>{@code holder} 是 AE2 与宿主看得见的 {@code long} 形状；某一格真需 {@code >long} 时它
     * 只能钉在天花板，<b>那份真值另记在 {@code exactPaid}</b>（{@code null} 即每格都装得进
     * {@code long}，{@code holder} 就是真值）。销账一律读 {@code exactPaid}，拿
     * {@code holder} 参与减法/比较就是又一次把饱和值当了量。
     *
     * <p>{@code mintedUnits} 按<b>键</b>记这一格里有多少枚是<b>就地认量的无限源</b>（{@code null}
     * 即一格也没有）：那些枚数从未离开池子，回滚时把它们 {@code insert} 回去就是凭空多一份料——
     * 下一刻 {@code storeItems()} 会把它当残池投回网络。
     *
     * <p>为什么记到<b>键</b>而不是记"这一格"：单发混取那一腿上，同一格可以先真抽走一段有限候选、
     * 再退到无限候选把剩下的补齐。按格跳过会把同格里<b>真从池子抽走</b>的那一段永久丢在池外；
     * 按键相减才既留住真料、又不还凭空的量。归位出口只认这份账，不另起第二道判据。
     */
    record Batch(KeyCounter[] holder, long executions, @Nullable WideKeyCounter[] exactPaid,
            @Nullable WideKeyCounter[] mintedUnits) {

        Batch(KeyCounter[] holder, long executions) {
            this(holder, executions, null, null);
        }
    }

    /**
     * 批量决策的只读探针：只记数，不参与任何判定。
     *
     * <p>用来回答"一次交互为什么只推进一发"——均一批量走通了几次、退单发混取几次、
     * 是槽挑不出候选、真扣料与估算不符，还是被占料账本夹住。
     */
    static final class Probe {
        long uniformOk;
        long uniformFallback;
        long noCandidate;
        long takenMismatch;
        long resClamp;
        /**
         * 份数被"本批该族剩余份额 ÷ 每发合计件数"夹小的次数（现场有料但份额不够）。
         * <b>只记 k 倍 holder 腿</b>（{@code widePayment == false} 那一支的显式族夹）；
         * 宽域那一支的族份额走 {@code batchScopePayable}，不在这格计数里。
         */
        long familyClamp;
        /**
         * 最后一次 noCand 的现场证据（停滞行按 {@code noCandWhy=} 原样打印这一串）：
         * 哪张样板第几格、这一格递得出的最好候选是谁、样板声明的候选表里都有什么。
         *
         * <p>样板头带 <b>实例身份标</b> {@code #i<hex>}（{@link ParallelCraftingCpuLogic#patternIdentityTag}）：
         * AE2 的样板按 {@code getDefinition()} 覆写 {@code equals}，同名两张在按样板记的账里是同一个键，
         * 光看名字配不上 {@code quotaBlind=} 的 {@code src=} 点的那一张（实机 {@code lane cc5a2c03} 那一跑
         * 两格点的压根不是同一张万象样板）。
         */
        String noCandKey = "";
        long noCandStored;
        long noCandQuota = -2L;
        /**
         * noCand 的三档归因：一条 "noCand=970" 修不了任何东西，三档各有不同的修法与现场形态。
         * <ul>
         * <li>{@code ncEmpty}：这一格所有候选在现场都是 0——真的没料（网络也没搬进来）；</li>
         * <li>{@code ncLocked}：现场有料、一发也撑得起，却被我们自己的配给账夹到付不起一发
         * ——锁死在配给表，不在料；</li>
         * <li>{@code ncShort}：现场有料、本槽配额也在，是跨槽同键的线性约束与族份额把它压到
         * 不足一发。</li>
         * <li>{@code ncNoListing}：这一格<b>连候选都递不出来</b>。真实的成因不是"样板没写候选"，
         * 而是<b>候选宇宙只有池子里现存的键</b>：{@code usableTemplates} 委托 AE2 的
         * {@code CraftingCpuHelper.getValidItemTemplates}，而它拿 {@code inv.findFuzzyTemplates(
         * what)} 过一遍池子——样板写了候选、池子里一枚没有（无限源在提交侧就被刻意跳过，压根不抽进
         * 池子）时，这一格就是零候选。与"候选都在、现场全 0"是两种修法：前者要改候选宇宙
         * （见 {@link #usableTemplates} 的无限源合成），后者才是真缺料。
         * 实机 2026-09-24 13:50 那一跑 {@code ncSplit=empty1000/locked0/short0} 把两型记成了同一个数，
         * 日志于是把"看不见"报成"没料"。</li>
         * </ul>
         */
        long ncEmpty;
        long ncLocked;
        long ncShort;
        long ncNoListing;
        /**
         * 被自己配给夹死的那一格：键、现场量、这一发本槽应占的件数、账面可用量。
         *
         * <p>必须带上<b>样板身份与槽号</b>（同 {@link FailingCell} 那套 owner 形状：
         * {@code definition#slotN}）：实机一行 {@code locked502} 只给一个全局键与三个数，
         * 分不出是<b>哪张样板的第几格</b>被夹——502 次里点的常常不是同一格。纯读数，不参与判据。
         */
        String ncLockedOwner = "";
        int ncLockedSlot = -1;
        String ncLockedKey = "";
        long ncLockedStored;
        long ncLockedUnits;
        long ncLockedUsable;

        /**
         * 记下"这一格为什么挑不出候选"的现场读数，并把它归进三档之一。
         *
         * <p>{@code quota} 的三种读数对应三条不同的修法：{@code -1} 是这张样板压根没有配额口径
         * （不夹）、{@code 0} 是被配给夹死（表说这一格不许从这键付）、正数则是现场量不够。
         * 把这三者混成一句 noCand，实机上就只能反复改错地方。
         *
         * <p>还有第四型：{@code -2}＝<b>这一格一个可计数候选都没递出来</b>（或压根没有配给表可问），
         * 它与 {@code -1}"有候选、有键、只是这张样板不在表里"是两件事——一个要问候选宇宙为什么空，
         * 一个要问配给表为什么没建。读数层分开点名（{@code ParallelCraftingCpuLogic
         * #describeNoCandQuota}），判据一字不动。
         *
         * <p>{@link #noCandKey} 要点名到<b>哪张样板的第几格</b>与<b>样板声明的候选表</b>：一条
         * {@code noCandWhy=<none>} 什么都定不了（实机 2026-09-25 00:15 那一跑 994 次 nolist、
         * 115 个无限键，日志里一个名字都读不出来）。
         */
        void noteNoCandidate(int slotIndex, IPatternDetails.IInput slot, ListCraftingInventory inventory,
                @Nullable Level level, IPatternDetails details, ParallelPlanReservation reservation) {
            long bestStored = -1L;
            boolean anyPayableOnSite = false; // 现场有够一发的候选
            boolean locked = false; // 有够一发的候选，却被配给账夹到不够
            boolean sawCandidate = false; // 这一格递得出至少一个可计数的键
            String bestKey = "<none>";
            if (slot != null && slot.getPossibleInputs() != null) {
                var templates = usableTemplates(inventory, slot, level, reservation);
                boolean substitutable = reservation != null && countDistinctKeys(templates) > 1;
                for (var template : templates) {
                    if (template == null || template.key() == null) {
                        continue;
                    }
                    var key = template.key();
                    BigInteger exactUnits = exactUnits(slot.getMultiplier(), template.amount());
                    long units = saturate(exactUnits);
                    if (units <= 0L) {
                        continue;
                    }
                    sawCandidate = true;
                    long stored = inventory.extract(key, Long.MAX_VALUE,
                            appeng.api.config.Actionable.SIMULATE);
                    if (stored <= bestStored) {
                        continue;
                    }
                    bestStored = stored;
                    bestKey = key.toString();
                    noCandQuota = reservation == null
                            ? -2L : reservation.quotaFor(key, details, slot);
                    // "撑不撑得起一发"按真值判：现场量与每发件数都钉在天花板上时，
                    // 拿两个哨兵比大小会给出相反的结论。
                    if (exactStored(inventory, key).compareTo(exactUnits) < 0) {
                        continue;
                    }
                    anyPayableOnSite = true;
                    long usable = reservation == null
                            ? stored
                            : reservation.usableFor(key, stored, details, 1L, slot, substitutable);
                    if (usable < units) {
                        locked = true;
                        // owner 与 key 都取第三方 toString() 原文（带组件时是 "<id> (with patches)"）：
                        // 这里不折，折点在它们那一格的出口（ParallelCraftingCpuLogic
                        // #describeLockedReadout 的 locked=[...] 一次折），别给每个来源各写一次。
                        ncLockedOwner = details == null
                                ? "?" : String.valueOf(details.getDefinition());
                        ncLockedSlot = slotIndex;
                        ncLockedKey = key.toString();
                        ncLockedStored = stored;
                        ncLockedUnits = units;
                        ncLockedUsable = usable;
                    }
                }
            }
            if (bestStored < 0L) {
                noCandStored = 0L;
                noCandQuota = -2L;
            } else {
                noCandStored = bestStored;
            }
            noCandKey = (details == null
                    ? "?" : String.valueOf(details.getDefinition())
                            + ParallelCraftingCpuLogic.patternIdentityTag(details))
                    + "#slot" + slotIndex + ' '
                    + (bestStored < 0L ? "no-candidate" : bestKey)
                    + " declared=[" + describeDeclaredCandidates(slot, inventory, level,
                            reservation, details) + "]";
            if (locked) {
                ncLocked++;
            } else if (anyPayableOnSite) {
                ncShort++;
            } else if (!sawCandidate) {
                ncNoListing++;
            } else {
                ncEmpty++;
            }
        }

        /**
         * 样板<b>声明</b>的候选表逐个点名：模板量、是不是无限源、池子里对应多少、这一格收不收它。
         *
         * <p>读的是声明表而不是 {@link #usableTemplates} 的输出——"递不出候选"这一档要问的正是
         * "样板明明写了键，为什么提取口看不见"，只回吐池子那半边的结果就还是读不出因。
         */
        private static String describeDeclaredCandidates(@Nullable IPatternDetails.IInput slot,
                ListCraftingInventory inventory, @Nullable Level level,
                @Nullable ParallelPlanReservation reservation, @Nullable IPatternDetails details) {
            if (slot == null || slot.getPossibleInputs() == null) {
                return "<none>";
            }
            var declared = slot.getPossibleInputs();
            var table = new StringBuilder();
            int listed = 0;
            int skipped = 0;
            for (var candidate : declared) {
                if (candidate == null || candidate.what() == null) {
                    continue;
                }
                if (listed >= CANDIDATE_READOUT_LIMIT) {
                    skipped++;
                    continue;
                }
                if (listed++ > 0) {
                    table.append(", ");
                }
                table.append(candidate.what()).append(" x").append(candidate.amount())
                        .append(" pool=").append(exactStored(inventory, candidate.what()))
                        .append(" unbounded=")
                        .append(reservation != null && reservation.isUnboundedSupply(candidate.what())
                                ? "yes" : "no")
                        // quota= 三态读数（纯读数，不进判据）：把"没配额口径"（nolist 那型）与
                        // "配额夹死"（0 那型）在读数上分开——过去一支 quota=-2 把两型压成一个码。
                        .append(" quota=").append(describeQuotaBasis(reservation, candidate.what(),
                                details, slot))
                        .append(" accepts=").append(slot.isValid(candidate.what(), level)
                                ? "yes" : "no");
            }
            if (skipped > 0) {
                table.append(", +").append(skipped).append(" more");
            }
            return table.toString();
        }

        /**
         * <b>纯读数</b>：把一个候选的 {@code quotaExact} 三态印成人话——<b>没配额口径</b>交"无口径"、
         * 份额 0 交"0"、真份额交那个数。与 {@code ParallelPlanReservation#quotaExact} 同一条判据
         * （同一个族合计、同一格豁免），不参与任何判据、不新增拒绝分支。
         */
        private static String describeQuotaBasis(@Nullable ParallelPlanReservation reservation,
                @Nullable AEKey key, @Nullable IPatternDetails details,
                @Nullable IPatternDetails.IInput slot) {
            if (reservation == null || details == null) {
                return "无口径";
            }
            var quota = reservation.quotaExact(key, details, slot);
            if (quota == null) {
                return "无口径";
            }
            return quota.signum() == 0 ? "0" : quota.exactValue().toString();
        }

        long execSum;

        /** 请求份数没被满足的次数（含退回单发混取）。 */
        long lostCount;
        /** 丢得最狠的那一次：其余字段与它同属一次提取，跨任务取 max 归不出因。 */
        long lostFirings;
        long lostReq;
        long lostGot;
        long lostPwr;
        long lostCap;
        String lostCls = "";
        /** 把份数压到 {@code lostGot} 的那个槽：现场存量 ÷ 每发合计需求。 */
        String lostBindKey = "";
        long lostBindStored;
        long lostBindDemand;

        /** 声明批量的读数（见 {@code DeclaredBatchHost}）：推送次数、实收份数合计、整批拒收次数。 */
        long declaredPushes;
        BigInteger declaredFiringsSum = BigInteger.ZERO;
        long declaredRejected;
        /**
         * 申报交回 {@code null} 的<b>分型读数</b>（纯读数，不参与任何判据）：记每一张样板<b>第一支</b>
         * 把申报打到 ≤1 的夹子（{@code fold}／{@code cap}／{@code rep-in}／{@code rep-out}／
         * {@code power}／{@code payable}）。过去 {@code declaredReject} 只说得出"没申报"，
         * 说不出"为什么没申报"——这一型读数补的正是那一格。随 {@code Probe} 每 tick 新建、不落 NBT。
         *
         * <p>键按<b>外来串</b>对待：自家那六支夹子恰好都不带空白，但这一本账的写入口不保证只有它们
         * （第三方宿主给的 reason、带组件的 {@code AEKey#toString} 都是 {@code " (with patches)"}）。
         * 这里不折，折点在 {@code declNull=} 那一格的出口（{@code ParallelCraftingCpuLogic
         * #describeDeclaredNullReasons} 一次折），别给每个来源各写一次。
         */
        final java.util.Map<String, Long> declaredNullReasons = new java.util.LinkedHashMap<>();

        void noteDeclaredRejectReason(String why) {
            declaredNullReasons.merge(why, 1L, Long::sum);
        }
        /**
         * 整批拒收的<b>点名读数</b>（纯读数，不参与任何判据）：一格 = {@code 样板@宿主#机器给的出口}，
         * 值 = 本 tick 命中该格的次数。
         *
         * <p>为什么非要有它：实机那一跑只有 {@code declaredReject=99} 一个聚合数——四型出口同影，
         * 上一轮只能靠"点名"才定性得着是谁在拒、在哪个出口拒。
         *
         * <p>封顶 {@value #DECLARED_REJECT_CELL_LIMIT} 格（与 {@code obWhy} 同风格：格间用 {@code /} 连、
         * 装不下的粘到最后一格后面补一格 {@code /更多=N}），格数记进
         * {@link #declaredRejectCellsDropped}。随 {@code Probe} 每 tick
         * 新建、不落 NBT。
         */
        final Map<String, Long> declaredRejectCells = new LinkedHashMap<>();
        /** 本 tick 因为封顶没留下的拒收格数（只报数，不冒充内容）。 */
        long declaredRejectCellsDropped;
        private static final int DECLARED_REJECT_CELL_LIMIT = 4;

        void noteDeclaredRejectedCell(String cell) {
            if (declaredRejectCells.containsKey(cell)) {
                declaredRejectCells.merge(cell, 1L, Long::sum);
                return;
            }
            if (declaredRejectCells.size() >= DECLARED_REJECT_CELL_LIMIT) {
                declaredRejectCellsDropped++;
                return;
            }
            declaredRejectCells.put(cell, 1L);
        }
        /** 义务交割：批次数、累计交割份数，以及一轮结束时还挂着的最欠账份数。 */
        long obligationBatches;
        long obligationFiringsSum;
        long obligationOwedMax;
        /** 义务交割的网络回补：动了几批、补进内部缓冲的合计枚数（每批每键 ≤ long）。 */
        long obligationRefillBatches;
        long obligationRefillUnits;
        /**
         * 「在分刀推进」与「真没推进」的<b>区分读数</b>：本轮向池子补进几<b>刀</b>（每刀
         * ≤{@code long}）与其<b>真值</b>合计，以及本轮从池子里伸手付出去几刀与真值合计。
         *
         * <p>为什么非要有这一档：一发真需 {@code >long} 时天然要 N 个批次轮次才凑得齐（AE2 的
         * {@code extract} 一轮最多交一刀 {@code long}），前 N−1 轮 {@code obOwed} 一个字不动、
         * {@code obPaid=0}——只看那两行读数就会把"正在累付"读成"停滞"。老那两格 {@code long}
         * 合计读数（{@link #obligationRefillUnits}）既只在<b>交割成功</b>的那一批才前进（半途
         * 归位的回补整笔不计），累加又会饱和在 {@link Long#MAX_VALUE} 上，两个方向都读不出
         * "每轮补进一刀"。这四格走 {@link BigInteger} 的真值与刀数。
         *
         * <p><b>纯读数</b>：这四格不参与任何判据，停滞判定本身一字不改（也不加自动取消/自动终止）。
         * {@code paid*} 记的是<b>伸手</b>付出去的刀数与真值——凑不齐整发会整批归位，所以它只证明
         * "这一轮料在动"，不证明"付成了"；付成了几批仍由 {@link #obligationBatches} 说。
         */
        long refillChunks;
        BigInteger refillExactUnits = BigInteger.ZERO;
        /**
         * 回补这条腿的<b>逐枚键</b>流水（台账 #225 读数 6，纯读数）：这一枚键在本 {@code Probe}
         * 这一 tick 里被网络真交回几枚（{@link BigInteger} 精确值，>long 不饱和）。
         *
         * <p>为什么要有它：{@link #refillChunks}／{@link #refillExactUnits} 交的是<b>聚合</b>那一格
         * ——实机只能读出"这一百拍网络一共回了 12 刀、几亿枚"，说不出"卡住这条 lane 的那一枚键
         * 自己被交回了几枚"。同窗里两张样板、三枚键的回补全糊在同一个数上，"这一枚压根没回"与
         * "这一枚回了但不够"分不开。
         *
         * <p>登记点是 {@link #noteRefillChunk} 那<b>唯一</b>一处：只有 {@code refill.pull} 真交回
         * {@code got>0} 且真进了池子（{@code inventory.insert}）的那一刀才记，SIMULATE 的答复、
         * 被配额夹掉的、{@code ranDry} 之前没搬进任何东西的那一格都不记 ⇒ 这一本不重复记账也不虚增。
         *
         * <p><b>不参与任何判据</b>：与上面两格同一个窗（每 tick 每 lane 一份，由 lane 侧取差值累计），
         * 停滞判定、搬运口径一字未改。
         */
        final Map<AEKey, BigInteger> refillUnitsPerKey = new LinkedHashMap<>();
        long paidChunks;
        BigInteger paidExactUnits = BigInteger.ZERO;

        /**
         * <b>第一道真失败的格</b>（该格算出 {@code executions < 1}）的现场读数：第几格、键、每发件数、
         * 池内真值、{@code quotaExact}、{@code usableFor} 读数、现场商、族商、cap。
         *
         * <p>形状由 {@link #cellReadout} 那一个出口定：格内子字段用不带空白的 {@code ,}、键的第三方
         * 空白折成 {@code "_"}——与 per100r 的 {@code minCell[...]=} <b>逐字同一把尺</b>。
         *
         * <p>停滞行的 {@code blocked=} 点名靠它：份数越出 {@code long} 时，前两格往往全都放行
         * （配额 ÷ 族每发合计远大于它），真失败的那一格排在第三格往后——只印前两格就永远看不见它，
         * 实机 2026-09-26 那一跑就是这个形状（{@code mismatch=0} 而 {@code obPayChunks=0}）。
         *
         * <p><b>纯读数</b>：不参与任何判据，也不新增拒绝分支——写它的那一句就在既有那份
         * {@code executions >= 1} 判据的否分支上。每轮提取只留<b>第一</b>格（见
         * {@link #noteFailingCell}），由 {@link #prepareUniform} 开头清一次。
         */
        String blockedCell = "";

        /**
         * <b>回补这条腿上被挡的格</b>（纯读数）：第几格、哪个候选、交回几枚、被哪一支挡住
         * （候选空表 / {@code cellFirings<1} / 配额夹 / {@code ranDry} / 无限源）。
         *
         * <p>旧形状是"每个 {@code Probe} 只留<b>第一</b>格"（非空即 {@code return}），实机因此把
         * {@code obWhy=} 印成了 {@code slot#1} 那一格，而真卡住这条 lane 的是<b>另一张样板</b>的
         * {@code slot#3}；被点名的那一格自己的 {@code 网络sim=/同族[…]} 读数还给 slot#1 占了位子，
         * 一个字都没印出来。现在按 <b>(样板, 槽)</b> 分桶，每桶各留第一格（后来的候选不覆盖）。
         *
         * <p>封顶 {@link #REFILL_BLOCKED_CELL_LIMIT} 格：{@code Probe} 每 tick 每 lane 新建，
         * 这本桶<b>不跨 tick</b>，也正是这条腿唯一的读数出口（<b>不是第二本账</b>）。装不下的格数记进
         * {@link #refillBlockedCellsDropped}，由 {@link #describeRefillBlockedCells()} 补一格
         * {@code obWhy更多=N}——紧跟在 {@code obWhy=} 后面同一格、不粘在最后一格尾巴上（格内格间都不含
         * 空白，per100r 那把按空白切 token 的尺才切得出独立的那一段）——不把"看到的这几格"冒充全部。
         *
         * <p>与 {@link #blockedCell} 一样<b>不参与任何判据</b>、不新增拒绝分支。
         */
        private final Map<IPatternDetails, List<RefillBlockedCell>> refillBlockedCells
                = new LinkedHashMap<>();
        /** 每 tick 每 lane 每 {@code Probe} 留得下的回补受阻格数上限（纯读数封顶，不是判据）。 */
        private static final int REFILL_BLOCKED_CELL_LIMIT = 4;
        /** 本 tick 已留下的格数与因为封顶没留下的格数（后者只报数，不冒充内容）。 */
        private long refillBlockedCellsKept;
        long refillBlockedCellsDropped;

        /** 一格被挡的形状：样板头、槽号、候选键、交回几枚、<b>真落池几枚</b>、被哪一支挡住，外加 got==0 时拼上的网络读数。 */
        private static final class RefillBlockedCell {
            private final String patternLabel;
            private final int slotIndex;
            @Nullable
            private final AEKey key;
            private final long got;
            /**
             * 这一格<b>真落进池子</b>的枚数（台账 #226 笔 3a，纯读数）：与 {@code got} 同一把尺
             * （{@link ParallelBatchExtraction#exactStored} 那一只宽域读口），量的是
             * {@code inventory.insert} 前后该键池内量的差值。{@code landed < got} 就是
             * "分桶／收不下／键身份"的当场证据——{@code insert} 交回 void，
             * 而累账累的一直是 {@code got}，这一格之前池子真收到几枚从没被测过。
             */
            private final BigInteger landed;
            private final String why;
            private String networkReadout = "";

            private RefillBlockedCell(String patternLabel, int slotIndex, @Nullable AEKey key,
                    String why, long got, BigInteger landed) {
                this.patternLabel = patternLabel;
                this.slotIndex = slotIndex;
                this.key = key;
                this.why = why;
                this.got = got;
                this.landed = landed;
            }

            /**
             * 一格的读数：{@code withPatternLabel} 时带上样板头（{@code obWhy=} 要点名是谁的那一格）。
             *
             * <p>出口处把整串空白一次折成 {@code "_"}（一格一 token）：格自带的五段连接空白、
             * 样板头与 {@code AEItemKey#toString} 带组件时追加的 {@code (with patches)}、同族读数
             * （{@code Probe#describeRefillNetworkReadout}）的三段前导空白，全在这一处折掉——
             * 不给每个来源各加一次折，per100r 那把按空白切 token 的尺才切得出这一格。
             */
            private String describe(boolean withPatternLabel) {
                return ((withPatternLabel ? patternLabel + " " : "")
                        + "slot#" + slotIndex + " " + (key == null ? "<none>" : key)
                        + " got=" + got + " landed=" + landed + " why=" + why
                        + networkReadout).replace(' ', '_');
            }
        }

        /** 记下这一张样板这一格被挡（纯读数）：每个 (样板, 槽) 各留第一格，超出封顶只加 {@code obWhy更多=} 计数（紧跟在 {@code obWhy=} 后面同一格，不粘在最后一格尾巴上）。 */
        void noteRefillBlockedCell(@Nullable IPatternDetails details, int slotIndex,
                @Nullable AEKey key, String why, long got, BigInteger landed) {
            var cells = refillBlockedCells.get(details);
            if (cells != null) {
                for (var cell : cells) {
                    if (cell.slotIndex == slotIndex) {
                        return; // 这一桶已有第一格：后来的候选不覆盖它
                    }
                }
            }
            if (refillBlockedCellsKept >= REFILL_BLOCKED_CELL_LIMIT) {
                refillBlockedCellsDropped++;
                return;
            }
            refillBlockedCells
                    .computeIfAbsent(details, ignored -> new ArrayList<>())
                    .add(new RefillBlockedCell(refillPatternLabel(details), slotIndex, key, why, got,
                            landed));
            refillBlockedCellsKept++;
        }

        /** 被挡那一格的样板头：{@code obWhy=} 要点名是谁的那一格，别再让别张样板的格顶名。 */
        private static String refillPatternLabel(@Nullable IPatternDetails details) {
            if (details == null) {
                return "<样板?>";
            }
            var outputs = details.getOutputs();
            // 样板头走 describeKey：带数据组件的键 toString 是 "<id> (with patches)"，两个空白
            // 会把这一格劈开——编码过的样板必带组件，这里必须单 token（连接空白另由 describe 出口折掉）。
            var head = outputs == null || outputs.isEmpty() || outputs.get(0) == null
                    || outputs.get(0).what() == null ? "?"
                    : ParallelCraftingCpuLogic.describeKey(outputs.get(0).what());
            return "<" + head + " " + details.getClass().getSimpleName() + ">";
        }

        /** 第一格（旧"每个 Probe 只留第一格"那唯一一格的同源读口，形状＝折成一 token 的那一格）。 */
        String refillBlockedCell() {
            for (var cells : refillBlockedCells.values()) {
                if (!cells.isEmpty()) {
                    return cells.get(0).describe(false);
                }
            }
            return "";
        }

        /**
         * {@code obWhy=} 那一格：本 tick 留下的每一格都带上自己的样板头，格间用 {@code /} 连；
         * 印不下的<b>计数 {@code obWhy更多=N} 紧跟在 {@code obWhy=} 后面同一格</b>——不粘在最后一格尾巴上。
         *
         * <p>为什么分隔符与格内都不许带空白：这一串按空白切 token 的下游读数就是 per100r 那一行的读法。
         * 旧形状 {@code <cell1> | <cell2>} 把 {@code " | "} 的两段空白一起漏进切分里，
         * "看到的这一格"被劈成三段；旧尾缀 {@code " obWhy更多=N"} 又把聚合数与"更多"劈成两段，
         * 换成 {@code /} 后粘到最后一格尾巴上（实机形如 {@code why=ranDry/obWhy更多=1}）时，
         * 按空白切反而切不出独立的"更多"段——移到 {@code obWhy=} 紧跟处才两头都不粘连。
         * 格本身的空白由 {@code RefillBlockedCell#describe} 在出口折成 {@code "_"}，整串零空白。
         */
        String describeRefillBlockedCells() {
            var described = new StringBuilder();
            for (var cells : refillBlockedCells.values()) {
                for (var cell : cells) {
                    if (described.length() > 0) {
                        described.append('/');
                    }
                    described.append(cell.describe(true));
                }
            }
            if (refillBlockedCellsDropped > 0L) {
                described.insert(0, "obWhy更多=" + refillBlockedCellsDropped
                        + (described.length() > 0 ? "/" : ""));
            }
            return described.toString();
        }

        /** 这张<b>样板自己</b>被挡的那几格（含它自己的 {@code 网络sim=}／{@code 同族[…]下界}）；没记过即空串。 */
        String refillBlockedCellsFor(@Nullable IPatternDetails details) {
            var cells = details == null ? null : refillBlockedCells.get(details);
            if (cells == null || cells.isEmpty()) {
                return "";
            }
            var described = new StringBuilder();
            for (var cell : cells) {
                if (described.length() > 0) {
                    described.append('/');
                }
                described.append(cell.describe(false));
            }
            return described.toString();
        }

        /**
         * 把网络读数拼进<b>它自己那一格</b>（纯读数）：只认 (这张样板, 这个槽, 这个键) 且
         * {@code got==0} 的那一格，同一格只拼一次。
         *
         * <p>{@code networkReadout} 是惰性的：格子不在位子上就压根不求值——现读要问网络，
         * 每格至多一次，不许进候选循环。
         */
        void noteRefillNetworkReadout(@Nullable IPatternDetails details, int slotIndex, AEKey key,
                Supplier<String> networkReadout) {
            var cells = details == null ? null : refillBlockedCells.get(details);
            if (cells == null) {
                return;
            }
            for (var cell : cells) {
                if (cell.slotIndex == slotIndex && cell.got == 0L
                        && Objects.equals(cell.key, key) && cell.networkReadout.isEmpty()) {
                    cell.networkReadout = networkReadout.get();
                    return;
                }
            }
        }

        /**
         * <b>本批算出的最小份数那一格</b>（纯读数，不参与任何判据）：{@code blockedCell} 只记
         * {@code executions < 1} 的那一格，而"每交互只推进 1 发"那一型钉住份数的那一格
         * {@code executions == 1}——它压根不是失败格，于是永远点不出名（实机
         * {@code lost=1 worst(req=4751999962 got=1 … bind=minecraft:netherrack stored=4751999962
         * demand=1)}：{@code bind} 那格自己算得出 4.75e9 发，钉住 1 的另有其格）。
         *
         * <p>形状与 {@link #blockedCell} 同一把尺（{@link #cellReadout}），末尾多一格
         * {@code 份数=}：取料点算这一格用的那一条算式，原样印出来。
         *
         * <p><b>按 scope 分三格</b>：{@link #noteMinCell} 被三趟取料腿喂（k 倍 holder、义务交割的
         * {@code payableFirings}、单发原型那一趟份数写死 {@code 1L}），旧那"单格取份数最小"于是
         * <b>永远被原型那一趟赢</b>——实机打印 {@code minCell=slot#0 … 现场商=28235520000
         * 族商=28235520000 cap=1 份数=1}，把"批处理上限"印成了 1；同一窗 {@code req=23969280000}
         * 证明 holder 腿拿到 2.4e10 ⇒ {@code cap=1} 是<b>读数伪迹</b>不是缺陷。
         * 现在每 scope 各留最紧一格，{@link #describeMinCell()} 三格一起打，没跑过的那格印 {@code -}。
         * 判据表达式、{@code executions} 算式与 {@code 份数} 的来源一字未动。
         */
        private final String[] minCells = newMinCellCells();
        private final long[] minCellFiringsByScope = newMinCellFirings();

        /**
         * {@code minCell=} 的三趟取料腿：三趟算出的份数<b>不是一把尺</b>，混进同一格就是把搬运上限
         * 或单发原型读成批处理上限。
         */
        enum MinCellScope {
            /** {@link ParallelBatchExtraction#prepareUnitPrototype}：份数写死 1 发的那一趟。 */
            PROTOTYPE("原型"),
            /** {@link ParallelBatchExtraction#prepare}：k 倍 holder 那一趟（宿主真收的批量）。 */
            HOLDER("holder"),
            /** {@link ParallelBatchExtraction#payDeclaredObligation}：义务交割 {@code payableFirings}。 */
            OBLIGATION("义务");

            final String label;

            MinCellScope(String label) {
                this.label = label;
            }
        }

        /** 新开一份"三格都还没跑过"的读数表（跨 100 轮累加也用同一个出口，别让调用方自己造形状）。 */
        static String[] newMinCellCells() {
            var cells = new String[MinCellScope.values().length];
            Arrays.fill(cells, "");
            return cells;
        }

        /** {@link #newMinCellCells()} 对应的份数读数表：{@code -1} 即这一格还没跑过。 */
        static long[] newMinCellFirings() {
            var firings = new long[MinCellScope.values().length];
            Arrays.fill(firings, -1L);
            return firings;
        }

        /**
         * {@link #cellReadout} 那一格内的子字段边界（per100r 的 {@code minCell=} 与停滞行的
         * {@code 真失败格[...]} 共用）：无空白，{@code 每发=/池内=/现场商=} 这些子字段名留着可按
         * {@code ,} 寻址。
         *
         * <p>这里过去还有第二把尺（专给停滞行那一格的空格 join），于是<b>同一格在两行里长成两种
         * 字面</b>：拿 grep 串或 diff 对"同一把尺"的读者只能得到假差异（{@code (with patches)} 对
         * {@code _(with_patches)_}）。两行统一成同一形状后那条分支没有任何调用者，一并删掉。
         */
        private static final String CELL_SEP_COMMA = ",";

        /**
         * 记下"本趟取料腿算出的最小份数那一格"（纯读数）：只在<b>同一 scope</b> 里被更小的份数覆盖，
         * 不新增判据、不改份数算法。
         *
         * @param scope       哪一趟取料腿在记这一格（由三个调用点各传自己的常量）
         * @param cellFirings 取料点为这一格算出的<b>那份份数</b>（同一次 {@code primaryCandidate}
         *                    返回的 {@code executions}，不是另算的数）
         */
        void noteMinCell(MinCellScope scope, int slotIndex, @Nullable AEKey key, BigInteger exactUnits,
                BigInteger exactAvailable, @Nullable PlanAmount quota, long usableReadout,
                BigInteger exactKeyAsk, BigInteger exactFamilyAsk, long cap, long cellFirings) {
            if (cellFirings < 1L) {
                return; // 算不出 1 发的是 blockedCell 那本读数，两格不重叠
            }
            int index = scope.ordinal();
            if (minCellFiringsByScope[index] >= 0L && cellFirings >= minCellFiringsByScope[index]) {
                return; // 只留这一趟里最紧那一格
            }
            minCellFiringsByScope[index] = cellFirings;
            minCells[index] = cellReadout(slotIndex, key, exactUnits, exactAvailable, quota,
                    usableReadout, exactKeyAsk, exactFamilyAsk, cap)
                    + CELL_SEP_COMMA + "份数=" + cellFirings;
        }

        /**
         * 本轮三趟取料腿各自最紧的那一格（一格一 token）：
         * {@code minCell[原型]=…,minCell[holder]=…,minCell[义务]=…}——格间与格内子字段都是 {@code ,}，
         * 第三方键串的空白在 {@link #cellReadout} 那一个出口折成 {@code "_"}。
         */
        String describeMinCell() {
            return describeMinCells(minCells);
        }

        /** 三格一起打（100 轮累加后的那份表也用这一把尺）：没跑过的那格印 {@code -}，不冒充"没有这一趟"。 */
        static String describeMinCells(String[] cells) {
            var described = new StringBuilder();
            for (var scope : MinCellScope.values()) {
                if (described.length() > 0) {
                    // 三趟各一格的边界用 ","——旧形状用空格 join，per100r 按空白切时把 minCell= 这一格
                    // 劈成三段，"minCell[原型]=" 之后紧跟的就不再是格名。
                    described.append(',');
                }
                var cell = cells[scope.ordinal()];
                described.append("minCell[").append(scope.label).append("]=")
                        .append(cell == null || cell.isEmpty() ? "-" : cell);
            }
            // 这里不再折空白：这一格的空白只在 cellReadout 的出口折一次，停滞行走的正是同一个出口——
            // 在这里再折一遍就是"同一把尺两处折"，两行的字面反倒可能各自漂。
            return described.toString();
        }

        /** 把本轮的三格并进 100 轮的累加表（纯读数）：每 scope 各留份数最小的那一格，不跨 scope 比较。 */
        void absorbMinCellsInto(String[] cells, long[] firings) {
            for (var scope : MinCellScope.values()) {
                int index = scope.ordinal();
                if (minCellFiringsByScope[index] >= 0L
                        && (firings[index] < 0L || minCellFiringsByScope[index] < firings[index])) {
                    firings[index] = minCellFiringsByScope[index];
                    cells[index] = minCells[index];
                }
            }
        }

        /**
         * 本轮<b>真被义务回补这条腿算过</b>的那张样板（纯读数，最后一次）：停滞行的
         * {@code 本轮最后算的那张样板=} 印它。
         *
         * <p>它要分开的两型是"这张样板本轮<b>根本没被算</b>（每 tick 批数闸／tick 预算闸把它挡在
         * {@code payDeclaredBatchObligations} 的 {@code continue} 上）"与"算了却交 0"。
         * 只在 {@link #refillObligationBatch} 真开跑时写一次，不跨 tick（{@code Probe} 每 tick 新建）。
         */
        String lastRefillPattern = "";

        void noteRefillComputedPattern(@Nullable IPatternDetails details) {
            if (details == null) {
                return;
            }
            var outputs = details.getOutputs();
            var head = outputs == null || outputs.isEmpty() || outputs.get(0) == null
                    || outputs.get(0).what() == null ? "?" : outputs.get(0).what().toString();
            // 自家子字段边界（class 名与 槽数）用不带空白的 ","；键头里的第三方空白（" (with
            // patches)"）由停滞行 blocked= 那一格的出口折一次，不在这里各折一遍。
            lastRefillPattern = head + "(" + details.getClass().getSimpleName()
                    + ",槽数=" + (details.getInputs() == null ? 0 : details.getInputs().length) + ")"
                    // 实例身份标：这一格与 blocked= 的样板头／src= 要能对上同一张样板实例
                    // （同名两张样板在 equals 键的账里是同一个格，见 ParallelCraftingCpuLogic#patternIdentityTag）。
                    + ParallelCraftingCpuLogic.patternIdentityTag(details);
        }

        /**
         * 一格的现场读数（停滞行 {@code blocked=} 的 {@code 真失败格[...]} 与 per100r 的
         * {@code minCell[...]=} 共用<b>同一份</b>产出）：槽号、键、每发、池内真值、配额、
         * {@code usableFor}、现场商、族商、cap。
         *
         * <p>形状只有一套（32c44e1 那三条口径）：格内子字段一律用不带空白的 {@link #CELL_SEP_COMMA}
         * （于是 {@code grep ',现场商='} 在两行里都寻得到同一个子字段），键的 {@code toString()} 是
         * 第三方串（带组件时含 {@code " (with patches)"}），空白在<b>这一格的出口</b>整串折一次
         * {@code "_"}——与 {@code ParallelCraftingCpuLogic#describeWorstCell} 那一格同一口径，
         * 不给每个来源各写一折。折点放在这里而不是各渲染口，是为了让"同一格在两行里字面相同"这件事
         * 由构造保证：两行的读者拿 grep 串或 diff 对账时不会得到 {@code (with patches)} 对
         * {@code _(with_patches)_} 那种假差异。
         *
         * <p>算式与取值一字未动；{@code "_"} 因此恒等于"别人家的空格"（自家子字段边界已经是 ","）。
         */
        private static String cellReadout(int slotIndex, @Nullable AEKey key, BigInteger exactUnits,
                BigInteger exactAvailable, @Nullable PlanAmount quota, long usableReadout,
                BigInteger exactKeyAsk, BigInteger exactFamilyAsk, long cap) {
            var onSiteQuotient = exactKeyAsk.signum() > 0L
                    ? exactAvailable.divide(exactKeyAsk).toString() : "?";
            var familyQuotient = quota != null && exactFamilyAsk.signum() > 0L
                    ? quota.exactValue().divide(exactFamilyAsk).toString() : "无口径";
            return ("slot#" + slotIndex + CELL_SEP_COMMA + key
                    + CELL_SEP_COMMA + "每发=" + exactUnits
                    + CELL_SEP_COMMA + "池内=" + exactAvailable
                    + CELL_SEP_COMMA + "配额=" + (quota == null ? "无口径" : quota.exactValue().toString())
                    + CELL_SEP_COMMA + "usableFor=" + usableReadout
                    + CELL_SEP_COMMA + "现场商=" + onSiteQuotient
                    + CELL_SEP_COMMA + "族商=" + familyQuotient
                    + CELL_SEP_COMMA + "cap=" + cap).replace(' ', '_');
        }

        /**
         * 一张样板在<b>本轮</b>（这一个 {@code Probe} 的存活期内）的第一道真失败格。
         *
         * <p>带 {@code slotIndex} 与 {@code key} 是为了让停滞行能把"这一格的份数"与点名的那一格
         * 认成<b>同一次提取的同一个数</b>（见 {@link #failingCell}）：只有三样都对得上，读数才同源。
         *
         * @param line           现场一行（同 {@link #blockedCell} 的形状）
         * @param payableFirings 取料点<b>批内 scope</b> 算出的这一格份数（{@code cellPayableFirings}
         *                       的原样返回值）；{@code null} = 这一格没有夹子口径（不夹）
         */
        record FailingCell(int slotIndex, @Nullable AEKey key, String line,
                @Nullable BigInteger payableFirings) {
        }

        /**
         * <b>本轮每张样板自己的</b>第一失败格：{@code prepareUniform} 每 tick 会被叫很多次
         * （一轮里十几张欠账样板各跑一次），而 {@link #blockedCell} 每次开头清一次 ⇒ 单格只留得下
         * <b>最后一次</b>那一张，停滞行点的却是"第一张欠账样板"⇒ 点名张冠李戴。
         *
         * <p>这本表按样板存"该样板本轮首格"，于是无论本轮跑过多少张，点哪张都印得出它自己的那一格。
         * <b>纯读数</b>：随 {@code Probe} 一起每 tick 新建、不落 NBT、不跨 tick、不参与任何判据。
         * 键比的是 {@code equals}（义务账交回的键与任务表上的键可能是两个等值实例）。
         */
        private final Map<IPatternDetails, FailingCell> firstFailingCellByPattern = new HashMap<>();

        /**
         * 记下第一道真失败的格（同一次提取里后来的格子不再覆盖 {@link #blockedCell}；每张样板的
         * 首格记进 {@link #firstFailingCellByPattern}）。
         *
         * <p>两份商都按 {@link #cellPayableFirings} 那<b>同一条</b>算式的分子分母现算现印：
         * 读数与判据不可能漂成两个数（旧形状是"可花=yes 而这一格实际付 0"）。
         *
         * @param payableFirings 取料点算这一格用的那一份<b>批内 scope</b> 份数（同一次
         *                       {@link #cellPayableFirings} 调用的返回值，不是另算的数）
         */
        void noteFailingCell(@Nullable IPatternDetails owner, int slotIndex, @Nullable AEKey key,
                BigInteger exactUnits,
                BigInteger exactAvailable, @Nullable PlanAmount quota, long usableReadout,
                BigInteger exactKeyAsk, BigInteger exactFamilyAsk, long cap,
                @Nullable BigInteger payableFirings
        ) {
            boolean firstOfThisRound = blockedCell.isEmpty();
            boolean firstOfThisPattern = owner != null && !firstFailingCellByPattern.containsKey(owner);
            if (!firstOfThisRound && !firstOfThisPattern) {
                return;
            }
            // 停滞行那一格与 per100r 的 minCell 同一份产出（cellReadout 一把尺：格内 "," 子字段 +
            // 键的第三方空白在格出口折成 "_"）。旧形状那一格用空格 join 且不折，于是"共用同一把尺"
            // 只对算式成立、对字面不成立。
            var line = cellReadout(slotIndex, key, exactUnits, exactAvailable, quota, usableReadout,
                    exactKeyAsk, exactFamilyAsk, cap);
            if (firstOfThisRound) {
                blockedCell = line;
            }
            if (firstOfThisPattern) {
                firstFailingCellByPattern.put(owner,
                        new FailingCell(slotIndex, key, line, payableFirings));
            }
        }

        /**
         * 这张样板<b>本轮</b>自己的第一失败格；它本轮没记过就是 {@code null}。
         *
         * <p>停滞行点名只认这一道口：拿 {@link #blockedCell} 点"<b>本轮最后一次</b>提取的那一格"
         * 再配"<b>第一张</b>欠账样板"就是张冠李戴（同轮 ≥2 张欠账样板时几乎必错）。
         */
        @Nullable
        FailingCell failingCell(@Nullable IPatternDetails owner) {
            return owner == null ? null : firstFailingCellByPattern.get(owner);
        }

        /** 本次提取的请求上下文，由调用方在 prepare 前写入（同 tick 同线程，单lane独占）。 */
        private long ctxRequested;
        private long ctxPwr = Long.MAX_VALUE;
        private long ctxCap;
        private String ctxCls = "";

        /** 钉住最终份数的那个槽，由 prepareUniform 在本次调用内写入。 */
        private long lastBindStored;
        private long lastBindDemand;
        private AEKey lastBindKey;

        void noteRequestContext(long requested, long powerCeiling, long hostCap,
                @Nullable IPatternDetails details) {
            ctxRequested = requested;
            ctxPwr = powerCeiling;
            ctxCap = hostCap;
            ctxCls = details == null ? "?" : details.getClass().getSimpleName();
        }

        private void noteLoss(long got) {
            if (ctxRequested <= got) {
                return;
            }
            lostCount++;
            long lost = ctxRequested - got;
            if (lost <= lostFirings) {
                return;
            }
            lostFirings = lost;
            lostReq = ctxRequested;
            lostGot = got;
            lostPwr = ctxPwr == Long.MAX_VALUE ? -1L : ctxPwr;
            lostCap = ctxCap;
            lostCls = ctxCls;
            lostBindKey = lastBindKey == null ? (got > 1L ? "<requested>" : "<none>")
                    : lastBindKey.toString();
            lostBindStored = lastBindStored;
            lostBindDemand = lastBindDemand;
        }

        private void resetBinding() {
            lastBindKey = null;
            lastBindStored = 0L;
            lastBindDemand = 0L;
        }

        /**
         * 最坏配对的判读串：直接指出份数是被现场料钉住的。
         *
         * <p>子字段一律用 {@code ,} 连（不带空白）：这一串被 {@code ParallelCraftingCpuLogic} 拼进
         * per100r 的 {@code worst(...)} 那一格，格内的空白会把那一格劈成好几段。
         * {@code lostBindKey} 是 {@code AEKey#toString()}（第三方串，带组件时含空白），
         * 由 {@code worst(...)} 那一格的<b>出口</b>统一折成 {@code "_"}——不在这里折第二次。
         */
        String describeWorstBinding() {
            return "bind=" + lostBindKey + ",stored=" + lostBindStored + ",demand=" + lostBindDemand;
        }
    }

    @Nullable
    /** 不关心批量决策明细的调用方：探针传 null。 */
    static Batch prepare(
            IPatternDetails details,
            ListCraftingInventory inventory,
            Level level,
            long requestedExecutions,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            @Nullable ParallelPlanReservation reservation
    ) {
        return prepare(details, inventory, level, requestedExecutions,
                expectedOutputs, expectedContainerItems, reservation, null);
    }

    static Batch prepare(
            IPatternDetails details,
            ListCraftingInventory inventory,
            Level level,
            long requestedExecutions,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            @Nullable ParallelPlanReservation reservation,
            @Nullable Probe probe
    ) {
        if (requestedExecutions <= 0L || details.getInputs() == null || details.getInputs().length == 0) {
            return null;
        }
        // 份数先夹到"每发量 × 份数仍留在 long 内"，再谈提取：产出、回流、holder 都是
        // AE2 的 long 计数表，乘法饱和成 Long.MAX 就是交出一个与份数对不上的假数。
        // 夹掉的部分照旧走 noteLoss，探针里表现为 req>got 且 bind=<requested>。
        long requestable = clampToRepresentablePerFiringAmounts(details, requestedExecutions);
        var remainders = new KeyCounter();
        if (probe != null) {
            probe.resetBinding();
        }
        Batch batch = prepareUniform(
                details, inventory, level, requestable, remainders, reservation, probe, false,
                Probe.MinCellScope.HOLDER);
        if (batch == null) {
            if (probe != null) {
                probe.uniformFallback++;
            }
            remainders.reset();
            batch = prepareSingleFiring(details, inventory, level, remainders, reservation);
            if (batch != null && probe != null) {
                probe.noteLoss(1L);
            }
        } else if (probe != null) {
            probe.uniformOk++;
            probe.execSum += batch.executions();
            probe.noteLoss(batch.executions());
        }
        if (batch == null) {
            return null;
        }
        for (var remainder : remainders) {
            expectedContainerItems.add(remainder.getKey(), remainder.getLongValue());
        }
        for (var output : details.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                expectedOutputs.add(output.what(), multiplyOrMax(output.amount(), batch.executions()));
            }
        }
        return batch;
    }

    /**
     * 每槽一个候选、每发组成相同的 k 发批量；凑不满一发时返回 null。
     *
     * @param widePayment 这一批<b>不交给宿主从 holder 反推份数</b>（声明形态的单发原型、义务交割）
     *                    时才置 {@code true}：那一格的<b>真值</b>越出 {@code long} 就分几刀从池子里
     *                    付，凑不齐整批发就不付。窄域路径（k 倍 holder）交 {@code false}，
     *                    口径与本刀之前逐字相同——那份 holder 的读数就是宿主算份数的依据，
     *                    拿一个钉在天花板上的数去充"一整发"是假通过。
     * @param minCellScope 这一趟取料腿的名字（{@code 原型} / {@code holder} / {@code 义务}）：
     *                     只决定 {@code minCell=} 读数<b>记进哪一格</b>，三趟各留自己最紧的那一格，
     *                     不参与任何判据、不改份数算式
     */
    @Nullable
    private static Batch prepareUniform(
            IPatternDetails details,
            ListCraftingInventory inventory,
            Level level,
            long requestedExecutions,
            KeyCounter expectedContainerItems,
            @Nullable ParallelPlanReservation reservation,
            @Nullable Probe probe,
            boolean widePayment,
            Probe.MinCellScope minCellScope
    ) {
        var inputs = details.getInputs();
        // 某个键"每发合计吃几件"：跨槽同键要累加，否则第二槽按第一槽那次的份数
        // 被整笔记账，本来撑得住的批量会被误判成 0 发。
        var demandPerFiring = new HashMap<AEKey, Long>();
        // 同一条线性约束的宽域版本：只在 {@code widePayment} 下参与除法（饱和值不当量用）。
        var exactDemandPerFiring = new HashMap<AEKey, BigInteger>();
        // 族配额是"整族一份"：本批里同一物品本体族上每发合计吃几枚，决定这一批最多几发。
        // 这一份合计走<b>宽域</b>：它下一刻要当分母去除族配额（{@link #executionsInsideFamilyQuota}），
        // 而分子那份族配额同样可能顶在天花板上——两个 {@code long} 哨兵相除得到的"1 发"既可能把
        // 付得起的两发夹成一发，也可能把付不起的一发放行成两发。
        var familyDemandPerFiring = new HashMap<Object, PlanAmount>();
        var chosen = new Candidate[inputs.length];
        long executions = requestedExecutions;
        if (probe != null) {
            // 一次提取一轮账：本轮的"第一格"只留本轮那一张样板的（别把上一张的那一格串过来）；
            // 按样板存的那本表不清——它要能回答"本轮里<b>这一张</b>的首格"，跨这一轮的多次提取。
            probe.blockedCell = "";
        }
        for (int slot = 0; slot < inputs.length; slot++) {
            var input = inputs[slot];
            if (input == null || input.getMultiplier() <= 0L) {
                return null;
            }
            var choice = primaryCandidate(
                    input, inventory, level, demandPerFiring, exactDemandPerFiring,
                    familyDemandPerFiring, executions,
                    reservation, details, requestedExecutions, probe, widePayment, slot,
                    minCellScope);
            if (choice == null || choice.executions() <= 0L) {
                if (probe != null) {
                    probe.noCandidate++;
                    // 只说"挑不出候选"定不了因：现场没料、被配给夹到 0、还是压根没有配额口径，
                    // 三条修法完全不同。把这一格候选的现场量与配额读数一起留下。
                    probe.noteNoCandidate(slot, input, inventory, level, details, reservation);
                }
                return null;
            }
            executions = Math.min(executions, choice.executions());
            // 无限格不是钉住份数的那一格（它交得出任意多发），别把它的读数报成 binding
            if (choice.executions() <= executions && !choice.unboundedSource() && probe != null) {
                probe.lastBindKey = choice.key();
                probe.lastBindStored = choice.stored();
                probe.lastBindDemand = choice.demandPerExecution();
            }
            chosen[slot] = choice;
            demandPerFiring.merge(choice.key(), choice.unitsPerExecution(), ParallelBatchExtraction::addOrMax);
            exactDemandPerFiring.merge(choice.key(), choice.exactUnitsPerFiring(), BigInteger::add);
            // "有没有配额口径"与封顶同一条判据（quotaExact 交 null 即不夹）：两侧读的是同一格
            // 豁免，走两道口径就会分叉。分母按<b>真值</b>累加，见上面那条注释。
            if (reservation != null
                    && reservation.quotaExact(choice.key(), details, input) != null) {
                familyDemandPerFiring.merge(choice.key().getPrimaryKey(),
                        PlanAmount.of(choice.exactUnitsPerFiring()), PlanAmount::add);
            }
        }

        var holder = new KeyCounter[inputs.length];
        // 真越出 long 的那几格在这里另记一份真值；null 即每格都装得进 long。
        BigInteger[] exactPaidUnits = null;
        // 就地认量的那几个键（无限源）：回滚出口只认这份账，不在那里再判一次 isUnboundedSupply。
        WideKeyCounter[] mintedUnits = null;
        for (int slot = 0; slot < inputs.length; slot++) {
            var choice = chosen[slot];
            BigInteger exactNeed = choice.exactUnitsPerFiring().multiply(BigInteger.valueOf(executions));
            holder[slot] = new KeyCounter();
            long items = multiplyOrMax(choice.unitsPerExecution(), executions);
            // 两者不等即这一发的真值装不进一个 KeyCounter 格子：items 那份是钉在天花板上的读数，
            // exactNeed 才是真值。
            boolean beyondHolder = items <= 0L || exactNeed.compareTo(BigInteger.valueOf(items)) != 0;
            if (choice.unboundedSource()) {
                // 无限源不经池子：既不 {@code extract}（池子里这一枚本就没有，抽它就是把 0 当付款），
                // 也不 {@code insert}（料一进池就被 {@code storeItems()} 当残池投回网络 = 凭空多一份，
                // 本仓已因此回退过一次）。
                if (beyondHolder && !widePayment) {
                    // 窄域路径交不出与份数对得上的读数：整批归位，凑不齐照实报（不在这里偷偷放行）
                    if (probe != null) {
                        probe.takenMismatch++;
                    }
                    reinjectTaken(inventory, holder, exactPaidUnits, mintedUnits);
                    return null;
                }
                if (mintedUnits == null) {
                    mintedUnits = new WideKeyCounter[inputs.length];
                }
                (mintedUnits[slot] = new WideKeyCounter()).add(choice.key(), exactNeed);
                if (beyondHolder) {
                    if (exactPaidUnits == null) {
                        exactPaidUnits = new BigInteger[inputs.length];
                    }
                    exactPaidUnits[slot] = exactNeed;
                    holder[slot].add(choice.key(), saturate(exactNeed));
                } else {
                    holder[slot].add(choice.key(), items);
                }
            } else if (beyondHolder && !widePayment) {
                // 窄域路径：这份 holder 就是宿主算份数的依据，真值越出 long 即这一发压根
                // 装不进 KeyCounter —— 交一个钉在天花板上的读数过去是把假数当了量。
                // 整批归位，缺料照实由调用方的回补与退避出口报出去（>long 的一发走声明形态）。
                if (probe != null) {
                    probe.takenMismatch++;
                }
                reinjectTaken(inventory, holder, exactPaidUnits, mintedUnits);
                return null;
            } else if (beyondHolder) {
                // 这一格一刀付不完：分几刀从（宽域）池子里累付，凑不齐就整批归位。
                BigInteger paid = payInChunks(inventory, holder[slot], choice.key(), exactNeed, probe);
                if (exactPaidUnits == null) {
                    exactPaidUnits = new BigInteger[inputs.length];
                }
                exactPaidUnits[slot] = paid;
                if (paid.compareTo(exactNeed) != 0) {
                    if (probe != null) {
                        probe.takenMismatch++;
                    }
                    reinjectTaken(inventory, holder, exactPaidUnits, mintedUnits);
                    return null;
                }
            } else {
                long taken = inventory.extract(choice.key(), items, Actionable.MODULATE);
                holder[slot].add(choice.key(), taken);
                if (taken != items) {
                    if (probe != null) {
                        probe.takenMismatch++;
                    }
                    // 已经真扣掉的 taken 个要先归位，否则这一支退回就把它丢在池外。
                    reinjectTaken(inventory, holder, exactPaidUnits, mintedUnits);
                    return null;
                }
            }
            AEKey remaining = inputs[slot].getRemainingKey(choice.key());
            if (remaining != null) {
                expectedContainerItems.add(remaining,
                        multiplyOrMax(inputs[slot].getMultiplier(), executions));
            }
        }
        return new Batch(holder, executions, exactPaidUnits == null
                ? null : toExactPaidHolder(holder, exactPaidUnits), mintedUnits);
    }

    /**
     * 把这一格的<b>真值</b>从池子里分几刀付出去：每刀 ≤{@code long}（AE2 的 {@code extract}
     * 只能吃 {@code long}），而池子是宽域的（{@link WideCraftingInventory}），装得下真值也就给得出真值。
     *
     * <p>凑不齐 {@code exactNeed} 时返回已付出去的那一截（可能为 0），由调用方整批归位——
     * <b>半份料不算一发</b>，绝不交出一个"看着像一发"的批量。
     */
    private static BigInteger payInChunks(ListCraftingInventory inventory, KeyCounter slotHolder,
            AEKey key, BigInteger exactNeed, @Nullable Probe probe) {
        if (inventory instanceof WideCraftingInventory wide) {
            BigInteger paid = wide.extractExact(resolvePoolKey(inventory, key), exactNeed, Actionable.MODULATE);
            slotHolder.set(key, saturate(paid));
            if (probe != null && paid.signum() > 0) {
                probe.paidChunks++;
                probe.paidExactUnits = probe.paidExactUnits.add(paid);
            }
            return paid;
        }
        BigInteger left = exactNeed;
        BigInteger paid = BigInteger.ZERO;
        while (left.signum() > 0) {
            long cut = left.min(WideCeilings.LONG_ACCOUNT).longValue();
            long taken = inventory.extract(key, cut, Actionable.MODULATE);
            if (taken <= 0L) {
                break;
            }
            BigInteger takenExact = BigInteger.valueOf(taken);
            paid = paid.add(takenExact);
            left = left.subtract(takenExact);
            // 记一刀（纯读数）：凑不齐整发时这一截会整批归位，所以它只说明"本批料在动"。
            if (probe != null) {
                probe.paidChunks++;
                probe.paidExactUnits = probe.paidExactUnits.add(takenExact);
            }
        }
        // holder 只能装 long：这里写的是长读数（真值越界即钉在天花板），只给宿主与诊断看。
        slotHolder.set(key, paid.min(WideCeilings.LONG_ACCOUNT).longValue());
        return paid;
    }

    /** 真值与 holder 的长读数不一致的那几格折成宽域账；其余格留 {@code null}（holder 即真值）。 */
    @Nullable
    private static WideKeyCounter[] toExactPaidHolder(KeyCounter[] holder, BigInteger[] exactPaidUnits) {
        WideKeyCounter[] wide = null;
        for (int slot = 0; slot < exactPaidUnits.length; slot++) {
            BigInteger exact = exactPaidUnits[slot];
            if (exact == null || exact.bitLength() <= 63 || holder[slot] == null) {
                continue;
            }
            for (var entry : holder[slot]) {
                if (entry.getKey() == null) {
                    continue;
                }
                if (wide == null) {
                    wide = new WideKeyCounter[holder.length];
                }
                var slotCounter = wide[slot] == null ? new WideKeyCounter() : wide[slot];
                slotCounter.setExact(entry.getKey(), exact);
                wide[slot] = slotCounter;
            }
        }
        return wide;
    }

    /**
     * 整批归位：分过刀的那几格按<b>真值</b>回池（holder 上的长读数可能比真值少一整刀），
     * 其余格照 holder 的读数原路放回。
     *
     * <p>每一格再按 {@code mintedUnits} 扣掉"就地认量的那几个键"：那些枚数从未离开池子，
     * 塞回去就是凭空多出一份（下一刻 {@code storeItems()} 把它当残池投回网络）。扣的是<b>键</b>
     * 不是<b>格</b>——同一格里"真抽走的有限候选 + 就地认量的无限候选"混着付是常态（单发混取那条腿），
     * 按格跳过会把真料永久丢在池外。
     */
    private static void reinjectTaken(ListCraftingInventory inventory, KeyCounter[] holder,
            @Nullable BigInteger[] exactPaidUnits, @Nullable WideKeyCounter[] mintedUnits
    ) {
        for (int slot = 0; slot < holder.length; slot++) {
            var held = holder[slot];
            if (held == null) {
                continue;
            }
            var minted = mintedUnits == null || slot >= mintedUnits.length
                    ? null : mintedUnits[slot];
            BigInteger exact = exactPaidUnits == null || slot >= exactPaidUnits.length
                    ? null : exactPaidUnits[slot];
            for (var entry : held) {
                AEKey key = entry.getKey();
                if (key == null) {
                    continue;
                }
                // 分过刀的那一格只有一个键（每槽一个候选），真值整个记在它头上。
                BigInteger owed = exact != null && exact.signum() > 0L
                        ? exact : BigInteger.valueOf(entry.getLongValue());
                if (minted != null) {
                    owed = owed.subtract(minted.getExact(key));
                }
                if (owed.signum() > 0L) {
                    restore(inventory, key, owed);
                }
            }
        }
    }

    /**
     * 还回真值：宽域库存一次写回，生产池的 insertExact 覆写同时登记进账。
     * 普通库存按 long 分段还回。
     */
    private static void restore(ListCraftingInventory inventory, AEKey key, BigInteger amount) {
        if (inventory instanceof WideCraftingInventory wide) {
            wide.insertExact(key, amount, Actionable.MODULATE);
            return;
        }
        BigInteger left = amount;
        while (left.signum() > 0L) {
            long chunk = left.min(WideCeilings.LONG_ACCOUNT).longValue();
            inventory.insert(key, chunk, Actionable.MODULATE);
            left = left.subtract(BigInteger.valueOf(chunk));
        }
    }

    /**
     * 推送/交割那两条形态的整批归位出口：holder 是宿主看得见的长读数，真值在
     * {@link Batch#exactPaid()} 里，退回池子时必须按真值还；就地认量的那几个键
     * （{@link Batch#mintedUnits()}）一枚都不许还——它们本就不在池子里。
     */
    static void reinjectInputs(ListCraftingInventory inventory, @Nullable Batch batch) {
        if (inventory == null || batch == null || batch.holder() == null) {
            return;
        }
        var holder = batch.holder();
        var exactPaid = batch.exactPaid();
        BigInteger[] exactUnits = null;
        if (exactPaid != null) {
            exactUnits = new BigInteger[holder.length];
            for (int slot = 0; slot < exactPaid.length && slot < exactUnits.length; slot++) {
                WideKeyCounter counter = exactPaid[slot];
                if (counter == null || counter.isEmpty()) {
                    continue;
                }
                exactUnits[slot] = counter.total().exactValue();
            }
        }
        reinjectTaken(inventory, holder, exactUnits, batch.mintedUnits());
    }

    /** AE2 原生的逐槽混取（单发）；同样不得吃掉别人唯一能用的那份现场料。 */
    @Nullable
    private static Batch prepareSingleFiring(
            IPatternDetails details,
            ListCraftingInventory inventory,
            @Nullable Level level,
            KeyCounter expectedContainerItems,
            @Nullable ParallelPlanReservation reservation
    ) {
        var inputs = details.getInputs();
        var holder = new KeyCounter[inputs.length];
        // 就地认量的那几个键（无限源）：整批归位时按键相减，见 {@link #reinjectTaken}
        WideKeyCounter[] mintedUnits = null;
        // 一格混用多个候选时族配额也只有一份：前面候选吃掉的枚数要先从份额里减掉，
        // 否则同族两个变体各按"整份剩余配额"放行，定数就被吃穿。
        // 这一份"已吃"走宽域：它是那道商法的<b>减数</b>，钉在天花板上就等于把还能付的那一截
        // 判成 0（与 {@link #templatesInsideFamilyQuota} 同一条口径）。
        var familySpent = new HashMap<Object, PlanAmount>();
        for (int slot = 0; slot < inputs.length; slot++) {
            var input = inputs[slot];
            if (input == null || input.getMultiplier() <= 0L) {
                reinjectTaken(inventory, holder, null, mintedUnits);
                return null;
            }
            holder[slot] = new KeyCounter();
            var templates = usableTemplates(inventory, input, level, reservation);
            boolean fallbackOnSite = countDistinctKeys(templates) > 1;
            long remainingUnits = input.getMultiplier();
            for (InputTemplate template : templates) {
                if (remainingUnits <= 0L) {
                    break;
                }
                if (reservation != null && reservation.isUnboundedSupply(template.key())) {
                    // 无限源不经池子：这一格还差的件数就地认量，不向 {@code extractTemplates} 伸手
                    // （池子里那一枚本就没有）。真值装不进 holder 时退到下一个候选——
                    // >long 的一发由声明形态那条路负责。
                    BigInteger exactMinted = exactUnits(remainingUnits, template.amount());
                    if (exactMinted.bitLength() > 63) {
                        continue;
                    }
                    holder[slot].add(template.key(), exactMinted.longValue());
                    if (mintedUnits == null) {
                        mintedUnits = new WideKeyCounter[inputs.length];
                    }
                    if (mintedUnits[slot] == null) {
                        mintedUnits[slot] = new WideKeyCounter();
                    }
                    mintedUnits[slot].add(template.key(), exactMinted);
                    AEKey mintedRemaining = input.getRemainingKey(template.key());
                    if (mintedRemaining != null) {
                        expectedContainerItems.add(mintedRemaining, remainingUnits);
                    }
                    remainingUnits = 0L;
                    break;
                }
                long allowed = remainingUnits;
                if (reservation != null && template.key() != null && template.amount() > 0L) {
                    long stored = inventory.extract(template.key(), Long.MAX_VALUE,
                            Actionable.SIMULATE);
                    allowed = Math.min(allowed, reservation.usableFor(template.key(), stored,
                            details, 1L, input, fallbackOnSite) / template.amount());
                    allowed = Math.min(allowed, templatesInsideFamilyQuota(reservation, details,
                            input, template, familySpent));
                }
                if (allowed <= 0L) {
                    continue;
                }
                // AE2 的 extractTemplates 第一步就算「模板量 × 份数」：一发真需越出 long 时
                // 那个乘积直接回绕成负数（SIMULATE 到 0、整格归位），下面 extracted × amount
                // 同样会绕进 holder。混取这一格只按还能精确表示的那份数伸手，
                // >long 的一发由声明形态那条路分几刀累付。
                if (template.amount() > 0L && allowed > Long.MAX_VALUE / template.amount()) {
                    allowed = Long.MAX_VALUE / template.amount();
                }
                if (allowed <= 0L) {
                    continue;
                }
                long extracted = CraftingCpuHelper.extractTemplates(inventory, template, allowed);
                if (extracted <= 0L) {
                    continue;
                }
                holder[slot].add(template.key(), extracted * template.amount());
                if (reservation != null
                        && reservation.quotaExact(template.key(), details, input) != null) {
                    familySpent.merge(template.key().getPrimaryKey(),
                            PlanAmount.of(extracted).multiply(PlanAmount.of(template.amount())),
                            PlanAmount::add);
                }
                AEKey remainingKey = input.getRemainingKey(template.key());
                if (remainingKey != null) {
                    expectedContainerItems.add(remainingKey, extracted);
                }
                remainingUnits -= extracted;
            }
            if (remainingUnits > 0L) {
                // 连本槽已经扣掉的半截一起归位：这一格之外的 null 由助手跳过。
                // 就地认量的那几个键按 mintedUnits 相减后不在归还名单里——它们从未离开池子。
                reinjectTaken(inventory, holder, null, mintedUnits);
                return null;
            }
        }
        return new Batch(holder, 1L, null, mintedUnits);
    }

    /**
     * @param exactUnitsPerFiring 这一格<b>真</b>需几枚（倍率 × 模板量，宽域）：越出 {@code long} 时
     *                            {@code unitsPerExecution} 只是钉在天花板上的哨兵读数，
     *                            除法与减法一律用它
     * @param unboundedSource 这一格付自<b>无限源</b>：付款时就地认量（不碰池子），回滚时也必须
     *                        跳过它——那些枚数从未离开池子。这个标记就是回滚出口认的那一条信息，
     *                        不在那里再判一次 {@code isUnboundedSupply}
     */
    private record Candidate(AEKey key, long unitsPerExecution, long executions,
            long stored, long demandPerExecution, BigInteger exactUnitsPerFiring,
            boolean unboundedSource) {
    }

    // ==================================================================================
    // 份数声明形态（DeclaredBatchHost 路径）：宿主只收"单发原型材料 + 份数声明"，
    // 一次交互推进几发不再由"k 倍料装不装得进一个 KeyCounter"决定。
    // ==================================================================================

    /**
     * 单发原型：一次交互推 k 发时先按 {@link #prepareUniform} 的<b>同一条</b>顺位与族配额夹
     * 提出<b>一发</b>所需材料。
     *
     * <p>不另起一份顺位账——把请求份数写成 1 就是把 prepareUniform 整条判据原样复用：
     * 同一套 {@link #primaryCandidate} 候选顺位、同一个 {@link ParallelPlanReservation} 配额夹、
     * 同一套"凑不满一发就整批归位"的退料口径。
     *
     * @param expectedContainerItems 本方法只写进<b>一发</b>的回流容器量；声明形态要按实收份数
     *                               记账，调用方传一份临时表并改用
     *                               {@link #addDeclaredReserves} 出账
     */
    @Nullable
    static Batch prepareUnitPrototype(
            IPatternDetails details,
            ListCraftingInventory inventory,
            Level level,
            KeyCounter expectedContainerItems,
            @Nullable ParallelPlanReservation reservation,
            @Nullable Probe probe
    ) {
        if (details.getInputs() == null || details.getInputs().length == 0) {
            return null;
        }
        if (probe != null) {
            probe.resetBinding();
        }
        return prepareUniform(details, inventory, level, 1L, expectedContainerItems,
                reservation, probe, true, Probe.MinCellScope.PROTOTYPE);
    }

    /** 窄域宿主的产出与容器预期；宽域声明批次使用 BigInteger 重载。 */
    static void addDeclaredReserves(IPatternDetails details, @Nullable KeyCounter[] unitPrototype,
            long declaredExecutions, KeyCounter expectedOutputs, KeyCounter expectedContainerItems) {
        if (declaredExecutions <= 0L) {
            return;
        }
        for (var output : details.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                expectedOutputs.add(output.what(),
                        multiplyOrMax(output.amount(), declaredExecutions));
            }
        }
        var inputs = details.getInputs();
        if (unitPrototype == null || inputs == null) {
            return;
        }
        for (int slot = 0; slot < unitPrototype.length; slot++) {
            var held = unitPrototype[slot];
            if (held == null || slot >= inputs.length || inputs[slot] == null) {
                continue;
            }
            for (var entry : held) {
                if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                    continue;
                }
                AEKey remaining = inputs[slot].getRemainingKey(entry.getKey());
                if (remaining != null) {
                    expectedContainerItems.add(remaining,
                            multiplyOrMax(inputs[slot].getMultiplier(), declaredExecutions));
                }
            }
        }
    }

    /** 声明批次逐键精确预留；容器数与原型槽倍率同源，不截断整批。 */
    static void addDeclaredReserves(IPatternDetails details, @Nullable KeyCounter[] unitPrototype,
            BigInteger executions, WideKeyCounter outputs, WideKeyCounter containers) {
        for (var output : details.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                outputs.add(output.what(), executions.multiply(BigInteger.valueOf(output.amount())));
            }
        }
        var inputs = details.getInputs();
        if (unitPrototype == null || inputs == null) {
            return;
        }
        for (int slot = 0; slot < unitPrototype.length && slot < inputs.length; slot++) {
            var held = unitPrototype[slot];
            if (held == null || inputs[slot] == null) {
                continue;
            }
            for (var entry : held) {
                if (entry.getKey() != null && entry.getLongValue() > 0L) {
                    AEKey remaining = inputs[slot].getRemainingKey(entry.getKey());
                    if (remaining != null) {
                        containers.add(remaining, executions.multiply(BigInteger.valueOf(inputs[slot].getMultiplier())));
                    }
                }
            }
        }
    }

    /**
     * 义务交割的一批：把 {@code payableFirings} 发所需材料从池子里提走，交回 holder 供销账。
     *
     * <p>提料走 {@link #prepareUniform} 本体——顺位、族配额、"取不足即整批归位"全部同一条口径，
     * 于是"分批销账的合计"与"同一 (details, k, 存量) 下 k 倍 holder 路径的提料合计"在可表示
     * 区间内逐键相等。回流容器写进临时表后丢弃：这份账在推送那一刻已经按实收份数挂过。
     *
     * <p>配给表是<b>编译期非空</b>的：生产侧唯一调用方（{@code ParallelCraftingCpuLogic}
     * #payObligationBatch）拿的是每 tick 现建的那一份，{@code ParallelPlanReservation#build}
     * 永不返回 null。允许 null 就等于允许"没有配额封顶的交割"那第二条路。
     */
    @Nullable
    static Batch payDeclaredObligation(
            IPatternDetails details,
            ListCraftingInventory inventory,
            Level level,
            long payableFirings,
            ParallelPlanReservation reservation,
            @Nullable Probe probe
    ) {
        if (payableFirings <= 0L || details.getInputs() == null || details.getInputs().length == 0) {
            return null;
        }
        if (probe != null) {
            probe.resetBinding();
        }
        return prepareUniform(details, inventory, level, payableFirings,
                new KeyCounter(), reservation, probe, true, Probe.MinCellScope.OBLIGATION);
    }

    /**
     * 义务交割的网络回补通道：把这一批缺的材料搬进内部缓冲。
     *
     * <p>它<b>只搬料、不记账</b>：搬进来的料落在 {@code ListCraftingInventory} 里，仍由
     * {@link #payDeclaredObligation} 伸手取，销账仍只有 {@code recordTakenInputs} 那一个出口。
     */
    interface ObligationRefill {
        /** 从网络抽至多 {@code wanted} 枚 {@code what}：返回真抽到的枚数（不足即给多少搬多少）。 */
        long pull(@Nullable AEKey what, long wanted);

        /**
         * 网络对同一笔请求<b>自报</b>能交出多少（SIMULATE）。
         *
         * <p>默认交回 0，即"这条通道不会探询"——只有真通道（{@code networkRefillChannel}）
         * 会给出答案，测试与旧调用方逐字不变。
         */
        default long probe(@Nullable AEKey what, long wanted) {
            return 0L;
        }

        /**
         * <b>同物品本体族</b>在网络上的逐精确键读数（<b>纯读数</b>，不参与任何判据）：交回与
         * {@code sample} 同一 {@code getPrimaryKey()}（且同一 {@code AEKeyType}）的精确键，
         * 至多 {@link #NETWORK_FAMILY_READOUT_LIMIT} 个。
         *
         * <p>默认交回空表：这条通道只会问"网络上有没有别的精确键"，量仍由现成的
         * {@link #probe} 逐键问一次（不新开第二条网络通道）。离线用例与旧调用方逐字不变。
         *
         * <p>实现侧<b>不许</b>交回 AE2 聚合报量当供给：{@code NetworkStorage#getAvailableStacks}
         * 把每个 mount 的报量经 fastutil {@code addTo}（普通 long 加法、回绕不饱和）累进同一张表，
         * 那个数既不是上界也不是下界；这里只取聚合表的<b>键集</b>，量是一次 {@code extract(SIMULATE)}
         * 的读数，只能报成下界（见 {@code ParallelCraftingCpuLogic#networkRefillChannel}）。
         */
        default java.util.List<AEKey> sameFamilyKeys(@Nullable AEKey sample) {
            return java.util.List.of();
        }

        /** 就地认下这一键为无限源（见 {@code ParallelExecutingCraftingJob#noteUnboundedSupply}）。 */
        default void noteUnboundedSupply(@Nullable AEKey what) {
        }

        /**
         * 本轮还剩不剩墙钟预算：同一格连喂多刀时每刀前问一次，没了就收手、剩下的下一轮凑。
         *
         * <p>默认 {@code true}（离线用例与旧调用方逐字不变）；生产侧交的是 tick 预算读数——
         * 没有这道闸，一次回补就能把整池抽满，把 MSPT 保护让给搬运本身。
         */
        default boolean hasTimeBudget() {
            return true;
        }
    }

    /**
     * 交割一批前把内部缓冲补齐：<b>内部缓冲优先</b>，缺口按样板自己的候选顺位从网络回补，
     * 每批每键 ≤ {@code long}（份数由 {@link #obligationBatchFirings} 夹过）。
     *
     * <p>顺位不另起一套账：这里只读 {@code getPossibleInputs()} 的原始顺序 +
     * {@code isValid} 过滤，正是 {@link #usableTemplates} 的两半里<b>不依赖池子</b>的那一半
     * （另一半"池子里落得下的键"由 {@link #primaryCandidate} 判）。要回补的正是这一截池子里
     * 看不见的供给，所以补进来之后仍由 {@link #payDeclaredObligation} 按同一条顺位决定吃哪个键几枚。
     *
     * <p>每次抽取被三道夹子夹住，保证不把别的任务那份口粮搬空：只补<b>这一键本批</b>真需的枚数
     * （由欠账驱动，多一枚都不要；分子分母同一个 scope——整张样板对该键的每发合计 × 本批份数）、
     * 只补到<b>这张样板在该族上还没销完的配给</b>
     * （{@link ParallelPlanReservation#quotaExact}，与取料侧同一道封顶；没有配额口径即不夹。
     * 这一道夹子与池子的族现量在<b>宽域</b>做差，只在夹完的那一个数上窄化回 {@code long}），
     * 且一次不超过网络当场给得出的量。配给已尽的候选一律不补——那格该改道，
     * 补进一个取不走它的槽只是白搬。
     *
     * <p>第四道<b>不是新闸口</b>，是同一把环种留底尺长在这一条腿上
     * （{@link ParallelPlanReservation#spendableAfterRingSeedReserve}，与取料点、折发数两条腿
     * 同一个 {@code owed}）：这一趟只搬"搬进池子之后本张真能花的净增那一截"。过去这一条腿不读留底，
     * 于是旁人<b>申报批</b>那 {@code k-1} 发的料能经网络绕开池子那把尺搬进本 lane 池子——
     * 实机 {@code 1e11} 那一跑（lane {@code 96d7b67a}）规划侧 {@code kept} 的那颗种就是被这样吃完的，
     * 增殖腿此后 {@code 每发=1,池内=0,网络sim=0}。
     *
     * <p>越出 {@code long} 的缺口<b>一轮只喂一刀</b>：AE2 的 {@code extract} 一次最多交出
     * {@code Long.MAX_VALUE}，剩下的那一截由下一批接着凑（池子是宽域的，累得下来）。
     * 顺位走到后续候选的条件是本批这一格<b>真</b>没撑满，而不是饱和读数对不上。
     *
     * @param payableFirings 本批要交割的发数，必须是 {@link #obligationBatchFirings} 的夹后值
     * @param refill         回补通道，{@code null} 即只吃内部缓冲（老行为）
     * @return 本批实际补进缓冲的合计枚数
     *
     * <p><b>账上已认的无限源键在这一条腿上一个字都不搬</b>（在候选循环开头就退出这一格）：
     * 取料点已经就地认量（见 {@code prepareUniform} 的 {@code unboundedSource} 那一支）。
     * 再把它搬进缓冲就是把同一份量记两次，而取料点不再向池子伸手 ⇒ 搬进来的那一截留在池子里，
     * 下一刻被 {@code storeItems()} 当残池投回网络 = 凭空多一份料。
     *
     * <p>还留在这条腿上的只有一种：<b>这一批现场认下的</b>——网络对同一笔请求 SIMULATE 答应、
     * MODULATE 一枚交不出（KubeJS／ExtendedAE 那类注册成无限的岩浆与物质球：不在任何单元里，
     * 规划时看不见）。这一张 {@link ParallelPlanReservation} 还不认它，取料点不会就地认量，
     * 于是它的 want 就是本批缺口（配额口径对它不成立），先按 ≤{@code long} 向网络真取一次；
     * <b>一枚都没取到就认下、然后收手</b>：这一格凭空的量一枚都不注进缓冲（不变式：认量绝不进池，
     * 注进去的残池下一刻会被 {@code storeItems()} 投回网络 = 凭空多一份料），键经
     * {@link ObligationRefill#noteUnboundedSupply} 交回本作业，下一 tick 配给表认得它之后
     * 由取料点就地认量。
     *
     * <p>这一句"多等一 tick"只对<b>义务交割</b>这一条腿成立：{@code payDeclaredBatchObligations}
     * 每 tick 都会再试一次，欠账原样挂着，很快就补得上（一轮最多
     * {@code DECLARED_OBLIGATION_BATCHES_PER_TICK} 批、还要过 tick 预算闸，所以"下一批"未必就是下一 tick）。走<b>任务提取</b>那条腿
     * （{@code refillTaskInputsFromNetwork}）时认中之后仍然拿不到料，调用方按提取失败登记
     * {@code MATERIAL_BACKOFF_TICKS}＝20 tick 的材料退避，于是要多等的是那 20 tick，
     * 直到料真到（{@code wakeMaterialBackoff} 按缺口键精准唤醒）或退避到期。两条腿的口径不是一句
     * "一 tick"，别看错了等的是哪一段。
     */
    static long refillObligationBatch(
            IPatternDetails details,
            ListCraftingInventory inventory,
            @Nullable Level level,
            long payableFirings,
            ParallelPlanReservation reservation,
            @Nullable ObligationRefill refill
    ) {
        return refillObligationBatch(details, inventory, level, payableFirings, reservation, refill,
                null);
    }

    /**
     * 同上，多一个 {@code probe}：<b>只加读数</b>（本轮补进池子的刀数与真值合计，见
     * {@link Probe#refillChunks}），不改任何判据。
     */
    static long refillObligationBatch(
            IPatternDetails details,
            ListCraftingInventory inventory,
            @Nullable Level level,
            long payableFirings,
            ParallelPlanReservation reservation,
            @Nullable ObligationRefill refill,
            @Nullable Probe probe
    ) {
        return refillObligationBatch(details, inventory, level,
                payableFirings <= 0L ? BigInteger.ZERO : BigInteger.valueOf(payableFirings),
                reservation, refill, probe);
    }

    /**
     * 同上，但发数走<b>宽域真值</b>。
     *
     * <p>{@code payableFirings} 只能是精确份数：它是"这一格本批真需几枚"的乘数。任务剩余发数
     * 越出 {@code long} 时，那份 {@code long} 饱和镜像是<b>哨兵不是量</b>——拿它乘出来的定数把
     * 回补夹在天花板那一格（缺口一旦补到 {@code Long.MAX_VALUE} 枚就永远"已撑满"），池子里
     * 再也补不进任务真需的那一截。AE2 通道仍一轮最多交一刀 {@code long}，这条不变。
     *
     * <p>配给表同样是编译期非空：这道判据的"只补到这张样板还没销完的配给"是回补不搬空别人
     * 口粮的唯一封顶，允许 {@code null} 就等于允许一条没有封顶的搬运路（上一条"注池"退路
     * 就是这么长出来的，已删）。
     */
    static long refillObligationBatch(
            IPatternDetails details,
            ListCraftingInventory inventory,
            @Nullable Level level,
            BigInteger payableFirings,
            ParallelPlanReservation reservation,
            @Nullable ObligationRefill refill,
            @Nullable Probe probe
    ) {
        if (refill == null || payableFirings == null || payableFirings.signum() <= 0L
                || details.getInputs() == null || details.getInputs().length == 0) {
            return 0L;
        }
        if (probe != null) {
            // 纯读数：走到这里才叫"本轮真为这张样板算过"（被每 tick 批数闸／tick 预算闸挡在外面的
            // 那张压根进不来）。停滞行的 本轮最后算的那张样板= 只读它，不参与任何判据。
            probe.noteRefillComputedPattern(details);
        }
        long pulled = 0L;
        var inputs = details.getInputs();
        for (int slotIndex = 0; slotIndex < inputs.length; slotIndex++) {
            var input = inputs[slotIndex];
            if (input == null || input.getMultiplier() <= 0L || input.getPossibleInputs() == null) {
                noteRefillBlockedCell(probe, details, slotIndex, null, "候选空表", 0L, BigInteger.ZERO);
                continue;
            }
            for (var candidate : input.getPossibleInputs()) {
                if (candidate == null || candidate.what() == null || candidate.amount() <= 0L) {
                    continue;
                }
                AEKey key = candidate.what();
                if (!input.isValid(key, level)) {
                    continue;
                }
                if (reservation.isUnboundedSupply(key)) {
                    // 已经认下的无限源<b>不经这一条腿</b>：取料点就地认量（见 {@code prepareUniform}
                    // 的 {@code unboundedSource} 那一支）。往池子里搬一刀就是把凭空的量当残池，
                    // 下一刻 {@code storeItems()} 把它投回网络 = 凭空多一份料；向网络抽它也是空转
                    // （那一枚本就不在任何单元里）。这一格到此为止，不退到后续候选多搬。
                    noteRefillBlockedCell(probe, details, slotIndex, key, "unbounded", 0L, BigInteger.ZERO);
                    break;
                }
                // 这一格本批真需几枚：宽域相乘。饱和值当 need 用会把"还差 9.2e18 枚"读成
                // "已经顶到天花板、不用补了"，跨批累进池子这条路当场断掉。
                BigInteger perFiringUnits = exactUnits(input.getMultiplier(), candidate.amount());
                // 分子与分母<b>同一个 scope</b>：分母 {@code wholeAsk} 是整张样板对该键的每发合计，
                // 分子就得是整张样板本批对该键的真需（合计 × 份数）。混 scope 的写法（本槽那 4 枚 ÷
                // 整张 8 枚）在同一键被这张样板的两个真槽各吃 4 枚时得 0 发 ⇒ "补了也没用" ⇒
                // {@code continue} ⇒ 这一键一枚都不补 ⇒ 池子见底即冻结、义务账永不可付。
                // 补满的目标也一起换成同一口径：只补到本槽那 4 枚而取料点每发要扣 8 枚，冻结只是
                // 从回补侧挪到取料侧（下一轮 onSite=4 已"撑满本槽"，谁也不再补），要的是"这一键本批
                // 真需多少就补多少"。
                // {@code wholeAsk} 交回 0（这个键只出现在模具槽上，取料点压根不为它扣份额）时按
                // {@link #cellPayableFirings} 的"无口径即不夹"处理，本槽那份原样保留，不多补一枚。
                BigInteger wholeAsk = wholePatternAskPerFiring(details, key, false, level);
                BigInteger exactNeed = wholeAsk.max(perFiringUnits).multiply(payableFirings);
                BigInteger exactOnSite = exactStored(inventory, key);
                // 这一键的族配额先算一次、下面 room 夹法复用同一个变量：顺位退让与取料点共用
                // <b>同一条</b>算式（{@link #cellPayableFirings}：{@code min(池内真值 ÷ 该键每发合计,
                // 族剩配额 ÷ 该族每发合计)}），于是"补进来的料取料点用不用得上"与取料点真正算出的
                // 那一格份数是同一个数。上一轮那把"保守上界"尺产出过"可花=yes 而这一格实际付 0"的
                // 矛盾读数，已删。
                // 池子那一支在这里交的是<b>补满之后</b>的那一截（exactNeed）：真正的池子夹子是下面
                // room 那一刀（真缺口 ∩ 族剩配额），不在这里重复夹一次。
                // 配额口径 {@code null}（模具槽／续传作业）= 不夹，与取料点交回 cap 同一条判据。
                var leftInQuota = reservation.quotaExact(key, details, input);
                var cellFirings = cellPayableFirings(exactNeed, wholeAsk,
                        leftInQuota == null ? null : leftInQuota.exactValue(),
                        wholePatternAskPerFiring(details, key, true, level));
                if (cellFirings != null && cellFirings.signum() < 1L) {
                    // 这一格连一发都算不出来：这一键一枚都不补，让位给下一候选
                    noteRefillBlockedCell(probe, details, slotIndex, key, "cellFirings<1", 0L, BigInteger.ZERO);
                    continue;
                }
                if (exactNeed.signum() <= 0L || exactOnSite.compareTo(exactNeed) >= 0L) {
                    // 顺位第一个候选就被内部缓冲撑住：不回补，本格到此为止
                    break;
                }
                BigInteger exactShort = exactNeed.subtract(exactOnSite);
                // 这一格还能补多少：真缺口 ∩ 这张样板在该族上还没销完的配给。两者都在<b>宽域</b>
                // 做差：族配给与族现量都可能越出 long，两个天花板读数相减得 0 就是把哨兵当了量
                // （还能补的那一截被判成配额用尽，>long 的一发在带配额的样板上永远开不了工）。
                // 只有夹完的那一个数才窄化回 long：AE2 的 extract 只能吃 long。
                // 没有配额口径（这张样板不在配给表里／模具槽）时 quotaExact 交回 null，不夹。
                var room = exactShort;
                if (leftInQuota != null) {
                    room = room.min(leftInQuota.exactValue().subtract(exactFamilyStored(
                            inventory, key)));
                }
                // 环种留底那一刀：与取料点、折发数那两条腿读<b>同一个读口、同一个 owed</b>
                // （{@link ParallelPlanReservation#spendableAfterRingSeedReserve}，这里不另算一截）。
                // 这一趟只许搬"搬进池子之后本张真能花的<b>净增</b>那一截"——上面那一支已经把族现量
                // 当本张的房子扣过一遍，被环扣着的那一层就在里面；按可花量的增量夹，同一份池内料
                // 只计一次，既不少减（旁人申报批那 k-1 截料经网络把环的口粮搬进本 lane 池子）
                // 也不多减（把池里那层算两遍就是少算这一族那一截的复发）。
                // 读口交回 null（这一族不在账上／本张正是<b>环分量的成员</b>）即这一格一字不夹：
                // 豁免写成"照池子现量夹"会把增殖腿自己扣在起步那一枚上，链式环互等死锁。
                var spendableBeforeSeedReserve = reservation.spendableAfterRingSeedReserve(
                        key, exactOnSite, details);
                boolean clampedBySeedReserve = false;
                if (spendableBeforeSeedReserve != null) {
                    var roomBySeedReserve = reservation.spendableAfterRingSeedReserve(
                                    key, exactOnSite.add(room.max(BigInteger.ZERO)), details)
                            .subtract(spendableBeforeSeedReserve).max(BigInteger.ZERO);
                    clampedBySeedReserve = roomBySeedReserve.compareTo(room) < 0;
                    room = room.min(roomBySeedReserve);
                }
                if (room.signum() <= 0L) {
                    noteRefillBlockedCell(probe, details, slotIndex, key,
                            clampedBySeedReserve ? "留底夹" : "配额夹", 0L, BigInteger.ZERO);
                    continue;
                }
                // 一口只喂得出一个 long，但同一格<b>连着喂到凑齐或墙钟用完</b>：旧形状一轮只喂
                // 一刀，5.2e13 发的欠账于是每 100 tick 只推进几发（实机 2026-09-25 19:08 那一跑）。
                // 定数口径一个字没动——上限仍是"这一键本批真需的那一截"（与上面 exactNeed 同一个
                // scope），也仍只有这一格真没撑满才退到后续候选；改的只是"一轮喂几刀"。
                long gotSoFar = 0L;
                // landedSoFar（台账 #226 笔 3a，纯读数）：insert 前后各取一次该键的宽域池内量
                // （exactStored，与销账／占料那几处同一把尺，不造第三把尺），差值才是"真落池"的那一截。
                // 只印，不参与这里任何一次夹取、退让或早退——room 仍按 got 收，与拆档之前逐字相同。
                BigInteger landedSoFar = BigInteger.ZERO;
                boolean latchedUnbounded = false;
                boolean ranDry = false;
                long lastWant = 0L;
                while (room.signum() > 0L) {
                    long want = room.min(WideCeilings.LONG_ACCOUNT).longValue();
                    // 记住伸出去的那一笔（夹过配额的 room，不是原始 exactShort）：下面认无限源
                    // 只许问<b>同一笔请求</b>——若拿 exactShort 去 probe，一旦 room<exactShort
                    // （配额夹掉了尾段）就在"room 交不出、exactShort 却有货"的形状上误认无限源，
                    // 这一格此后走 isUnboundedSupply 早退 + 取料点就地认量 = 凭空造料。
                    lastWant = want;
                    long got = refill.pull(key, want);
                    if (got > 0L) {
                        var storedBeforeInsert = exactStored(inventory, key);
                        inventory.insert(key, got, Actionable.MODULATE);
                        landedSoFar = landedSoFar.add(
                                exactStored(inventory, key).subtract(storedBeforeInsert));
                        pulled = addOrMax(pulled, got);
                        noteRefillChunk(probe, key, got);
                        gotSoFar = addOrMax(gotSoFar, got);
                        room = room.subtract(BigInteger.valueOf(got));
                    }
                    if (got < want) {
                        ranDry = true; // 这一刀没喂满：这一格到此为止（是否退后续候选看下面）
                        noteRefillBlockedCell(probe, details, slotIndex, key, "ranDry", gotSoFar, landedSoFar);
                        break;
                    }
                    if (room.signum() > 0L && !refill.hasTimeBudget()) {
                        break; // 墙钟没了：剩下的下一轮凑，不把本轮推送预算吃光
                    }
                }
                if (gotSoFar == 0L) {
                    // 判据一字未动：同一笔请求的 SIMULATE 仍只在 gotSoFar==0 时问一次、仍是
                    // ">0 即认无限源"。这里只是把那一问的答案<b>连同同族读数</b>拼进 obWhy 那一格。
                    long sameKeySim = refill.probe(key, lastWant);
                    noteRefillNetworkReadout(probe, details, slotIndex, key, refill, lastWant,
                            sameKeySim);
                    if (sameKeySim > 0L) {
                        // 同一笔请求，网络 SIMULATE 答应、MODULATE 一枚都交不出——这才是"报得出取不出"
                        // 的无限元件（实机 minecraft:lava、ae2:matter_ball：alloc 三键 left=Long.MAX
                        // taken=0 stored=0 toMake=0，全员等一份永远不会来的料）。判据是行为不是报量：
                        // 巨大有限盘（实机 mekanism:redstone 网络 1.47e20）真交得出 got>0，
                        // 一次也进不到这一支，照旧按 long 分批真取。
                        refill.noteUnboundedSupply(key);
                        latchedUnbounded = true;
                        // 认下即收手，这一格一枚都不搬：作业那份集合是<b>下一 tick</b> 配给表的输入
                        // （{@code ParallelCraftingCpuLogic} 每 tick 重建它），于是同一批付不出、欠账原样
                        // 留在义务账上，下一批由取料点就地认量（{@code prepareUniform} 的
                        // unboundedSource 那一支）。
                        //
                        // 这里<b>没有</b>"把缺口注进缓冲、让同一批伸手取走"的第二条路：注进来的量不是
                        // 网络交出的，它留在池里唯一的去处就是 {@code storeItems()} 投回网络 = 凭空多
                        // 一份料（本仓已因此回退过一次）。代价是这一键多等一 tick，换"认量绝不进池"
                        // 这条不变式无条件成立——NeoEco 那条快路径的销账（按池子真少的那一截记）正是
                        // 建在它上面。
                        break;
                    }
                }
                if (latchedUnbounded || room.signum() <= 0L) {
                    // 认下无限源即收手；这一格本批撑满了也不退后续候选（"多一枚都不要"）
                    break;
                }
                if (ranDry && exactShort.compareTo(WideCeilings.LONG_ACCOUNT) > 0) {
                    // 缺口本身就装不进一刀：首候选这一刀交不满即停手，不退到后续候选——
                    // 那样等于把该槽下一批才该凑的那一截提前堆进池子（定数口径被吃穿）。
                    // 与"一轮只喂一刀"旧形状同效，区别只是同一格在交得出满口时允许连喂。
                    break;
                }
                // 这一格还没撑满本批：按顺位退到下一个候选继续补，本批交得出多少算多少
            }
        }
        return pulled;
    }

    /**
     * 这一格算得出的份数（<b>全仓唯一</b>一处表达式）：
     * {@code min( 池内真值 ÷ 该键每发合计 , 该族剩配额 ÷ 该族每发合计 )}。
     *
     * <p>取料点 {@link #primaryCandidate} 的宽域那一支、义务回补侧"这一族的料补进来到底用不用得上"
     * 那道判据、停滞行 {@code blocked=} 的读数，调的都是<b>这一个</b>表达式；<b>没有第二份算式</b>。
     * 但两份<b>调用者的分母 scope</b> 不是一回事，别再混进同一行读数：取料点传的是<b>批内已选槽
     * 累加</b>那两份（{@code exactDemandPerFiring} / {@code familyDemandPerFiring}，真扣配额的权威），
     * 回补侧传的是 {@link #wholePatternAskPerFiring} 那份<b>整张样板 scope</b>（分子分母同 scope，
     * 量的是搬运上限）。停滞行不再自己算一遍——它印的是取料点那份（{@link Probe#failingCell}）。
     *
     * <p>上一轮那把"保守上界"尺（族每发件数取每槽候选里的<b>最大项</b>）已删：它比取料点真正扣的
     * 那份分母大，产出过"可花=yes 而这一格实际付 0"的自相矛盾读数。
     *
     * @param exactAvailable   这一格拿得动的<b>真值</b>（池子真值 ∩ 配额夹过的 usableFor）
     * @param exactKeyAsk      该键在整张样板上的每发合计件数
     * @param quotaLeft        该族剩余额度真值；{@code null} = 没有配额口径（模具槽／续传作业）⇒ 不夹
     * @param exactFamilyAsk   该物品本体族在整张样板上的每发合计件数
     * @return {@code null} = 两道夹子都没有口径（不夹，份数由调用方那一刀自己定）
     */
    static @Nullable BigInteger cellPayableFirings(@Nullable BigInteger exactAvailable,
            @Nullable BigInteger exactKeyAsk, @Nullable BigInteger quotaLeft,
            @Nullable BigInteger exactFamilyAsk
    ) {
        BigInteger limit = null;
        if (exactAvailable != null && exactKeyAsk != null && exactKeyAsk.signum() > 0L) {
            limit = exactAvailable.divide(exactKeyAsk);
        }
        if (quotaLeft != null && exactFamilyAsk != null && exactFamilyAsk.signum() > 0L) {
            BigInteger byQuota = quotaLeft.divide(exactFamilyAsk);
            limit = limit == null ? byQuota : limit.min(byQuota);
        }
        return limit;
    }

    /**
     * <b>整张样板 scope</b>：取料点在整张样板上会为<b>这个键</b>（{@code wholeFamily=false}）或它所在的
     * <b>物品本体族</b>（{@code wholeFamily=true}）扣掉的每发合计件数——按声明顺位，每一格只取
     * "收得下它的第一个候选"。
     *
     * <p><b>这不是配额判定的那一份分母</b>（2026-09-28 裁决）：配额判定（{@link #primaryCandidate}
     * 算这一格开几发）用的是<b>批内已选槽累加</b>的那两份 map（{@code exactDemandPerFiring} /
     * {@code familyDemandPerFiring}），它是真扣配额的那一份，因而<b>是权威</b>。这里这份算的是
     * "<b>整张样板</b>对这个键总共要几枚"，用来给搬运定上限（回补侧分子分母同 scope ⇒ 这一支自洽）：
     * <b>同一个判据摆两种 scope、两种分母，产出的就是下一次误判的入口</b>——所以它只许出现在回补侧，
     * 停滞行的"这一格几发"一律取 {@link Probe#failingCell} 里那份批内读数，不许调这里再算一次。
     *
     * <p>两条收/不收与取料点<b>同一条判据</b>，摘掉任一条分母就比取料点真正扣的那份大：
     * <ul>
     *   <li>原样退回的模具槽不算——取料点对它 {@code quotaExact} 交回 {@code null}，压根不为它扣
     *       族配额（{@link SlotAccount#selfRepaying} 就是那条判据）；</li>
     *   <li>{@code isValid} 不收这个候选的槽不算——取料点的候选宇宙走 {@link #usableTemplates}，
     *       那里压根递不出它。</li>
     * </ul>
     *
     * <p>{@code level == null} 的口径与 {@link #usableTemplates}、{@link #refillObligationBatch}
     * 开头那句 {@code !input.isValid(key, level)} 是<b>同一个入参、同一句调用</b>：解引用世界的
     * 模糊替代那一支（合成、石切、锻造台同族）在这里同样抛 NPE，既不写放宽也不另起 null 分支。
     */
    static BigInteger wholePatternAskPerFiring(@Nullable IPatternDetails details, @Nullable AEKey key,
            boolean wholeFamily, @Nullable Level level
    ) {
        var slots = details == null ? null : details.getInputs();
        if (slots == null || key == null) {
            return BigInteger.ZERO;
        }
        var family = key.getPrimaryKey();
        var total = BigInteger.ZERO;
        for (var slot : slots) {
            var candidates = slot == null ? null : slot.getPossibleInputs();
            if (candidates == null) {
                continue;
            }
            for (var candidate : candidates) {
                if (candidate == null || candidate.what() == null) {
                    continue;
                }
                var what = candidate.what();
                var matches = wholeFamily ? family.equals(what.getPrimaryKey()) : key.equals(what);
                if (!matches || SlotAccount.selfRepaying(slot, what)
                        || !slot.isValid(what, level)) {
                    // 取料点不为这一格这一候选扣份额：模具槽 quotaExact=null、不收该候选的槽
                    // 在 usableTemplates 里压根没有它
                    continue;
                }
                total = total.add(exactUnits(slot.getMultiplier(), candidate.amount()));
                break;
            }
        }
        return total;
    }

    /**
     * 记一刀回补（纯读数，不参与任何判据）：见 {@link Probe#refillChunks}。
     *
     * <p>一发真需 {@code >long} 时这一格天然要几轮才凑得齐，读数分"刀"与"真值合计"两格才分得出
     * "在分刀推进"与"真没推进"——那份 {@code long} 的合计读数第二轮起就钉在天花板上不动了。
     *
     * <p>同一刀另记一份<b>逐枚键</b>的账（{@link Probe#refillUnitsPerKey}，台账 #225 读数 6）：
     * 键就是 {@code refill.pull} 伸出去的那一枚（与插进池子的同一枚实例），聚合两格一个字不动。
     */
    private static void noteRefillChunk(@Nullable Probe probe, AEKey key, long units) {
        if (probe == null || units <= 0L) {
            return;
        }
        probe.refillChunks++;
        probe.refillExactUnits = probe.refillExactUnits.add(BigInteger.valueOf(units));
        probe.refillUnitsPerKey.merge(key, BigInteger.valueOf(units), BigInteger::add);
    }

    /**
     * 记下回补这条腿上<b>被挡的那一格</b>（纯读数）：按 <b>(样板, 槽)</b> 分桶，每桶各留第一格，
     * 每 tick 每 lane 封顶 {@code Probe} 那份表里写的格数，装不下的只加 {@code obWhy更多=} 计数
     * （紧跟在 {@code obWhy=} 后面同一格，不粘在最后一格尾巴上，per100r 才切得出独立的那一段）。
     * 不新增判据、不改搬运口径。
     */
    private static void noteRefillBlockedCell(@Nullable Probe probe, @Nullable IPatternDetails details,
            int slotIndex, @Nullable AEKey key, String why, long got, BigInteger landed) {
        if (probe == null) {
            return;
        }
        probe.noteRefillBlockedCell(details, slotIndex, key, why, got, landed);
    }

    /**
     * 伸手拿到 0 时，把"网络侧这一族到底有什么"拼进<b>它自己那一格</b>（按 (样板, 槽) 分桶的那本
     * 读数，见 {@link Probe#noteRefillBlockedCell}）——<b>纯读数</b>：只拼串，没有任何判据读它。
     *
     * <p>旧形状是每个 {@code Probe} 只留第一格，于是 {@code got==0} 的这一格常常压根不在位子上
     * （实机 slot#1 占了位子、一个字都没印出来，而真卡住这条 lane 的是<b>另一张样板</b>的 slot#3）。
     * 现在只认 (这张样板, 这个槽, 这个键) 且 {@code got==0} 的那一格：格子不在位就不问网络
     * （{@code networkReadout} 惰性求值），同一格只拼一次 ⇒ 现读只在 {@code got==0} 那一支发生、
     * <b>每格一次</b>，不进候选循环、不进热路径。
     *
     * <p>两个读数各管一问：{@code 网络sim=} 是<b>这一精确键</b>对同一笔请求（{@code want} =
     * 真伸手那一份夹过配额的量）的 SIMULATE 答案；{@code 同族[…]} 是同物品本体族的<b>逐精确键</b>
     * 现量与键数。键数顶到上限时明写 {@code N+}，不把"看到的四个"报成"一共四个"。
     *
     * <p>量一格只问一次现成的 {@link ObligationRefill#probe}（同一条网络通道，不另开第二条）；
     * 一次 SIMULATE 最多问得出一个 {@code long}，>long 的盘报回来的就是<b>下界</b>，故整串标
     * {@code 下界}。聚合报量在这里一个都不取（{@code NetworkStorage#getAvailableStacks} 走
     * fastutil {@code addTo} 的普通 long 加法，回绕不饱和，既非上界也非下界）——键集才来自它。
     */
    private static void noteRefillNetworkReadout(@Nullable Probe probe, @Nullable IPatternDetails details,
            int slotIndex, AEKey key, ObligationRefill refill, long want, long sameKeySim) {
        if (probe == null || key == null || want <= 0L) {
            return;
        }
        probe.noteRefillNetworkReadout(details, slotIndex, key,
                () -> describeRefillNetworkReadout(refill, key, want, sameKeySim));
    }

    /** {@code 网络sim=/同族[…]下界} 那一串：只在被拼的那一格真在位子上时才被叫到（惰性，每格一次）。 */
    private static String describeRefillNetworkReadout(ObligationRefill refill, AEKey key, long want,
            long sameKeySim) {
        var family = refill.sameFamilyKeys(key);
        var described = new StringBuilder();
        int shown = 0;
        for (var member : family) {
            if (member == null) {
                continue;
            }
            if (shown >= NETWORK_FAMILY_READOUT_LIMIT) {
                break;
            }
            if (shown > 0) {
                // 同族里格与格用 "/" 连——旧形状 " | " 的两段空白会把 per100r 的 obWhy 那一格劈成三段
                // （见 {@link #describeRefillBlockedCells}）；这一串自带的三段前导空白与 member 键
                // toString 的 "(with patches)"，由 RefillBlockedCell#describe 在格出口统一折成 "_"。
                described.append('/');
            }
            // 本键那一份用的就是真伸手问出的 sameKeySim，不重复问一次网络
            described.append(member).append('=').append(member.equals(key)
                    ? sameKeySim : refill.probe(member, want));
            shown++;
        }
        return " 网络sim=" + sameKeySim
                + " 同族键数=" + family.size()
                + (family.size() >= NETWORK_FAMILY_READOUT_LIMIT ? "+" : "")
                + " 同族[" + described + "]下界";
    }

    /**
     * 内部缓冲在该键所属<b>物品本体族</b>上的<b>精确</b>现量：配给按族记，回补量也得按族扣。
     *
     * <p>读的是 {@link WideCraftingInventory#amount(ListCraftingInventory, AEKey)} 那道口，
     * 不是 {@code KeyCounter} 主表的格子：溢出键在主表恒等于 {@code Long.MAX_VALUE}，跨批累进池子的
     * 那一截只挂在侧表上。拿主表读数去和配额做差，就是把两份哨兵相减当成"还能补 0 枚"。
     */
    private static BigInteger exactFamilyStored(ListCraftingInventory inventory, AEKey key) {
        var family = key.getPrimaryKey();
        BigInteger total = BigInteger.ZERO;
        for (var entry : inventory.list) {
            AEKey stored = entry.getKey();
            if (stored != null && family.equals(stored.getPrimaryKey())) {
                // 这里读的是<b>池子自己的那一枚实例</b>：主表分格按引用，遍历到的键天然落在
                // 它自己那一格里。套一层 exactStored 的 equals 找桶只会两件事——每格再全池扫一遍，
                // 以及同族有两把桶时把这一格的数量读成<b>另一格</b>的；合计语义（逐格相加）必须原样保住。
                total = total.add(WideCraftingInventory.amount(inventory, stored));
            }
        }
        return total;
    }

    /**
     * 义务取料支持宽域材料真值；批次只受发数载体 long 限制，材料量由现场和配额约束。
     * 每发用量乘批次可以超过 long，由宽域库存一次扣除；普通库存仍走实际提取。
     */
    static long obligationBatchFirings(IPatternDetails details, BigInteger remaining) {
        return remaining == null || remaining.signum() <= 0
                ? 0L : saturate(remaining);
    }

    /**
     * 申报份数的<b>可付上界</b>：各槽候选键的现场量按宽域加总、除以各自每发件数，跨槽取最小。
     *
     * <p>这是"上界"的字面含义——同一键被两格共用时这里会被计入两次，真扣料由每批
     * {@link #payDeclaredObligation} 再夹一刀，付不出的部分留在义务账上等料，
     * 但绝不一次声明出池子根本拿不出的份数（那等于让机器白产）。
     *
     * <p>配额夹按申报上界读数走一次 {@link ParallelPlanReservation#usableFor}，与
     * {@link #primaryCandidate} 同一道判据（不是第二份顺位账：这里不挑键、不定序，只估总量）。
     *
     * <p>无限源那一格<b>不进这把尺子</b>：它与 {@link #primaryCandidate} 同一条判据
     * （{@link ParallelPlanReservation#isUnboundedSupply}），交得出任意多枚就不是这一格的约束项。
     * 拿"池子现量 ÷ 每发件数"算它就是把 {@code 0 ÷ 件数} 当成"这一格付不起一发"，申报整批归零
     * （实机 {@code declaredReject=99} 那一型）。
     *
     * <p>{@code poolClaims} 是<b>别的样板已经申出去、还没交割</b>的池料（
     * {@link BatchObligationLedger#pendingPoolClaims}，顺位排水算出的派生读数）：申报批的料在交割完
     * 之前一直躺在同一个池子里，而这一把尺子读的就是那份池子——不把它遮住，同一份存量就被许诺两次，
     * 后申那一张从此永远交割不出（停滞行里的 owed 挂死）。来申的这张样板自己不会有欠账
     * （{@code attemptDeclaredBatch} 开头那道"一张样板同时只挂一笔"的守卫挡掉），所以这里
     * 遮住的是<b>全部</b>已挂上的 claim，不再按归属排除自己。
     *
     * <p>减法在<b>宽域真值</b>上做，并且 {@code max(ZERO)}：claim 是按顺位从这份池子里排出去的
     * 量，池子被回补／交割动过之后它就是可能小于 claim——判成负数等于把"这一格没料"报成
     * "这一格欠料"，申报直接归零。
     */
    static BigInteger declaredPayableCeiling(
            IPatternDetails details,
            ListCraftingInventory inventory,
            @Nullable Level level,
            @Nullable ParallelPlanReservation reservation,
            BigInteger cap
    ) {
        return declaredPayableCeiling(details, inventory, level, reservation, cap, null);
    }

    /** 同上，多一份"别人已经申出去的池料"：每键从现场量里先遮住它。 */
    static BigInteger declaredPayableCeiling(
            IPatternDetails details,
            ListCraftingInventory inventory,
            @Nullable Level level,
            @Nullable ParallelPlanReservation reservation,
            BigInteger cap,
            @Nullable WideKeyCounter poolClaims
    ) {
        var inputs = details.getInputs();
        if (inputs == null || inputs.length == 0 || cap.signum() <= 0L) {
            return BigInteger.ZERO;
        }
        long readout = cap.compareTo(WideCeilings.LONG_ACCOUNT) >= 0 ? Long.MAX_VALUE : cap.longValue();
        BigInteger limit = cap;
        for (var input : inputs) {
            if (input == null || input.getMultiplier() <= 0L) {
                return BigInteger.ZERO;
            }
            var templates = usableTemplates(inventory, input, level, reservation);
            var seen = new HashSet<AEKey>();
            BigInteger slotFirings = BigInteger.ZERO;
            boolean slotHasUnboundedSource = false;
            for (InputTemplate template : templates) {
                BigInteger exactUnits = exactUnits(input.getMultiplier(), template.amount());
                if (exactUnits.signum() <= 0L || !seen.add(template.key())) {
                    continue;
                }
                if (reservation != null && reservation.isUnboundedSupply(template.key())) {
                    slotHasUnboundedSource = true;
                    break;
                }
                // 现场量与每发件数都取真值：主表的 long 天花板读数会把"池子里放着两发"
                // 除成"只撑得起一发"，申报于是永远进不到 >long 的那一格。
                BigInteger exactAvailable = exactStored(inventory, template.key());
                if (poolClaims != null) {
                    exactAvailable = exactAvailable
                            .subtract(poolClaims.getExact(template.key())).max(BigInteger.ZERO);
                }
                long stored = inventory.extract(template.key(), Long.MAX_VALUE, Actionable.SIMULATE);
                if (reservation != null) {
                    PlanAmount quota = reservation.quotaExact(template.key(), details, input);
                    if (quota != null) {
                        exactAvailable = exactAvailable.min(quota.exactValue().max(BigInteger.ZERO));
                    }
                    long usable = reservation.usableFor(template.key(), stored, details,
                            readout, input, countDistinctKeys(templates) > 1);
                    // 同 primaryCandidate：读数的天花板不是一道真夹子，不许拿它剪掉池子的真值
                    if (usable < Long.MAX_VALUE) {
                        exactAvailable = exactAvailable.min(BigInteger.valueOf(Math.max(0L, usable)));
                    }
                    stored = Math.min(stored, usable);
                }
                if (exactAvailable.signum() > 0L) {
                    slotFirings = slotFirings.add(exactAvailable.divide(exactUnits));
                }
            }
            if (slotHasUnboundedSource) {
                continue;
            }
            if (slotFirings.signum() <= 0L) {
                // 这一格现场连一发都付不起：上界归零，申报随之作废（不改探针计数，
                // 判失败的账由真正伸手的那次 primaryCandidate 记）。
                return BigInteger.ZERO;
            }
            limit = limit.min(slotFirings);
        }
        return limit;
    }

    /** 窄域宿主或 k 倍 holder 的产出上界；BigInteger 声明宿主不使用此限制。 */
    static BigInteger representableOutputCeiling(IPatternDetails details) {
        BigInteger limit = WideCeilings.LONG_ACCOUNT;
        for (var output : details.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                limit = limit.min(BigInteger.valueOf(Long.MAX_VALUE / output.amount()));
            }
        }
        return limit;
    }

    /**
     * 同一把尺的<b>输入</b>口径：申报 × 每发倍率 ≤ long。
     *
     * <p>它唯一的读者是 {@link #addDeclaredReserves} 里被 {@code getRemainingKey(...) != null}
     * 门控的 {@code expectedContainerItems}（回流容器也是按键 {@code long} 账）。宿主用
     * {@code BigInteger} 记份数、且这一张样板没有回流容器时，它就不是这一条腿的约束项 ⇒
     * 申报侧对 {@code declaredBatchFiringsUnbounded()} 的宿主跳过它。窄域那一路（k 倍 holder）
     * 仍与产出支取交集一起夹（{@link #clampToRepresentablePerFiringAmounts}），一字不变。
     */
    static BigInteger representableInputCeiling(IPatternDetails details) {
        BigInteger limit = WideCeilings.LONG_ACCOUNT;
        for (var input : details.getInputs()) {
            if (input == null || input.getMultiplier() <= 1L) {
                continue;
            }
            limit = limit.min(BigInteger.valueOf(Long.MAX_VALUE / input.getMultiplier()));
        }
        return limit;
    }

    /**
     * 该槽按样板候选顺位第一个撑得起 ≥1 发的候选，份数由该键存量算出。
     *
     * <p>不按"谁能撑最多发"挑：那样执行侧会改取同族里更富的键，而规划期已把同一个
     * 富余量许诺给别的槽，同族供给被双重指派，尾段就成了无产者的缺口。规划侧
     * {@code LinearPlanner.request} 对模糊槽就是顺位榨干靠前的模板再退下一个，
     * 两侧必须同一条顺位规则。
     *
     * <p>顺位规则只保证"两侧同序"，不保证"两侧同料"：计划自己产出的中间料会让本槽
     * 在现场多出一个规划期没有的键，按顺位取走它就把别的只吃得下这个键的任务饿死。
     * 反过来，规划侧给这一格记的是备选键、备选却还没落到池子时，按顺位取走首位键
     * 同样把别人唯一能用的那份吃干。所以每格都要把 {@code input} 交给
     * {@link ParallelPlanReservation} 夹一次——别人唯一能用的那份现场料留在池子里，
     * 本槽退到下一个键；两条退路视角都不成立（真改不了道）才不夹。
     *
     * <p>{@code widePayment} 那一条路径上，"这一格撑得起几发"由<b>池子的真值 ÷ 每发的真值</b>
     * 算出来：池子主表报的是 {@code long} 天花板、每发件数也会饱和成同一个天花板，两个哨兵
     * 相除得到的"1 发"既可能把付得起的两发夹成一发、也可能把付不起的一发放行成两发。
     *
     * <p><b>无限源键不是这一格的约束项</b>：它给得出任意多枚，"池子现量 ÷ 每发件数"算的是
     * {@code 0 ÷ 件数 = 0 发}，而池子里那枚本就该是 0（提交侧不向网络抽它）。份数由别的槽钉住。
     * 配额那两道夹子对它本来就不成立（{@link ParallelPlanReservation#quotaExact} 交 {@code null}、
     * {@link ParallelPlanReservation#usableFor} 一条早退），这里不另起第二道判据。
     */
    @Nullable
    private static Candidate primaryCandidate(IPatternDetails.IInput input,
            ListCraftingInventory inventory, @Nullable Level level,
            Map<AEKey, Long> demandPerFiring, Map<AEKey, BigInteger> exactDemandPerFiring,
            Map<Object, PlanAmount> familyDemandPerFiring, long cap,
            @Nullable ParallelPlanReservation reservation,
            IPatternDetails details, long batchLimit,
            @Nullable Probe probe, boolean widePayment, int slotIndex,
            Probe.MinCellScope minCellScope) {
        long multiplier = input.getMultiplier();
        // AE2 的 getValidItemTemplates 不是照抄样板候选表：它按物品本体把同物品的多个组件变体
        // 一并递回来，再交给该槽的 isValid 过滤。第三方样板（按标签放行的万能样板）还会往里
        // 塞别的物品。所以"有没有退路"只看现场实际落得下的键数，不看候选表长度。
        var templates = usableTemplates(inventory, input, level, reservation);
        boolean substitutable = reservation != null && countDistinctKeys(templates) > 1;
        for (InputTemplate template : templates) {
            BigInteger exactUnits = exactUnits(multiplier, template.amount());
            long unitsPerExecution = saturate(exactUnits);
            if (unitsPerExecution <= 0L) {
                continue;
            }
            boolean unboundedSource = reservation != null
                    && reservation.isUnboundedSupply(template.key());
            if (unboundedSource) {
                // 现场量在这里只是<b>读数</b>（宿主与诊断要看到池子里确实是 0），不是这一格的份数判据
                long onSite = inventory.extract(template.key(), Long.MAX_VALUE, Actionable.SIMULATE);
                return new Candidate(template.key(), unitsPerExecution, cap, onSite,
                        unitsPerExecution, exactUnits, true);
            }
            BigInteger exactAvailable = exactStored(inventory, template.key());
            long stored = inventory.extract(template.key(), Long.MAX_VALUE, Actionable.SIMULATE);
            long usableReadout = stored;
            if (reservation != null) {
                long usableNow = reservation.usableFor(template.key(), stored, details, batchLimit,
                        input, substitutable);
                usableReadout = usableNow;
                if (usableNow < stored && probe != null) {
                    probe.resClamp++;
                }
                // usableNow == Long.MAX 是配额读数的天花板（"宁高估"那道出口），不是一道真夹子：
                // 拿它去夹池子的真值就是把还能付的那一截剪掉，>long 的一发永远凑不齐。
                if (usableNow < Long.MAX_VALUE) {
                    exactAvailable = exactAvailable.min(BigInteger.valueOf(Math.max(0L, usableNow)));
                }
                stored = Math.min(stored, usableNow);
            }
            // 同一键被多个槽吃时，可行份数由"每发合计需求"这个线性约束决定：
            // E × (前面槽的每发件数 + 本槽每发件数) ≤ 现场量。
            // 跨槽同键时 demand 是 addOrMax 饱和过的每发合计、stored 是池子的饱和 long 读数
            // （extract MAX SIMULATE），两个哨兵相除把还能付的那一截夹小一次；紧贴的下一句已经在
            // 宽域上算出真商，widePayment=true 时让位给那一份，窄域商不再二次夹。widePayment=false 走原路，一位不动。
            long demand = addOrMax(demandPerFiring.getOrDefault(template.key(), 0L), unitsPerExecution);
            long executions = widePayment ? cap : Math.min(cap, stored / demand);
            // 该键与它所在族"每发合计吃几枚"的<b>终值</b>口径，与回补侧、停滞行共用同一个
            // cellPayableFirings（全仓一处）：旧那把"保守上界"尺产出过"可花=yes 而这一格付 0"的
            // 自相矛盾读数，已删。
            BigInteger exactKeyAsk = exactDemandPerFiring
                    .getOrDefault(template.key(), BigInteger.ZERO).add(exactUnits);
            PlanAmount quota = reservation == null ? null
                    : reservation.quotaExact(template.key(), details, input);
            BigInteger exactFamilyAsk = familyDemandPerFiring
                    .getOrDefault(template.key().getPrimaryKey(), PlanAmount.ZERO)
                    .add(PlanAmount.of(exactUnits)).exactValue();
            // 这一格的份数：<b>批内 scope</b>（分母是取料点真正会为这一格扣掉的那两枚数）。
            // 只在这里算这一次：宽域那一支拿它夹判据，停滞行那一支拿它印读数，两侧同一个数。
            BigInteger batchScopePayable = cellPayableFirings(exactAvailable, exactKeyAsk,
                    quota == null ? null : quota.exactValue(), exactFamilyAsk);
            if (widePayment) {
                if (batchScopePayable != null) {
                    executions = Math.min(executions, saturate(batchScopePayable));
                }
            } else {
                // 现场量约束按精确键算，配额约束却按族给：同族两个变体各吃一个槽时，
                // 每一槽都按"整份族配额"被放行，合起来就超出规划定死的份数
                // （实机 {@code site=batch minecraft:glass ate=11992458236 share=11992458235}）。
                // 同一个线性约束换到族上：E × (本批该族每发合计件数) ≤ 该族剩余份额。
                long familyLimited = executionsInsideFamilyQuota(
                        reservation, details, input, template.key(), familyDemandPerFiring,
                        PlanAmount.of(exactUnits), executions);
                if (familyLimited < executions && probe != null) {
                    probe.familyClamp++;
                }
                executions = familyLimited;
            }
            if (executions >= 1L) {
                if (probe != null) {
                    // 纯读数：这一格算得出几发就记几发，本趟取料腿最紧的那一格由 Probe#noteMinCell
                    // 按 scope 留着（原型趟的份数写死 1，别让它冒充批处理上限）。
                    // "每交互只推进 1 发"钉住份数的常常正是这一格（executions == 1），它不是失败格，
                    // blockedCell 点不到它。判据一字未动（仍是上面那一句 executions >= 1）。
                    probe.noteMinCell(minCellScope, slotIndex, template.key(), exactUnits,
                            exactAvailable, quota,
                            usableReadout, exactKeyAsk, exactFamilyAsk, cap, executions);
                }
                return new Candidate(template.key(), unitsPerExecution, executions, stored, demand,
                        exactUnits, false);
            }
            if (probe != null) {
                // 这一格算不出<b>一发</b>：把现场原样留下（纯读数，判据就是上面那一句的否分支）
                probe.noteFailingCell(details, slotIndex, template.key(), exactUnits, exactAvailable,
                        quota, usableReadout, exactKeyAsk, exactFamilyAsk, cap, batchScopePayable);
            }
        }
        return null;
    }

    /**
     * 混取时这一格还能从这个候选上拿几份模板：族剩余份额先扣掉本批在该族上已吃的枚数。
     *
     * <p>没有配额口径（续传作业、模具槽）时返回 {@link Long#MAX_VALUE}，不参与夹量。
     *
     * <p><b>减法与除法都在宽域上做</b>：份额是 {@code quotaExact} 的精确值（族配给与已吃账都是
     * 跨笔复加的账，合计可以越出 {@code long}），而 {@code quotaFor} 那份对外读数是"宁高估"
     * 钉到 {@link Long#MAX_VALUE} 的<b>哨兵</b>——哨兵减哨兵得 {@code 0}、哨兵除哨兵得 {@code 1}，
     * 两种都是把还能付的那一截判成配额用尽。做完全流程才窄化一次喂调用方（它只能吃 {@code long}）。
     */
    private static long templatesInsideFamilyQuota(ParallelPlanReservation reservation,
            IPatternDetails details, IPatternDetails.IInput input, InputTemplate template,
            Map<Object, PlanAmount> familySpent
    ) {
        PlanAmount quota = reservation.quotaExact(template.key(), details, input);
        if (quota == null) {
            return Long.MAX_VALUE;
        }
        PlanAmount spent = familySpent.getOrDefault(template.key().getPrimaryKey(), PlanAmount.ZERO);
        PlanAmount left = quota.subtract(spent).max(PlanAmount.ZERO);
        return Ae2Amounts.toLong(left.divide(PlanAmount.of(template.amount())));
    }

    /**
     * 本批在该键所属<b>物品本体族</b>上还能开几发：份额减去同批前面槽在本族的承诺后，
     * 除以"含本槽的每发合计件数"。
     *
     * <p>没有配额口径（续传作业、模具槽）时原样返回 {@code cap}，不参与夹份数。
     *
     * <p>分子走 {@link ParallelPlanReservation#quotaExact} 的<b>宽域真值</b>、分母是各槽每发件数的
     * <b>精确合计</b>（{@link Candidate#exactUnitsPerFiring()}），商做完才 {@link #saturate} 一次：
     * {@code quotaFor} 与 {@code addOrMax} 那两份天花板读数同顶时，{@code Long.MAX_VALUE /
     * Long.MAX_VALUE = 1} 会把夹得住的 2 发读成 1 发——份数就是这么被哨兵夹不住的。
     */
    private static long executionsInsideFamilyQuota(@Nullable ParallelPlanReservation reservation,
            IPatternDetails details, IPatternDetails.IInput input, @Nullable AEKey key,
            Map<Object, PlanAmount> familyDemandPerFiring, PlanAmount exactUnitsPerFiring, long cap
    ) {
        if (reservation == null || key == null || exactUnitsPerFiring.signum() <= 0L) {
            return cap;
        }
        PlanAmount quota = reservation.quotaExact(key, details, input);
        if (quota == null) {
            return cap;
        }
        PlanAmount familyDemand = familyDemandPerFiring
                .getOrDefault(key.getPrimaryKey(), PlanAmount.ZERO).add(exactUnitsPerFiring);
        if (familyDemand.signum() <= 0L) {
            return cap;
        }
        // 商在宽域上算完再夹一次 cap：cap 本来就是 long，夹不进的那一截由后续真扣料如实报缺。
        return Math.min(cap, Ae2Amounts.toLong(quota.divide(familyDemand)));
    }

    /**
     * 这一格递得出的候选：AE2 的"池子口径"，再补上<b>无限源的样板口径</b>与<b>池账口径</b>。
     *
     * <p>{@code CraftingCpuHelper.getValidItemTemplates} 的候选宇宙是
     * {@code inv.findFuzzyTemplates(what)}，也就是<b>池子里现存的键</b>。而无限源在提交侧就被刻意
     * 跳过（{@code tryExtractInitialItems} 遇无限键不向网络抽），池子里恒为 0 —— 于是样板明明写了
     * 候选，这一格却一个键都递不出来。实机形态就是 {@code ae2:creative_storage_cell x 1000000}
     * 那一跑：30 个任务 {@code pushed=0}，994 次尝试全记成 {@code nolist}。
     *
     * <p>所以无限键的候选回到样板声明的 {@code getPossibleInputs()}，过滤条件与 AE2 那半边的
     * {@code isValid} 逐字一致（这一拆法与 {@link #refillObligationBatch} 的"声明顺位 + isValid"
     * 同源：都是 {@code getValidItemTemplates} 的两半里<b>不依赖池子</b>的那一半）。
     * 合成项插在<b>它自己声明的那一号</b>，不追加到表尾：规划侧对模糊槽就是按声明顺位榨干靠前的
     * 模板再退下一个，两侧同序才不会把配给许诺给前一位的量改吃到后一位。
     *
     * <p>池子里已经落着的键不再合成第二份：同一键进候选表两次就是两条顺位。
     *
     * <h2>第三半：池账口径（第三方自定义键）</h2>
     * {@code findFuzzyTemplates} 只认 AE2 {@code KeyCounter} 的那本主索引，而它是
     * {@code Reference2ObjectMap}（实现是 {@code Reference2ObjectOpenHashMap}）——分格按
     * <b>{@code getPrimaryKey()} 的引用</b>（{@code appeng/api/stacks/KeyCounter.java:48}）。
     * {@code AEItemKey}/{@code AEFluidKey} 的主键
     * 是注册表里的 {@code Item}/{@code Fluid} 单例，引用恒等，所以这条缝在 AE2 自带的键上看不见。
     * 第三方自定义键（Mekanism 那型：自己的 {@code AEKeyType}、{@code equals} 只比资源本体）只要把
     * 主键存成<b>值对象</b>（注册名一类，{@code equals} 相等而引用不等——从 NBT／网络包重建出来就是
     * 这一型），同一枚池料就分两把尺子：池账按 {@code equals} 读得出量（停滞行 {@code pool=} 那一格
     * 走的正是 {@link #exactStored}），模糊索引按引用看不见 ⇒ 同一格里并排出"池里有 N 枚"与
     * "样板写了候选、池里一枚没有"（{@code nolist}），这一格永远递不出候选，万象样板那一发永远
     * 开不了工。
     *
     * <p>所以这一格<b>池账读出量</b>的声明候选必须递出去：判据与 {@code pool=} 那一格同源同调用，
     * 两处从此不可能互相矛盾。这一支不新增问网络的探针、不新增账本、不加闸门，也不改任何算式：
     * 只是把"这一格能不能伸手"问的那本池账换成唯一那一本。付款仍走池子自己的 {@code extract}，
     * 交不出整发照旧整批归位（{@link #prepareUniform} 那条不变式一字未动）——池账读不出量的键
     * 一个都不递，所以放开候选宇宙不等于凭空给料。
     *
     * <p><b>这一支覆盖面不止溢出那一档</b>：池账那两条腿（主表按引用、侧表按 {@code equals}）由
     * {@link #exactStored} 收在同一把尺上——读数前先按 {@code equals} 找到池子真装着的那一枚。
     * 池料塞得进 {@code long} 时没有侧表条目兜底，只有这一步能让"池里有 8 枚"读成 8 而不是 0。
     *
     * <p>顺位与无限源那一支同一条：插在<b>它自己声明的那一号</b>，且排在无限源合成之后
     * （无限源不经池子，认中了就不许被一截残池读数顶掉）。主键引用恒等的那两型（AE2 自带键）
     * 在这一支上必然已经被模糊那半边的同一键 {@code emitted} 掉——{@code exactStored} 读得出量
     * 就等价于主索引那格里躺着一枚 {@code equals} 相等的键，模糊那半边按 {@code equals} 认得到它，
     * 所以对 AE2 自带的键这里一条都不多递，老口径逐字不变。
     */
    private static List<InputTemplate> usableTemplates(
            ListCraftingInventory inventory, IPatternDetails.IInput input, @Nullable Level level,
            @Nullable ParallelPlanReservation reservation) {
        var templates = new ArrayList<InputTemplate>();
        var declared = input.getPossibleInputs();
        if (declared == null) {
            return templates;
        }
        // 池子那一半逐字保持 AE2 的口径：外层声明顺位、内层 findFuzzyTemplates、再过 isValid。
        // 这里不去重——同一键被两个声明项各命中一次时，两份模板量各是它自己声明的那一个数，
        // 撑不住第一份的槽还能退到第二份。
        var emitted = new HashSet<AEKey>();
        for (var stack : declared) {
            if (stack == null || stack.what() == null) {
                continue;
            }
            if (stack.amount() > 0L) {
                for (AEKey fuzz : inventory.findFuzzyTemplates(stack.what())) {
                    if (fuzz == null || !input.isValid(fuzz, level)) {
                        continue;
                    }
                    templates.add(new InputTemplate(fuzz, stack.amount()));
                    emitted.add(fuzz);
                }
            }
            AEKey self = stack.what();
            if (stack.amount() <= 0L || emitted.contains(self)) {
                continue;
            }
            boolean unbounded = reservation != null && reservation.isUnboundedSupply(self);
            // 不是无限源、而池账读得出这一枚的量的那一格：候选回到同一本池账（见方法注释）。
            // 判据先于 isValid：老口径里 reservation==null 那一支压根不问 isValid，这里不多问一次
            // （第三方 isValid 会解引用世界，level==null 那一型不许因本刀而多抛）。
            if (!unbounded && exactStored(inventory, self).signum() <= 0L) {
                continue;
            }
            if (!input.isValid(self, level)) {
                continue;
            }
            templates.add(new InputTemplate(unbounded ? self : resolvePoolKey(inventory, self),
                    stack.amount()));
            emitted.add(self);
        }
        return templates;
    }

    /**
     * 声明键在<b>容器账</b>里对应的那一枚精确键（池账与产出缓冲共用这一句——两边装的都是
     * {@link WideCraftingInventory}，分格的是同一本 {@code KeyCounter}）：主键引用与声明键不同
     * （第三方自定义键那一型）时，伸手要伸手的是容器真装着的那一个实例——{@code extract} 之后
     * AE2 的 {@code KeyCounter} 才会写回<b>同一个</b>分格，不会把同一份料拆进两个桶。
     *
     * <p>容器读不出量（真缺料那一型）时原样交回声明键，由调用方按"这一格递不出候选"记；
     * 容器里找不到同键时同样交回声明键（AE2 自带键走的就是这一支，逐字保持老口径）。
     *
     * <p><b>读现量与随后 {@code MODULATE} 摘账必须伸同一枚</b>：{@code ParallelCraftingCpuLogic} 的
     * 倒终点产物（{@code available <= 0} 即不倒）与实物回补（{@code waitingFor <= 0} 即
     * {@code EMPTY}）两脚都是"读完就要扣"——只把读数放开、扣的那一脚仍按声明键伸手，就是
     * 读得出 8 枚而一枚也扣不走 ⇒ 交给收货方的那一截仍躺在容器里，残池回网时<b>多出一份料</b>
     * （产出缓冲那一侧则是那一格永远清不掉，收工判据恒不成立）。
     * 全仓只有这一份解析，那两脚用读的也正是这一句。
     */
    static AEKey resolvePoolKey(ListCraftingInventory inventory, AEKey declared) {
        for (var entry : inventory.list) {
            if (entry != null && declared.equals(entry.getKey())) {
                return entry.getKey();
            }
        }
        return declared;
    }

    private static int countDistinctKeys(Iterable<InputTemplate> templates) {
        Set<AEKey> keys = new HashSet<>();
        for (InputTemplate template : templates) {
            if (template == null || template.key() == null) {
                continue;
            }
            keys.add(template.key());
        }
        return keys.size();
    }

    /**
     * 请求份数夹到"每发量 × 份数"仍可精确表示：产出与回流容器都是 AE2 的 {@code long}
     * 计数表，乘法饱和成 {@code Long.MAX_VALUE} 就是交出一个跟份数对不上的假数
     * （产出侧永远等不齐、或被当成已完成）。夹子是两支的交集（{@link #representableOutputCeiling}
     * ∩ {@link #representableInputCeiling}），与宽域申报侧共用这两个算式、各在全仓写一次。
     * 连除不先乘——每发量与倍率相乘本身就溢出。
     * 夹完至少留一发：一发都装不下的样板由后续提取判失败，不在这里偷偷放行。
     */
    static long clampToRepresentablePerFiringAmounts(IPatternDetails details, long requested) {
        BigInteger ceiling = representableOutputCeiling(details).min(representableInputCeiling(details));
        long limit = ceiling.compareTo(BigInteger.valueOf(requested)) >= 0
                ? requested : ceiling.longValue();
        return Math.max(1L, limit);
    }

    private static long multiplyOrMax(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long addOrMax(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /**
     * 这一发的<b>真</b>需几枚：倍率 × 模板量，宽域相乘，永不饱和。
     *
     * <p>{@link #multiplyOrMax} 的那份饱和值是"这一格已经超过一个天花板"的哨兵，
     * 只能拿来判存在，一旦进了减法与除法就把 1.8e19 枚读成了 9.2e18 枚。
     */
    private static BigInteger exactUnits(long multiplier, long templateAmount) {
        if (multiplier <= 0L || templateAmount <= 0L) {
            return BigInteger.ZERO;
        }
        return BigInteger.valueOf(multiplier).multiply(BigInteger.valueOf(templateAmount));
    }

    /**
     * 池子在该键上的<b>精确</b>现量：宽域池给真值，窄域池给它的 {@code long} 读数。
     *
     * <h2>主表那一腿也先按 {@code equals} 找到桶</h2>
     * {@code WideCraftingInventory#amount} 是两条腿拼出来的：主表 {@code list.get()} 与溢出侧表
     * {@code Map<AEKey,BigInteger>}。侧表是 {@code HashMap}（按 {@code equals}），而主表那一本
     * {@code KeyCounter} 的主索引是 {@code Reference2ObjectMap}
     * （{@code appeng/api/stacks/KeyCounter.java:48}，取数在 {@code :124-131}），分格按
     * {@code getPrimaryKey()} 的<b>引用</b>。第三方自定义键把主键存成<b>值对象</b>时，
     * {@code list.get(声明键)} 落在另一个桶里恒读 0：
     * <ul>
     *   <li>溢出那一档（池料 {@code >long}）还剩侧表那一截兜住，读数非 0；</li>
     *   <li><b>塞得进 {@code long} 那一档</b>（实机最常见）压根没有侧表条目 ⇒ 整条读口回 0，
     *       {@link #usableTemplates} 的第三支候选不触发，这一格又被判回"池里一枚没有"。</li>
     * </ul>
     * 所以这一句先把声明键换成<b>池账里真装着的那一枚</b>（{@link #resolvePoolKey}，唯一那一本池账
     * 的同一句调用：不新建第二本索引、不加缓存），主表与侧表从此落在<b>同一把尺、同一个分格</b>上。
     * 池账里没有同键时 {@code resolvePoolKey} 原样交回声明键，读数照旧是 0——放开读口不等于凭空给料。
     *
     * <p><b>伸手的键不是池子里那一份实例时，读池子就只许走这一句</b>——现存的调用点逐处点名：
     * <ul>
     *   <li>取料点的候选宇宙与首位候选（{@link #usableTemplates}、{@code primaryCandidate}）；</li>
     *   <li>递不出候选的三档归因与停滞行的 {@code pool=} 读数（{@code Probe} 那一格、
     *       {@code describeDeclaredCandidates}）；</li>
     *   <li>网络回补那条腿的池内现量（{@code refillObligationBatch}）与申报可付上界
     *       （{@code declaredPayableCeiling}）；</li>
     *   <li>折发数那条腿读环种留底那一刀（{@code ParallelCraftingCpuLogic#clampByRemainingQuota}）；</li>
     *   <li>NeoEco 销账那两腿——push 前的现量快照与 push 后的现量差
     *       （{@code ParallelCraftingCpuLogic#readPoolWide}／{@code #measurePoolOutflow}）；</li>
     *   <li>占料账本交出去的池子现量读口（{@code ParallelCraftingCpuLogic#pendingPoolClaims} 递给
     *       {@code BatchObligationLedger#seedRemaining} 的那一个函数）；</li>
     *   <li>两处<b>诊断读数</b>的 {@code 池内=} 那一格（{@code ParallelCraftingCpuLogic#describeBlockedObligation}
     *       的 {@code blocked=}／{@code StallProbe#describeBackoffBlind} 的 {@code backoffBlind=}）——
     *       它们不参与判据，但会被发回来当排障证据：按声明键裸读报 0 就是把"池里还剩 8 枚"写成
     *       "池里一枚没有"，读数比没读数更坏。</li>
     *   <li>倒终点产物与实物回补那两脚（{@code ParallelCraftingCpuLogic#flushDyeableRecursiveFinalOutput}
     *       ／{@code #insertInternal} 问产出缓冲还欠几枚）：它们<b>进判据</b>且读完就要在同一本账上扣，
     *       所以伸的是{@link #resolvePoolKey} 那一句解析而不是这一句读数——读与扣必须同一枚，
     *       理由见那一句的注释。</li>
     * </ul>
     * 这几处伸手的键出自宿主自报清单与样板候选表，压根不是池子里那一份实例；裸读就是把"池里有没有
     * 这一格"交给 AE2 那本按引用分格的主表单独说了算。
     *
     * <p><b>剩下几处 {@code WideCraftingInventory.amount} 不经过这一句，各有其故</b>：
     * 遍历池子自身键集的那些读数（{@code returnMaterialsToNetwork}、状态栏 {@code getAllItems}、
     * 诊断快照 {@code snapshotPoolExact}）拿的本来就是池账真装着的那一枚，套一层找桶只会每格再全池
     * 扫一遍（同 {@link #exactFamilyStored} 里那条理由）。读快照 {@code Map<AEKey,…>}（{@code LinkedHashMap}，
     * 按 {@code equals}）的那些探针格也不在这一句上：桶本来就是池子自己那一枚。
     */
    static BigInteger exactStored(ListCraftingInventory inventory, @Nullable AEKey key) {
        if (key == null) {
            return BigInteger.ZERO;
        }
        return WideCraftingInventory.amount(inventory, resolvePoolKey(inventory, key));
    }

    /** 宽域量交回 {@code long} 口径：越界即钉在天花板（只给哨兵读数用）。 */
    private static long saturate(BigInteger exact) {
        if (exact == null || exact.signum() <= 0L) {
            return 0L;
        }
        return exact.min(WideCeilings.LONG_ACCOUNT).longValue();
    }
}
