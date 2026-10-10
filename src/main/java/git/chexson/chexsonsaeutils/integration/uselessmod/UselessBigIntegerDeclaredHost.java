package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.crafting.IPatternDetails;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalCarrier;
import appeng.api.networking.IGrid;
import appeng.api.stacks.KeyCounter;
import appeng.hooks.ticking.TickHandler;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchPlannedOutputs;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.List;

/**
 * 把 useless 的 BigInteger 批次通道<b>伪装</b>成一颗 {@link DeclaredBatchHost}，让 CPU 的
 * 「单发原型材料 + 份数声明」那条既有账路（{@code ParallelCraftingCpuLogic#attemptDeclaredBatch}）
 * 原样复用：申报份数、义务账、销账、挂账出口全都只有一套，这里不建第二套账。
 *
 * <p>它不是网格里的 provider（收料的是机器，本壳只转推送），所以 {@code ICraftingProvider} 那三个
 * 方法一律给空实现。网格退避账按对象引用认人，故 CPU 侧按真宿主身份缓存本壳：一台机器一颗。
 *
 * <p>{@link #declaredBatchFiringsUnbounded()} 报 {@code true}：这条通道的份数就是
 * {@link BigInteger}，宿主侧没有数值上界，真实上限由机器在 {@code capacity}/{@code admit} 那一刻
 * 动态给（材料窗口配置 / AIMD 分段预算 / 能量 / 回网背压）。CPU 发数和产出账均保持
 * BigInteger 真值；材料现货、实际配额及尚未结清的义务仍限制接单。
 *
 * <p>单机高级合金炉拿不到本壳的去路：{@link #channelAvailable} 要求真宿主本身是
 * {@code AlloyFurnaceBigIntegerProvider}，而单机炉只有 {@code cpuBinding=null} 那条入口
 * （产物只写回网络、不给 CPU 回执），它不是 provider。
 */
public final class UselessBigIntegerDeclaredHost
        implements DeclaredBatchHost, DeclaredBatchPlannedOutputs, DeclaredBatchTerminalCarrier {

    /** 网格里的真宿主（useless 的合金炉方块实体）：只用来判它是不是 bigint provider。 */
    private final Object host;
    private final @Nullable IGrid grid;

    /** 本 tick 查到的机器清单：同一 tick 内逐张样板、探测与推送都只走一次网格。 */
    private long machinesTick = Long.MIN_VALUE;
    private @Nullable List<UselessBigIntegerBatchClient.Machine> machines;
    /** 本批宿主自报的<b>计划</b>入网产出（预期，不是入网证据）；{@link #takePlannedNetworkOutputs} 取走即清。 */
    private @Nullable git.chexson.chexsonsaeutils.bigint.WideKeyCounter lastPlannedNetworkOutputs;
    /**
     * 上一次真收下那一批的 {@code cpuToken}：CPU 侧靠它挂 {@code 在飞→终局} 那本台账
     * （{@link DeclaredBatchTerminalCarrier}）。<b>每次推送开头一律清空</b>——留着上一批的 token
     * 就会把上一批的终局记到这一批头上，与 {@link #lastPlannedNetworkOutputs} 同一条取走即清口径。
     */
    private @Nullable UselessBigIntegerReceipt lastAcceptedBatchReceipt;

    public UselessBigIntegerDeclaredHost(Object host, @Nullable IGrid grid) {
        this.host = host;
        this.grid = grid;
    }

    /**
     * 这道批次现在能不能走 bigint 通道：装了 useless 且通道在场、真宿主是 bigint provider、
     * 这份样板在通道里、CPU 的网格里查得到接这通道的机器。
     *
     * <p>接单的是查到的那台机器，「预期入网」产出也按那台机器自报的 {@code plannedOutputs} 记，
     * 账跟着真正收料的机器走。
     *
     * <p>{@code false} 即调用方逐字退回既有的 {@code ScaledProcessingPattern} 折叠路径 ——
     * 行为与接这条通道之前一字不差。
     */
    public boolean channelAvailable(@Nullable IPatternDetails pattern) {
        if (!UselessBigIntegerBatchClient.isBigIntegerProvider(host)
                || !UselessBigIntegerBatchClient.acceptsPatternKind(pattern)) {
            return false;
        }
        return !machinesThisTick().isEmpty();
    }

    @Override
    public boolean supportsDeclaredBatch(IPatternDetails pattern) {
        // 接管与否以调用方 channelAvailable 的判定为准：本壳到手时通道已经可用
        return true;
    }

    /** 这条通道的份数是 {@link BigInteger}：宿主侧没有数值上界，真上限在机器接单那一刻动态给。 */
    @Override
    public boolean declaredBatchFiringsUnbounded() {
        return true;
    }

    /** 本 tick 上一次整批拒收时机器给回的可辨原因（纯读数，随 {@link #pushDeclaredBatch} 刷新）。 */
    private @Nullable String lastRejectReason;

    @Override
    public BigInteger pushDeclaredBatch(IPatternDetails pattern, KeyCounter[] unitPrototype,
            BigInteger declaredExecutions) {
        lastPlannedNetworkOutputs = null;
        lastAcceptedBatchReceipt = null;
        var outcome = UselessBigIntegerBatchClient.dispatchOutcome(grid, pattern, unitPrototype,
                declaredExecutions, ignored -> machinesForReadout());
        var result = outcome.result();
        if (result == null || result.accepted() == null || result.accepted().signum() <= 0) {
            // 没有机器接单 / 整批被拒：机器一分料没吃，CPU 按「整批拒收」把原型料原数回池。
            // 是哪个出口拒的（throttled／capacity／admit-null／commit-false-noTake／
            // commit-false-tookInput…）随这句读数带出去，
            // 由 CPU 侧的 declaredReject 那一格点名——过去这里只有"拒了"一个字。
            lastRejectReason = outcome.rejectDetail();
            return BigInteger.ZERO;
        }
        lastRejectReason = null;
        lastPlannedNetworkOutputs = result.expectedToNetwork();
        // 这一枚 token 就是该笔批次的终局凭据载体：机器若在 commit 那层栈帧里报了 batchId，
        // CPU 侧才挂得上在飞记录（桥侧 onBatchAdmitted 记的，见 UselessBigIntegerApiBridge）。
        lastAcceptedBatchReceipt = result.receipt();
        return result.accepted();
    }

    /**
     * 上一次推送<b>真正递给客户端</b>的那一串机器（<b>纯读数交接</b>，不参与任何判据）：
     * {@code queuePending[useless侧]=} 那一格就地读它，<b>不新开一次网格扫描</b>——
     * 推送那一刻已经握过这台机器，读数问的必须是同一台，否则印出来的队列深度属于另一台机器。
     *
     * <p>只在 {@link #pushDeclaredBatch} 真的问过机器之后才有值：没推过（{@code null}）时下游
     * 那一格如实印 {@code ?} 并把覆盖面报成 {@code total=0}，不拿空表冒充"这轮碰过机器"。
     */
    private @Nullable List<UselessBigIntegerBatchClient.Machine> lastPushedMachines;

    /** 上一次推送递给客户端的那一串机器；没推过就是 {@code null}（读数口径，见 {@link #lastPushedMachines}）。 */
    public @Nullable List<UselessBigIntegerBatchClient.Machine> lastPushedMachines() {
        return lastPushedMachines;
    }

    /**
     * {@link #machinesThisTick()} 那一层缓存的<b>带读数交接</b>版：把同一条链查到的机器原样递给
     * 客户端，并顺手记下"这一批推送碰到过哪几台"，供 {@code queuePending=} 那一格回读。
     * 判据、接单顺序、退回折叠的行为与直接交 {@link #machinesThisTick()} 逐字相同。
     */
    private List<UselessBigIntegerBatchClient.Machine> machinesForReadout() {
        var found = machinesThisTick();
        lastPushedMachines = found;
        return found;
    }

    @Override
    public @Nullable UselessBigIntegerReceipt lastAcceptedBatchReceipt() {
        return lastAcceptedBatchReceipt;
    }

    @Override
    public @Nullable String lastDeclaredBatchRejectReason() {
        return lastRejectReason;
    }

    /**
     * 退避账记在<b>真宿主</b>身上：本壳不是网格 provider，{@code ParallelCpuProviderBackoff
     * #checkProvider} 查询用的永远是真方块实体那一个身份，记在壳上就永久空转
     * （实机 {@code back=0} 恒零即此形态）。壳只在调用方当路由用。
     */
    @Override
    public appeng.api.networking.crafting.ICraftingProvider declaredBatchBackoffOwner() {
        return host instanceof appeng.api.networking.crafting.ICraftingProvider provider
                ? provider : this;
    }

    @Override
    public @Nullable git.chexson.chexsonsaeutils.bigint.WideKeyCounter takePlannedNetworkOutputs() {
        var planned = lastPlannedNetworkOutputs;
        lastPlannedNetworkOutputs = null;
        return planned;
    }

    /** 每 tick 重查一次机器清单：useless 侧要求 target 不跨 tick 攥着，同 tick 内则只走一次网格。 */
    private List<UselessBigIntegerBatchClient.Machine> machinesThisTick() {
        var currentTick = TickHandler.instance().getCurrentTick();
        if (currentTick != machinesTick || machines == null) {
            machinesTick = currentTick;
            machines = UselessBigIntegerBatchClient.findMachines(grid);
        }
        return machines;
    }

    // ==== 本壳不是网格 provider：下面三个方法没人会调，给空实现只为满足接口形状 ====

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
