package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.crafting.IPatternDetails;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerApi;
import com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerBatch;
import com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerCapacity;
import com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerOutput;
import com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerProvider;
import com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerTarget;
import com.sorrowmist.useless.api.crafting.bigint.cpu.AlloyFurnaceBigIntegerBatchContext;
import com.sorrowmist.useless.api.crafting.bigint.cpu.AlloyFurnaceBigIntegerBatchResult;
import com.sorrowmist.useless.api.crafting.bigint.cpu.AlloyFurnaceBigIntegerCpuAdapter;
import com.sorrowmist.useless.api.crafting.bigint.cpu.AlloyFurnaceBigIntegerCpuAdapters;
import com.sorrowmist.useless.api.crafting.bigint.cpu.AlloyFurnaceBigIntegerCpuBinding;
import com.sorrowmist.useless.content.blockentities.multiblock.MultiblockAlloyFurnaceCoreBlockEntity;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本 mod 里唯一 import {@code com.sorrowmist.useless.api.crafting.bigint}（外加
 * {@code content.blockentities.multiblock.MultiblockAlloyFurnaceCoreBlockEntity} 这一个
 * <b>public 类</b>——只为读它那两只 public 回网队列口，见
 * {@link TargetMachine#queuePending()}）的文件：
 * 桥接 useless 的 BigInteger 批次通道与 {@link UselessBigIntegerBatchClient} 的通道形状。
 *
 * <p><b>加载时机是被判过闸的</b>：每个入口都在调用方确认
 * {@link UselessBigIntegerBatchClient#apiAvailable()}（= 装了 useless_mod 且 bigint 入口类在场）
 * 之后才被触及，所以没装 useless_mod、或装的是 2.3.4 那种没这通道的版本时，本类连类加载都不会发生，
 * 更谈不上 {@code NoClassDefFoundError}。
 *
 * <p>顺带承载本 mod 的 CPU 回执适配器：它<b>只做点名、对账，以及把该笔的终局事件交回 CPU 台账</b>
 * （{@code onBatchAdmitted} 记 {@code batchId}，{@code onBatchFinished} 把 {@code (batchId, 结局)}
 * 交给 {@code DeclaredBatchTerminalLedger}：{@code SUCCESS} 结掉那批发数，
 * {@code REJECTED}/{@code CANCELLED} 把那一批发数<b>退回该样板的待办</b>（材料欠账原样挂着，
 * 重新申报时由既有义务账重挂；这一型沿用"接受后才 commit 游标、拒绝先回注且不前移"））。
 * 它<b>绝不补记产出</b>——产出在推送那一刻已按
 * {@code plannedOutputs} 记进<b>预期入网</b>账（那是一笔预期，不是入网证据），再记一遍就是重复计。
 * 那把做差的尺在 {@link UselessBigIntegerReceipt#selfReportDelta}：本类 import 了 useless 的类型，
 * 装在没装 useless 的离线用例里根本加载不了，判据不能长在这上面——所以终局那一条凭据的<b>本体</b>
 * 在 {@code DeclaredBatchTerminalLedger}（无 useless 依赖，离线可直接打同一条口），本类只剩拆
 * record 与转调。
 */
final class UselessBigIntegerApiBridge {

    /** 本 mod 的回执适配器 id（{@code chexsonsaeutils} 命名空间）。 */
    static final ResourceLocation CPU_ADAPTER_ID =
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "parallel_cpu_bigint");

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * 回执适配器<b>延迟</b>建：本类的静态初始化绝不碰 useless 的类型——本类可能被
     * {@code isBigIntegerProvider} 这类判定调用，抢先生成适配器就会在没这通道的版本上抛
     * {@code NoClassDefFoundError}。
     */
    private static @Nullable AlloyFurnaceBigIntegerCpuAdapter receiptAdapter;
    /** 通道接受的样板物品 id：每 tick 逐张样板问一次，查中一次就钉住。 */
    private static volatile @Nullable Set<ResourceLocation> acceptedPatternKinds;

    private UselessBigIntegerApiBridge() {
    }

    /**
     * 这台真宿主自己是不是 bigint provider（只有多方块合金炉的核心与组装多方块是）。
     *
     * <p>判空在前：{@code apiAvailable()} 为假时不引用 useless 的类型，老版本与未安装时
     * 一律按「不是」处理，不会为这个 {@code instanceof} 去加载缺失的类。
     */
    static boolean isBigIntegerProvider(@Nullable Object candidate) {
        return UselessBigIntegerBatchClient.apiAvailable()
                && candidate instanceof AlloyFurnaceBigIntegerProvider;
    }

    /** 现查网格里的 bigint 机器（按物理机器身份去重由 useless 侧 {@code findTargets} 保证）。 */
    static List<UselessBigIntegerBatchClient.Machine> findMachines(@Nullable IGrid grid) {
        var targets = AlloyFurnaceBigIntegerApi.findTargets(grid);
        if (targets.isEmpty()) {
            return List.of();
        }
        var machines = new ArrayList<UselessBigIntegerBatchClient.Machine>(targets.size());
        for (AlloyFurnaceBigIntegerTarget target : targets) {
            if (target != null) {
                machines.add(new TargetMachine(target));
            }
        }
        return machines;
    }

    /** 这份样板在不在通道里：useless 只接 AE2 合成样板与万象样板，普通处理样板不在内。 */
    static boolean acceptsPatternKind(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return false;
        }
        var definition = pattern.getDefinition();
        if (definition == null) {
            return false;
        }
        return acceptedPatternKinds().contains(definition.getId());
    }

    /**
     * 通道接受的样板物品 id：每 tick 逐张样板都要问一次，查中一次就钉住。
     *
     * <p>物品注册表在服务器 tick 里已冻结，取值不会中途变；启动早期万一拿到空表就不钉，下次再查。
     */
    private static Set<ResourceLocation> acceptedPatternKinds() {
        var cached = acceptedPatternKinds;
        if (cached != null) {
            return cached;
        }
        var kinds = AlloyFurnaceBigIntegerApi.acceptedPatternKinds();
        if (!kinds.isEmpty()) {
            acceptedPatternKinds = kinds;
        }
        return kinds;
    }

    /** 注册 CPU 回执适配器（幂等：同一实例重复注册由 useless 侧放行）。 */
    static void registerCpuAdapter() {
        var adapter = receiptAdapter;
        if (adapter == null) {
            adapter = new ParallelCpuReceiptAdapter();
            receiptAdapter = adapter;
        }
        AlloyFurnaceBigIntegerCpuAdapters.register(CPU_ADAPTER_ID, adapter);
        LOGGER.info("[chexsonsaeutils-bigint] 已注册 bigint 回执适配器 id={}", CPU_ADAPTER_ID);
    }

    // ==================== 通道形状适配 ====================

    /** 一台多方块万象合金炉：把 useless 的 target 说成客户端听得懂的话。 */
    private static final class TargetMachine implements UselessBigIntegerBatchClient.Machine {

        private final AlloyFurnaceBigIntegerTarget target;

        private TargetMachine(AlloyFurnaceBigIntegerTarget target) {
            this.target = target;
        }

        @Override
        public String machineIdentity() {
            return target.machineIdentity();
        }

        @Override
        public boolean throttled() {
            return target.isThrottled();
        }

        @Override
        public UselessBigIntegerBatchClient.Capacity capacity(IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested) {
            AlloyFurnaceBigIntegerCapacity capacity = target.capacity(pattern, unitPrototype, requested);
            if (capacity == null) {
                return new UselessBigIntegerBatchClient.Capacity(BigInteger.ZERO, "");
            }
            return new UselessBigIntegerBatchClient.Capacity(capacity.accepted(), capacity.statusKey());
        }

        @Override
        public @Nullable UselessBigIntegerBatchClient.BatchHandle admit(IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested, UselessBigIntegerReceipt receipt) {
            var binding = new AlloyFurnaceBigIntegerCpuBinding(CPU_ADAPTER_ID, receipt);
            var batch = target.admit(pattern, unitPrototype, requested, binding);
            // 提交用的必须正是 admit 时那一份数组对象（useless 侧按引用比对，换数组直接抛异常）
            return batch == null ? null : new Admission(batch, unitPrototype);
        }

        /**
         * 这一台的回网队列深度（台账 #225 读数 5，<b>纯读数</b>）：只走
         * {@code MultiblockAlloyFurnaceCoreBlockEntity} 那<b>两个 public 口</b>
         * （{@code getMaxAETaskCount()}／{@code getRemainingAETaskCount(true)}）换算，
         * 私有字段 {@code queuedCraftingOutputs} 一律不碰（无反射、无 mixin）。
         *
         * <p>核心 BE 从哪来：{@code machineIdentity()} 的取值本来就是<b>核心 BE 的坐标</b>
         * （useless 侧唯一身份公式 {@code 维度@x,y,z}），按它现取那一个方块实体即可——
         * <b>不重跑一次 {@code findTargets} 网格扫描</b>，调用方递进来的这台机器就是这一条链上
         * 推送那一刻已经握过的那一台。
         *
         * <p>读不到就是读不到，一律 {@code null}（下游印 {@code ?}，<b>不许</b>折成 0）：
         * 身份串不是 {@code 维度@x,y,z} 那一型（例如 level 为空时机器自己交的
         * {@code machine@<long>} 退路形）、服务端还没起来、区块没加载、BE 不是那一型、
         * 两个读口抛异常，全在 {@code null} 这一档。
         */
        @Override
        public @Nullable UselessBigIntegerBatchClient.QueuePending queuePending() {
            var core = coreBlockEntityOf(target.machineIdentity());
            if (core == null) {
                return null;
            }
            try {
                // 装机 2.3.9 的换算式（AdvancedAlloyFurnaceAeManager#craftingPatternQueueCap
                // → #hardOutputQueueCap → #getRemainingAETaskCount(boolean) 三段字节码逐字对照）：
                //   cap       = max(2, min(Integer.MAX_VALUE, max(1, getMaxAETaskCount()) × 8))
                //   remaining = max(0, cap - min(cap, queuedCraftingOutputs.size()))
                // 反解出条目数 = cap - min(cap, remaining)；remaining==0 时只读得出"≥cap"。
                var threads = Math.max(1, core.getMaxAETaskCount());
                var cap = Math.max(2L, Math.min((long) Integer.MAX_VALUE,
                        threads * (long) OUTPUT_QUEUE_CAP_MULTIPLIER));
                var remaining = Math.max(0, core.getRemainingAETaskCount(true));
                var entries = cap - Math.min(cap, (long) remaining);
                return new UselessBigIntegerBatchClient.QueuePending(entries, cap, remaining == 0);
            } catch (Throwable e) {
                LOGGER.debug("[chexsonsaeutils-bigint] 回网队列深度读数取不到（这一格随后如实印 ?）", e);
                return null;
            }
        }
    }

    /**
     * {@code AdvancedAlloyFurnaceAeManager#hardOutputQueueCap} 的条目倍数（2.3.9 字节码里的
     * {@code OUTPUT_BACKLOG_HARD_MULTIPLIER}）：这一格只用来<b>换算</b>回网队列的条目上限，
     * 改版对不上时上面的换算式整体读空，不会读成别人的数。
     */
    private static final int OUTPUT_QUEUE_CAP_MULTIPLIER = 8;

    /**
     * 按机器身份（{@code 维度@x,y,z}，核心 BE 的坐标）现取那一台多方块万象合金炉的核心 BE。
     *
     * <p>这是一次<b>按坐标取方块实体</b>，不是网格扫描；任何一步取不到都交回 {@code null}
     * （读数口径，见 {@link TargetMachine#queuePending()}）。
     */
    private static @Nullable MultiblockAlloyFurnaceCoreBlockEntity coreBlockEntityOf(
            @Nullable String machineIdentity) {
        if (machineIdentity == null) {
            return null;
        }
        try {
            var at = machineIdentity.lastIndexOf('@');
            if (at < 0) {
                return null;
            }
            var coords = machineIdentity.substring(at + 1).split(",");
            if (coords.length != 3) {
                return null;
            }
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return null;
            }
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(machineIdentity.substring(0, at))));
            if (level == null) {
                return null;
            }
            return level.getBlockEntity(new BlockPos(Integer.parseInt(coords[0].trim()),
                    Integer.parseInt(coords[1].trim()), Integer.parseInt(coords[2].trim())))
                    instanceof MultiblockAlloyFurnaceCoreBlockEntity core ? core : null;
        } catch (Throwable e) {
            LOGGER.debug("[chexsonsaeutils-bigint] 机器身份 {} 取不到核心 BE（这一格随后如实印 ?）",
                    machineIdentity, e);
            return null;
        }
    }

    /** 一次准入凭据。 */
    private static final class Admission implements UselessBigIntegerBatchClient.BatchHandle {

        private final AlloyFurnaceBigIntegerBatch batch;
        private final KeyCounter[] prototype;

        private Admission(AlloyFurnaceBigIntegerBatch batch, KeyCounter[] prototype) {
            this.batch = batch;
            this.prototype = prototype;
        }

        @Override
        public @Nullable BigInteger count() {
            return batch.count();
        }

        @Override
        public boolean commit() {
            return batch.commit(prototype);
        }

        /**
         * 原样透出 useless 那一只 <b>public</b> 读口（不反射、不改行为）：{@code commit()} 回
         * {@code false} 之后，客户端拿它把"拒收没吃料"与"报了 false 却已把料吃掉"分成两格读数。
         */
        @Override
        public boolean hasTransferredInputOwnership() {
            return batch.hasTransferredInputOwnership();
        }
    }

    // ==================== 回执：只对账，不补记 ====================

    private static final class ParallelCpuReceiptAdapter implements AlloyFurnaceBigIntegerCpuAdapter {

        @Override
        public @NotNull ResourceLocation id() {
            return CPU_ADAPTER_ID;
        }

        @Override
        public void onBatchAdmitted(@NotNull AlloyFurnaceBigIntegerBatchContext context) {
            // 与推送同栈帧：把整批计划产出交回正在等回执的那一次 dispatch
            if (context.cpuToken() instanceof UselessBigIntegerReceipt receipt) {
                // 先把这一笔的 batchId 记进 token：CPU 侧的在飞台账凭它认回"自己那一笔申报"。
                // 从 admit 到终局，useless 侧带的是同一个 context 对象（PendingCraftingOutput.cpuContext），
                // 所以这一个 batchId 与后面 onBatchFinished 报回来的是同一个。
                receipt.noteBatchId(context.batchId());
                for (AlloyFurnaceBigIntegerOutput planned : context.plannedOutputs()) {
                    if (planned != null) {
                        receipt.addPlannedOutput(planned.what(), planned.amount());
                    }
                }
            }
        }

        @Override
        public @NotNull Map<AEKey, BigInteger> claimOutputs(@NotNull AlloyFurnaceBigIntegerBatchContext context,
                @NotNull List<AlloyFurnaceBigIntegerOutput> availableOutputs) {
            if (!(context.cpuToken() instanceof UselessBigIntegerReceipt receipt)) {
                return Map.of();
            }
            var core = coreBlockEntityOf(context.machineIdentity());
            if (core == null || core.getAeGrid() == null) {
                return Map.of();
            }
            // 2.3.9 在真实交付队列扣账前调用；数量可能合并多个批次，不能按 plannedOutputs 补库存。
            return receipt.claimOutputs(context.batchId(), core.getAeGrid(), toWideTotals(availableOutputs));
        }

        @Override
        public void onBatchOutputs(@NotNull AlloyFurnaceBigIntegerBatchContext context,
                @NotNull List<AlloyFurnaceBigIntegerOutput> actualOutputs) {
            // 这一批机器报回来的「实际产出」就是入队时算好的那份计划量本身（useless 侧
            // notifyCpuBatchCompleted 直接取 pending.bigIntegerOutputs，注释写着"实际产出直接取入队时
            // 算好的 BigInteger 精确值"）。CPU 的预期账在推送那一刻已按同一份 plannedOutputs 结过，
            // 所以这里既没有可补记的东西、也**对不出入网**：只逐键点名"自报清单 vs 样板声明"的差
            // （动态产物的键形差就从这一格露出来）。
            var planned = toWideTotals(context.plannedOutputs());
            var selfReported = toWideTotals(actualOutputs);
            var delta = UselessBigIntegerReceipt.selfReportDelta(planned, selfReported);
            if (delta.isEmpty()) {
                if (ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled()) {
                    LOGGER.debug("[chexsonsaeutils-bigint] 批次自报清单对平(两侧皆自报量,非入网证据)"
                                + " machine={} 份数={} 清单={}",
                            context.machineIdentity(), context.acceptedCount(), selfReported);
                }
                return;
            }
            // 差额那一格直说欠了几枚：欠的那一截早已按预期记进 CPU 的预期入网账，
            // 这一行不许把它读成"交付对平"，也不许拿它补记产出。
            LOGGER.warn("[chexsonsaeutils-bigint] 自报清单与样板声明不符(本 mod 不补记、不回滚预期账)"
                            + " machine={} 样板={} 份数={} 计划={} 自报={} 差额={}",
                    context.machineIdentity(),
                    context.pattern() == null ? "?" : context.pattern().getDefinition(),
                    context.acceptedCount(), planned, selfReported,
                    UselessBigIntegerReceipt.describeSelfReportDelta(delta));
        }

        @Override
        public void onBatchFinished(@NotNull AlloyFurnaceBigIntegerBatchContext context,
                @NotNull AlloyFurnaceBigIntegerBatchResult result) {
            // 这一批的终局到了：回调体的全部实质动作就是把 (batchId, 结局) 交给 CPU 侧的在飞台账
            // （DeclaredBatchTerminalLedger 的口径：SUCCESS 结掉那批发数，REJECTED/CANCELLED 沿挂账时
            // 交进去的回退出口把那一批发数<b>退回该样板的待办</b>——不拦表、不退料）。
            // 这里不读任何数量——context.acceptedCount() 与 plannedOutputs 都是 admit 那一刻的自报量，
            // 拿它们当结案凭据就是上一刀（b99d7ee）被回退的那两条死因之一。
            if (context.cpuToken() instanceof UselessBigIntegerReceipt receipt) {
                receipt.deliverTerminal(context.batchId(), toTerminalOutcome(result));
            }
            if (result == AlloyFurnaceBigIntegerBatchResult.CANCELLED) {
                // 取消的批次 useless 侧明确不发 onBatchOutputs（见 AlloyFurnaceBigIntegerCpuAdapter
                // 的回调约定）⇒ 这一批记进 CPU 预期账的那几枚，从此没有任何一份自报清单对得上号。
                // 缺的那一截只能在这一行如实露出来：账不动、不补交付（存档重载后 cpuToken 会丢，
                // 找不到批次也是同一形态）。
                LOGGER.warn("[chexsonsaeutils-bigint] 批次以 CANCELLED 收场 batch={} machine={} 份数={}："
                        + "无自报清单可对账，按预期入网账处理（不补记产出）预期={}",
                        context.batchId(), context.machineIdentity(), context.acceptedCount(),
                        toWideTotals(context.plannedOutputs()));
                return;
            }
            if (result != AlloyFurnaceBigIntegerBatchResult.SUCCESS) {
                // 2.3.9 的字节码里 REJECTED 没有任何发射点（全 jar 除枚举自身声明外零引用），
                // 走到这一支只可能是新版本加了别的负向结局：按欠交付记，绝不记成结清。
                LOGGER.warn("[chexsonsaeutils-bigint] 批次以非成功终局收场 batch={} machine={} result={}："
                                + "这一批发数记为欠交付（不整张归零、不退料）",
                        context.batchId(), context.machineIdentity(), result);
                return;
            }
            if (ChexsonsaeutilsCompatibilityConfig.parallelCpuDiagnosticsEnabled()) {
                LOGGER.debug("[chexsonsaeutils-bigint] 批次已终局(SUCCESS) batch={} machine={}：那批发数结掉在飞记录",
                        context.batchId(), context.machineIdentity());
            }
        }

        /**
         * 终局枚举的映射：三档穷举，useless 以后加新结局就在这里编译不过（宁可编译期发现，
         * 也不许把没见过的结局默认当成功结案）。
         */
        private static DeclaredBatchTerminalLedger.Outcome toTerminalOutcome(
                AlloyFurnaceBigIntegerBatchResult result) {
            return switch (result) {
                case SUCCESS -> DeclaredBatchTerminalLedger.Outcome.SUCCESS;
                case REJECTED -> DeclaredBatchTerminalLedger.Outcome.REJECTED;
                case CANCELLED -> DeclaredBatchTerminalLedger.Outcome.CANCELLED;
            };
        }
    }

    private static Map<AEKey, BigInteger> toWideTotals(List<AlloyFurnaceBigIntegerOutput> outputs) {
        var totals = new LinkedHashMap<AEKey, BigInteger>();
        if (outputs == null) {
            return totals;
        }
        for (AlloyFurnaceBigIntegerOutput output : outputs) {
            if (output == null || output.what() == null || output.amount() == null) {
                continue;
            }
            totals.merge(output.what(), output.amount(), BigInteger::add);
        }
        return totals;
    }
}
