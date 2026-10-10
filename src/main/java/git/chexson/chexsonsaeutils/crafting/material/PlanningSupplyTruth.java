package git.chexson.chexsonsaeutils.crafting.material;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.StorageCell;
import git.chexson.chexsonsaeutils.cell.WideSupplySource;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;

/**
 * 规划侧只读的「真值供给」快照：把网络逐键 {@code long} 读数里<b>属于我们无限磁盘溢出键</b>
 * 的那一截换成 {@link BigInteger} 真值，其余键的读数逐字不变。
 *
 * <p>要解决的问题：{@code InfinityCellStore} 的 {@code CellData#visibleAmount} 对溢出键报
 * {@code Long.MAX_VALUE}（AE2 的 {@code KeyCounter}/{@code MEStorage} 只能装 {@code long}，
 * 这是接口事实，不动它）。于是规划侧从 {@code getCachedInventory()} /
 * {@code NetworkCraftingSimulationState} 那条链读到的库存永远看不见 &gt; long 的一截。
 * 本快照只提供「池子里真有那么多」这一事实。
 *
 * <p>它<b>不</b>放宽任何一批的上界：单批份数仍受
 * {@code ParallelBatchExtraction#clampToRepresentablePerFiringAmounts} 与
 * {@code representableOutputCeiling}（按每发产出量）夹住，&gt; long 的用量只能靠跨批推进。
 * 把真值喂进求解侧的逐键库存账（{@code CraftGraph.stock} / {@code denseStock} /
 * {@code reservableStock} / 容量上界）是下一刀的事。
 *
 * <p>口径（三条不变式）：
 * <ul>
 * <li><b>只在规划侧生效</b>：纯读，不进网络聚合、不改 AE2 对其他 mod 的读数；</li>
 * <li><b>只碰聚合读数不可信的键</b>（台账 {@code 197} 换尺判据，算术只写在
 * {@link #aggregateReadingUnderstates} 一处）：AE2 的聚合是普通 {@code long} 加法
 * （{@code KeyCounter.add → VariantCounter.add → fastutil addTo}），回绕且不饱和——
 * 两片各 {@code > Long.MAX/2} 的库存相加得到的是<b>负数</b>，三片各 {@code Long.MAX} 相加
 * 回绕成 {@code Long.MAX_VALUE-2} 这样的正数。旧口径只认「恰等哨兵 {@code Long.MAX_VALUE}」，
 * 其余回绕值被逐字当本数种进沙盒。现在三态准入：恰等哨兵、回绕成负、逐存储可见和越出聚合读数。
 * 普通键（真实和装得进 {@code long}、没有回绕）聚合读数与逐存储和一致，一个都不换尺，
 * 读数逐字不变——所以普通存储与别的 mod 的 cell 一律不受影响；</li>
 * <li><b>宁可少报</b>：换尺用的是<b>逐存储网络可见读数</b>的和（挂载视图，含驱动/舱的配置过滤），
 * 聚合读数里归属不到任何可枚举存储的余额算成负数（回绕）即按 0 起算，
 * 因此结果恒为真供给的下界，方向与今日一致，绝不假通过。</li>
 * </ul>
 *
 * <p>归属做法：无限盘只能挂在驱动器/存储舱的单元槽里，而 {@link IChestOrDrive}
 * 是网格节点的 owner，{@link IChestOrDrive#getOriginalCellInventory(int)} 交回的正是
 * 我们自己那份 {@code StorageCell}（{@link WideSupplySource}）。于是"这一网络看到的饱和量
 * 里有多少是我们的盘"有确定答案，不需要猜，也不需要碰 AE2 内部字段。逐存储可见读数走
 * {@link IChestOrDrive#getCellInventory(int)}（网络挂载的那份视图）——它与聚合读数同一条
 * 过滤口径，锁了提取的单元不会在这一层混进供给。
 *
 * <p><b>调用方注意</b>：本快照交回的 {@link PlanAmount} 与网络那份 {@code long} 读数
 * <b>不在一个数域</b>。判定要一律走 {@link PlanAmount}（{@code supplyOf} 未命中宽域键时
 * 已经把 {@code long} 读数升成 {@link PlanAmount}），不得写 {@code wideAmount > networkReading}
 * 这种混比——{@code long} 读数在饱和键上恒等于 {@code Long.MAX_VALUE}，混比会把
 * "被截断" 读成 "够用"，方向正好反了。同理：不要拿 {@code PlanAmount#longValue} 之外的
 * 窄化出口回灌任何按 {@code long} 计数的账。
 */
public final class PlanningSupplyTruth {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 常态：网格为空，或聚合读数逐键可信（无哨兵、无回绕迹象），退回纯 {@code long} 读数。 */
    public static final PlanningSupplyTruth EMPTY = new PlanningSupplyTruth(Map.of(), 0);

    /**
     * 无限源的记账替身：{@code 2^126} 枚。
     *
     * <p>为什么不是 {@code Long.MAX_VALUE}：需求可以 &gt; long（实机形态 4.5e20 枚熔岩），
     * 而"能不能付这一口"的探询最多只能问 {@code Long.MAX} 一次 ⇒ 无限源也永远答不够 ⇒
     * 缺口永远清不掉 ⇒ 计划恒被标成"材料不足"、不许提交（这正是本类要修的那条链）。
     * 任何可表达的请求都远小于这个替身，同时它仍是"有限数"，不引入无穷算术的分支。
     *
     * <p>与 {@code LinearPlanner.CapacityUpperBound#UNBOUNDED}（{@code 2^256}）是两件事，故意留两份：
     * 这一份是<b>供给量替身</b>，要参加供需比较与减法（{@code MaterialClosureGate} 的 headroom
     * 就是拿它减去已用量），量级只要高于任何可表达的请求即可；那一份是<b>产能上界标志</b>，
     * 读侧只走 {@code isUnbounded} 那一句、它自己的注释明写不许拿它参加"够不够"的比较，
     * 量级要高于任何一次真实乘算。一个是量、一个是标志，合到一处必得改掉另一侧的判据——故不合。
     */
    static final PlanAmount UNBOUNDED = PlanAmount.of(new BigInteger("85070591730234615865843651857942052864"));

    /** 只收录「真值 &gt; 网络 {@code long} 读数」的键；未收录即读数不变。 */
    private final Map<AEKey, PlanAmount> wideSupplies;
    /** 无限源覆盖的键（AE2 创造盘这类"报 {@code Integer.MAX_VALUE}、取多少给多少"）。 */
    private final java.util.Set<AEKey> unboundedSupplies;
    /** 本次归属扫描见到的宽域供给源个数（诊断用：区分「没有盘」与「盘没溢出」）。 */
    private final int wideSourceCount;
    /**
     * 本次扫描真伸到的<b>宿主形态数</b>与<b>单元格数</b>（诊断用纯计数，不是第二本账）：
     * {@link #scanNetworkCells} 只认 {@link IChestOrDrive} 这一种宿主，{@code StorageBusPart}
     * （{@code IStorageProvider} ＋ {@code ExternalStorageFacade}）、任意节点 owner 的
     * {@code IStorageProvider}、{@code IStorageService#addGlobalStorageProvider} 那三型网络存量
     * <b>天生不在这一本里</b>。这两个数与 {@link #tallyKeyCount} 一起就是覆盖面：
     * {@code cells>0} 而这一枚键不在 {@code tally} 里 ⇒「扫描看不见」；{@code cells=0} ⇒「压根没伸到」。
     */
    private final int scannedHostCount;
    private final int scannedCellCount;
    /** 逐存储网络可见读数那一本账里登记过的键数（覆盖面读数，不另算一份）。 */
    private final int tallyKeyCount;

    private PlanningSupplyTruth(Map<AEKey, PlanAmount> wideSupplies, int wideSourceCount) {
        this(wideSupplies, wideSourceCount, java.util.Set.of(), 0, 0, 0);
    }

    private PlanningSupplyTruth(Map<AEKey, PlanAmount> wideSupplies, int wideSourceCount,
            java.util.Set<AEKey> unboundedSupplies, int scannedHostCount, int scannedCellCount,
            int tallyKeyCount) {
        this.wideSupplies = wideSupplies;
        this.wideSourceCount = wideSourceCount;
        this.unboundedSupplies = unboundedSupplies;
        this.scannedHostCount = scannedHostCount;
        this.scannedCellCount = scannedCellCount;
        this.tallyKeyCount = tallyKeyCount;
    }

    /** 仅使用明确声明的生成能力；报量和提取量的差异不能证明无限供给。 */
    public static java.util.Set<AEKey> unboundedKeysOf(MEStorage cell) {
        if (!(cell instanceof git.chexson.chexsonsaeutils.api.storage.UnboundedStorageSupply source))
            return java.util.Set.of();
        var result = new java.util.HashSet<AEKey>();
        for (AEKey key : source.unboundedSupplyKeys()) {
            if (cell.extract(key, 1, appeng.api.config.Actionable.SIMULATE,
                    appeng.api.networking.security.IActionSource.empty()) == 1) result.add(key);
        }
        return java.util.Set.copyOf(result);
    }

    /** 原生网络重载作业时重新核对实际在线单元及其可见挂载，不依赖聚合报量。 */
    public static java.util.Set<AEKey> currentUnboundedSupplies(IGrid grid) {
        return java.util.Set.copyOf(scanNetworkCells(grid).unbounded());
    }

    /**
     * 已归属完成的真值表直接成快照：给<b>非网格</b>来源（离线审计、判据用例）一条与
     * {@link #capture} 同构的入口。表里只该放「真值 &gt; 网络 long 读数」的键，空表退回 {@link #EMPTY}。
     *
     * <p>这一条路没有网格可扫，覆盖面三个读数一律 0：它交的表<b>不是</b>扫描来的，
     * {@link #scannedCellCount()} 之类在此恒为 0 是事实，不是缺陷。
     */
    public static PlanningSupplyTruth of(Map<AEKey, PlanAmount> wideSupplies) {
        var copy = Map.copyOf(wideSupplies);
        return copy.isEmpty() ? EMPTY : new PlanningSupplyTruth(copy, copy.size());
    }
    /**
     * 多目标只读预览在同一来源库存上扣除已模拟占用后，替换有限供给数量。
     * 无限源标记及归属诊断保持原样，不能把无限源转换成有限的数值替身。
     */
    public PlanningSupplyTruth withRemaining(Map<AEKey, PlanAmount> remaining) {
        var supplies = new java.util.HashMap<>(this.wideSupplies);
        remaining.forEach((key, amount) -> {
            if (amount.compareTo(PlanAmount.ZERO) < 0) throw new IllegalArgumentException("预览供给不能为负");
            if (!this.unboundedSupplies.contains(key)) supplies.put(key, amount);
        });
        return new PlanningSupplyTruth(Map.copyOf(supplies), this.wideSourceCount, this.unboundedSupplies,
                this.scannedHostCount, this.scannedCellCount, this.tallyKeyCount);
    }

    /**
     * 从一次规划请求的库存快照读出真值供给。
     *
     * @param grid         本次规划所属网格；{@code null}（离线/无网格）直接退回 {@link #EMPTY}
     * @param networkStock 规划侧读到的逐键 {@code long} 库存快照（AE2 聚合口径，会回绕）
     */
    public static PlanningSupplyTruth capture(@Nullable IGrid grid, @Nullable KeyCounter networkStock) {
        return capture(grid, networkStock, null);
    }

    /** 规划入口已握有实际网络库存时，优先使用完整精确挂载快照；原生库存沿用单元扫描。 */
    public static PlanningSupplyTruth capture(@Nullable IGrid grid, @Nullable KeyCounter networkStock,
            @Nullable MEStorage storage) {
        if (networkStock == null) return EMPTY;
        if (storage instanceof git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply exact) {
            var snapshot = exact.readExactSupply();
            var wide = new java.util.HashMap<AEKey, PlanAmount>();
            snapshot.finite().forEach((key, amount) -> {
                if (!amount.equals(BigInteger.valueOf(networkStock.get(key)))) wide.put(key, PlanAmount.of(amount));
            });
            snapshot.unbounded().forEach(key -> wide.put(key, UNBOUNDED));
            return new PlanningSupplyTruth(Map.copyOf(wide), 1, snapshot.unbounded(), 0, 0,
                    snapshot.finite().size() + snapshot.unbounded().size());
        }
        if (grid == null || networkStock.isEmpty()) return EMPTY;
        // 无限源与"报没报满 Long.MAX"无关：AE2 创造盘只报 Integer.MAX_VALUE，读数正常得看不出问题，
        // 来源必须明确声明生成能力。先收集其配置键——否则需求 >long 时，一次 ≤long 的 SIMULATE
        // 探询永远答不够（无限源一次也最多给 Long.MAX），缺口清不掉，计划恒被判"材料不足"不许提交。
        // 同一趟扫描顺带把逐存储的网络可见读数按键加进宽域计数器（#197 的回绕判据要吃这份和）。
        var scan = scanNetworkCells(grid);
        var unbounded = scan.unbounded();
        if (!unbounded.isEmpty()) {
            LOGGER.info("Graph planning unbounded supply sources: keys={} hosts={} cells={} {}",
                    unbounded.size(), scan.hosts(), scan.cells(), unbounded);
        }
        // 换尺准入（台账 197）：聚合读数不可信才算嫌疑——恰等哨兵、回绕成负、或逐存储可见和越出聚合。
        // 一个嫌疑键都没有就没有必要做归属（常态零成本；扫描本身只复用无限源探询那趟既有遍历）。
        List<AEKey> suspect = null;
        // 逐存储那本账里一个字都没有的嫌疑键数：这一枚是「本扫描看不见」，与「网络真没有」不是一句话
        int blindKeys = 0;
        for (var entry : networkStock) {
            var seen = scan.perStorageSum().getIfPresent(entry.getKey());
            if (aggregateReadingUnderstates(entry.getLongValue(), seen)) {
                if (suspect == null) {
                    suspect = new ArrayList<>(4);
                }
                suspect.add(entry.getKey());
                if (seen == null) {
                    blindKeys++;   // 这一枚在逐存储可见读数里缺席：这一趟扫描对它没有覆盖面
                }
            }
        }
        Map<AEKey, PlanAmount> wide = null;
        int sources = scan.wideSources().size();
        // 覆盖面读数直接取现成的那一本账，不另算一份
        int tallyKeys = scan.perStorageSum().size();
        if (suspect != null) {
            if (sources == 0) {
                // 网络上有嫌疑键、网格上却没有一份"会报真值的我们的单元"。这两种情形必须分开写清楚，
                // 否则"计划看不见 >long 那一截"就只能靠猜：玩家的 >long 料存在别家的无限盘里
                // （neoecoae 的 ECOCreativeCell 压根不存数量、extendedae_plus 的宽域表是私有），
                // 真值确实无从捞回，供需只能按聚合读数算。
                LOGGER.warn("Graph planning wide supply truth idle: saturatedKeys={} sources=0"
                                + " scannedHosts={} scannedCells={} tallyKeys={} blindKeys={}"
                                + " —— 饱和键没有一个落在我们的宽域单元上，>long 的一截看不见"
                                + "（本扫描只认 IChestOrDrive 宿主形态：存储总线的 IStorageProvider／"
                                + "节点 owner 的 IStorageProvider／addGlobalStorageProvider 那三型的存量"
                                + "天生不在覆盖面内，看不见就少报，不多报；"
                                + "scannedCells>0 而 blindKeys>0 是「伸到了、这一枚数不着」，"
                                + "scannedCells=0 才是「压根没伸到」）",
                        suspect.size(), scan.hosts(), scan.cells(), tallyKeys, blindKeys);
            }
            for (AEKey key : suspect) {
                var ourExact = BigInteger.ZERO;
                var ourReported = BigInteger.ZERO;
                for (var source : scan.wideSources()) {
                    ourExact = ourExact.add(source.exactAmount(key));
                    ourReported = ourReported.add(BigInteger.valueOf(source.reportedAmount(key)));
                }
                var aggregate = BigInteger.valueOf(networkStock.get(key));
                // 逐存储网络可见和＝本数：AE2 聚合本就是把它加出来的，它比聚合大，聚合就是回绕残渣。
                var visible = scan.perStorageSum().containsKey(key)
                        ? scan.perStorageSum().getExact(key) : BigInteger.ZERO;
                // 我们单元上 >long 的那一截只有旁路口能捞回（网络可见读数恒被饱和成 long 口径）
                var overflowTail = ourExact.subtract(ourReported);
                // 聚合读数里归属不到可枚举存储的余额（别家挂载/隐藏存储）：算成负数（聚合回绕）按 0，只少报不多报
                var otherFloor = aggregate.subtract(visible);
                var supply = visible
                        .add(overflowTail.signum() > 0 ? overflowTail : BigInteger.ZERO)
                        .add(otherFloor.signum() > 0 ? otherFloor : BigInteger.ZERO);
                if (supply.compareTo(aggregate) <= 0) {
                    continue; // 聚合读数在本键上不比逐存储和小（含别家盘报满 long 的形状）：读数逐字不变
                }
                if (wide == null) {
                    wide = new LinkedHashMap<>();
                }
                wide.put(key, PlanAmount.of(supply));
            }
        }
        if (wide == null && unbounded.isEmpty()) {
            // 一个宽域键都没有时仍要分清"扫到了我们的盘但这张没溢出"（sources>0 且有过嫌疑键，带计数回去）
            // 与"网格上压根没有我们的盘"（退回 EMPTY 单例，调用方按实例比对的判据不变）。
            // 走这条路确实扫过网格：覆盖面三个读数交真数，不许报 0 假装没伸到。
            return sources > 0 && suspect != null
                    ? new PlanningSupplyTruth(Map.of(), sources, java.util.Set.of(),
                            scan.hosts(), scan.cells(), tallyKeys)
                    : EMPTY;
        }
        return new PlanningSupplyTruth(wide == null ? Map.of() : wide, sources, unbounded,
                scan.hosts(), scan.cells(), tallyKeys);
    }

    /**
     * 聚合 {@code long} 读数可不可信——<b>回绕算术全仓库只写在这一处</b>（台账 {@code 197}）。
     *
     * <p>AE2 的整条聚合链是普通 long 加法（{@code KeyCounter.add → VariantCounter.add:35 →
     * AEKey2LongMap.addTo}（fastutil {@code Object2LongOpenHashMap}）；逐存储累加点
     * {@code MEInventoryHandler:119}、{@code NetworkStorage:211-215}、{@code CompositeStorage:117-119}），
     * 回绕且不饱和：两片各 {@code > Long.MAX/2} 的正数相加，和落在 {@code (Long.MAX, 2^64-2]}，
     * 按有符号读必然回绕成<b>负数</b>；三片及以上还能再绕回 {@code Long.MAX_VALUE-2} 这类
     * 恰不等哨兵的巨大正数——旧口径「恰等哨兵」抓不住它，于是回绕值被逐字当本数种进沙盒。
     *
     * <p>三态准入：恰等哨兵（饱和）；回绕成负（网络库存不会是负数）；逐存储可见和 &gt; 聚合
     * （两片正数相加不可能让真和变小，比聚合小只可能是回绕残渣被读大了，方向恰好相反）。
     */
    static boolean aggregateReadingUnderstates(long aggregate, @Nullable PlanAmount perStorageSum) {
        if (aggregate == Long.MAX_VALUE || aggregate < 0L) {
            return true;
        }
        return perStorageSum != null && perStorageSum.compareTo(PlanAmount.of(aggregate)) > 0;
    }

    /**
     * 一次宿主单元扫描的五件收获：无限源键、我们的宽域单元清单、逐存储网络可见读数之和，
     * 外加两件<b>覆盖面纯计数</b>（宿主形态数 / 单元格数）——它们是数出来的，不是第二本账。
     */
    private record NetworkCellScan(java.util.Set<AEKey> unbounded, List<WideSupplySource> wideSources,
            WideKeyCounter perStorageSum, int hosts, int cells) {
    }

    /**
     * 走一遍网格节点的 owner（宿主只有驱动器与存储舱两种形态）：无限源按明确能力认、
     * 宽域单元按接口认，逐存储的网络可见读数（{@link IChestOrDrive#getCellInventory(int)}
     * 那份挂载视图，含配置过滤，与聚合读数同口径）按键加进 {@link WideKeyCounter}——
     * 它是 AEKey 账里现成的宽域计数器（long 快路径、越界自动进 BigInteger 侧表、绝不回绕），
     * 逐存储相加因此永远不会复现聚合自己那条回绕腿。
     */
    private static NetworkCellScan scanNetworkCells(IGrid grid) {
        java.util.Set<AEKey> unbounded = null;
        List<WideSupplySource> sources = null;
        var perStorageSum = new WideKeyCounter();
        // 覆盖面两笔纯计数：宿主那一层加一次、单元那一层加一次（放错层就把一个数当两个数报）
        int hosts = 0;
        int cells = 0;
        for (IGridNode node : grid.getNodes()) {
            if (!(node.getOwner() instanceof IChestOrDrive host) || !host.isPowered()) {
                continue; // 离线宿主的库存本来就不在网络里
            }
            hosts++;
            for (int slot = 0, slots = host.getCellCount(); slot < slots; slot++) {
                StorageCell cell = host.getOriginalCellInventory(slot);
                if (cell == null) {
                    continue;
                }
                cells++;
                var keys = unboundedKeysOf(cell);
                if (cell instanceof WideSupplySource source) {
                    if (sources == null) {
                        sources = new ArrayList<>(4);
                    }
                    sources.add(source);
                }
                MEStorage visible = null;
                try {
                    visible = host.getCellInventory(slot);
                } catch (RuntimeException failure) {
                    LOGGER.info("Graph planning per-storage tally skipped: host slot {} —— {}",
                            slot, failure);
                }
                if (visible == null) {
                    continue; // 读不到网络可见视图就是这一份不计进逐存储和：和只会偏小，方向保守
                }
                for (AEKey key : keys) {
                    if (visible.extract(key, 1, appeng.api.config.Actionable.SIMULATE,
                            appeng.api.networking.security.IActionSource.empty()) == 1) {
                        if (unbounded == null) unbounded = new java.util.HashSet<>();
                        unbounded.add(key);
                    }
                }
                try {
                    var reading = new KeyCounter();
                    visible.getAvailableStacks(reading);
                    for (var entry : reading) {
                        long amount = entry.getLongValue();
                        if (amount != 0L) {
                            perStorageSum.add(entry.getKey(), amount);
                        }
                    }
                } catch (RuntimeException failure) {
                    // 读一份存储绝不打断规划：少一份和就是更保守的下界
                    LOGGER.info("Graph planning per-storage reading skipped: slot {} —— {}",
                            slot, failure);
                }
            }
        }
        return new NetworkCellScan(unbounded == null ? java.util.Set.of() : unbounded,
                sources == null ? List.of() : sources, perStorageSum, hosts, cells);
    }

    /** 该键的供给真值：无限源给替身、溢出键给 BigInteger 真值，其余逐字退回网络的 {@code long} 读数。 */
    public PlanAmount supplyOf(AEKey key, long networkReading) {
        if (key != null && this.unboundedSupplies.contains(key)) {
            return UNBOUNDED;
        }
        var wide = this.wideSupplies.get(key);
        return wide != null ? wide : PlanAmount.of(networkReading);
    }

    /** 该键是否被换成了逐存储宽域真值；未收录返回 {@code null}（调用方按网络读数走）。 */
    public @Nullable PlanAmount wideSupply(AEKey key) {
        return this.wideSupplies.get(key);
    }

    /** 只读的宽域供给键表（诊断/审计用；调用方不得写）。 */
    public Map<AEKey, PlanAmount> wideSupplies() {
        return this.wideSupplies;
    }

    public boolean isEmpty() {
        return this.wideSupplies.isEmpty() && this.unboundedSupplies.isEmpty();
    }

    /** 无限源覆盖的键数（诊断：区分"没有无限盘"与"有但没被认出来"）。 */
    public int unboundedKeyCount() {
        return this.unboundedSupplies.size();
    }

    /**
     * 无限源键集的一次性快照（不可变）：随计划出门给执行侧，那里既不夹配额也不走提取。
     *
     * <p>AE2 创造元件、ExtendedAE（含 KubeJS）和 Cell4 由来源能力声明生成键，
     * NeoEco 精确来源也可明确声明无限供给。容量、饱和数值和提取量差异不参与身份判定。
     * 当前挂载不可见或不能提取的资源不加入快照。
     */
    public java.util.Set<AEKey> unboundedSupplies() {
        return java.util.Set.copyOf(this.unboundedSupplies);
    }

    public int wideKeyCount() {
        return this.wideSupplies.size();
    }

    public int wideSourceCount() {
        return this.wideSourceCount;
    }

    /**
     * 覆盖面读数（三件，纯读数）：本次扫描真伸到的<b>宿主形态数</b>。
     * <b>不参与任何判据</b>——准入、归属、下界一律不读它，它只回答"这一趟扫到了哪儿"。
     */
    public int scannedHostCount() {
        return this.scannedHostCount;
    }

    /** 覆盖面读数（纯读数，不参与任何判据）：本次扫描真伸到的<b>单元格数</b>。 */
    public int scannedCellCount() {
        return this.scannedCellCount;
    }

    /**
     * 覆盖面读数（纯读数，不参与任何判据）：逐存储网络可见读数那一本账里登记过的<b>键数</b>。
     *
     * <p>它与上面两件一起把"看不见"拆成两句话：{@code scannedCellCount()>0} 而某枚键不在账上
     * ＝扫描伸到了、数不着这一枚；{@code scannedCellCount()==0} ＝压根没伸到。
     * {@link #of(java.util.Map)} 那条路没有网格可扫，三个读数恒为 0。
     */
    public int tallyKeyCount() {
        return this.tallyKeyCount;
    }
}
