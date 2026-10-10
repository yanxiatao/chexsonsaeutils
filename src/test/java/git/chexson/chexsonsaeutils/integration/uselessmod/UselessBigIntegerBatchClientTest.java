package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * useless BigInteger 批次客户端的离线用例：四种"这台机器没接单"的出口各自的返回值与<b>回滚</b>。
 *
 * <p>这条通道只交<b>单发原型</b>，剩下 {@code count - 1} 份由我们自己的义务账扣，所以"机器没吃料"
 * 必须等价于"我们手上那份料一枚不少"。四个出口＝无 target / 容量不可用 / {@code admit} 返回
 * {@code null} / {@code commit} 返回 {@code false}，一律 {@code null} 且不留下任何幽灵账。
 *
 * <p>夹具用我们自己的 {@link UselessBigIntegerBatchClient.Machine} 假货：判定与账目只认这套形状，
 * 没装 useless_mod 也跑得起来（真通道在 {@code UselessBigIntegerApiBridge} 里，本文件一行都不碰）。
 */
final class UselessBigIntegerBatchClientTest {

    private static final AEItemKey ORE = AEItemKey.of(Items.IRON_ORE);
    private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
    private static final IPatternDetails PATTERN = new FakePattern();

    /** 一发原型的材料：一槽 1000 枚。 */
    private static KeyCounter[] unitPrototype() {
        var slot = new KeyCounter();
        slot.add(ORE, 1000L);
        return new KeyCounter[]{slot};
    }

    /** 无 target：返回 {@code null}，手上的原型料一枚不少。 */
    @Test
    void noTargetReturnsNullAndKeepsTheWholePrototype() {
        var prototype = unitPrototype();
        var result = UselessBigIntegerBatchClient.dispatch(null, PATTERN, prototype,
                BigInteger.valueOf(5_000L), grid -> List.of());

        assertNull(result, "网格里没有 bigint 机器＝通道不可用");
        assertEquals(1000L, prototype[0].get(ORE), "一枚料都没动：调用方按原路退回");
    }

    /** 容量不可用（{@code accepted=0}）：连 {@code admit} 都不该问一次，料原样留着。 */
    @Test
    void unavailableCapacityIsNeverAdmittedAndKeepsTheWholePrototype() {
        var machine = new FakeMachine("overworld@1,2,3");
        machine.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.ZERO,
                "useless_mod.msg.not_enough_energy");
        var prototype = unitPrototype();

        var result = UselessBigIntegerBatchClient.dispatch(null, PATTERN, prototype,
                BigInteger.valueOf(5_000L), grid -> List.of(machine));

        assertNull(result);
        assertEquals(0, machine.admitCalls, "没容量就不申请准入：机器侧零副作用");
        assertEquals(0, machine.commitCalls);
        assertEquals(1000L, prototype[0].get(ORE), "料一枚不少");
    }

    /** {@code admit} 返回 {@code null}：换下一台；都没接单即 {@code null}，前一台也没吃料。 */
    @Test
    void nullAdmitMovesToTheNextMachineAndNeverCommits() {
        var busy = new FakeMachine("overworld@1,2,3");
        busy.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(9_000L), "");
        busy.admitReturnsNull = true;
        var alsoBusy = new FakeMachine("overworld@4,5,6");
        alsoBusy.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(9_000L), "");
        alsoBusy.admitReturnsNull = true;
        var prototype = unitPrototype();

        var result = UselessBigIntegerBatchClient.dispatch(null, PATTERN, prototype,
                BigInteger.valueOf(5_000L), grid -> List.of(busy, alsoBusy));

        assertNull(result, "两台都没给出准入凭据＝这批没处去");
        assertEquals(2, busy.capacityCalls + alsoBusy.capacityCalls, "两台都问过一次容量");
        assertEquals(0, busy.commitCalls + alsoBusy.commitCalls, "没有凭据就没有提交");
        assertEquals(0, busy.committedUnits + alsoBusy.committedUnits, "机器一分料没吃");
        assertEquals(1000L, prototype[0].get(ORE), "承诺料完整可回滚");
    }

    /** {@code commit} 返回 {@code false}：机器没吃任何料，退回后换下一台真接单的机器。 */
    @Test
    void failedCommitRollsBackAndTheNextMachineTakesTheBatch() {
        var refusing = new FakeMachine("overworld@1,2,3");
        refusing.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(5_000L), "");
        refusing.commitReturnsFalse = true;
        var willing = new FakeMachine("overworld@4,5,6");
        willing.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(5_000L), "");
        willing.plannedOutputPerFiring = BigInteger.ONE;
        var prototype = unitPrototype();

        var result = UselessBigIntegerBatchClient.dispatch(null, PATTERN, prototype,
                BigInteger.valueOf(5_000L), grid -> List.of(refusing, willing));

        assertNotNull(result);
        assertEquals(1, refusing.commitCalls, "第一台被拒过一次");
        assertEquals(0, refusing.committedUnits, "被拒那台一分料没吃：我们的扣账原样有效");
        assertEquals(BigInteger.valueOf(5_000L), result.accepted(), "第二台整批接单");
        assertEquals("overworld@4,5,6", result.machineIdentity());
        assertNotNull(result.expectedToNetwork());
        assertEquals(BigInteger.valueOf(5_000L), result.expectedToNetwork().getExact(PRODUCT), "回执的计划产出按整批报回来");
        // 交出去的是副本：真机器在 commit 成功时会清空那份数组，CPU 手上的 holder 必须完好
        assertEquals(1000L, prototype[0].get(ORE), "CPU 那份单发原型一枚没少，销账照旧读它");
    }

    /** 正在降频的机器＝"机器正忙"：非紧急批不挤，换下一台。 */
    @Test
    void throttledMachineIsSkippedForNonUrgentBatches() {
        var throttled = new FakeMachine("overworld@1,2,3");
        throttled.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(5_000L), "");
        throttled.plannedOutputPerFiring = BigInteger.ONE;
        throttled.throttled = true;
        var idle = new FakeMachine("overworld@4,5,6");
        idle.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(700L), "");
        idle.plannedOutputPerFiring = BigInteger.ONE;

        var result = UselessBigIntegerBatchClient.dispatch(null, PATTERN, unitPrototype(),
                BigInteger.valueOf(5_000L), grid -> List.of(throttled, idle));

        assertNotNull(result);
        assertEquals(0, throttled.capacityCalls, "降频那台连容量都不问：不挤它的时间预算");
        assertEquals(BigInteger.valueOf(700L), result.accepted(), "降频永不把容量压到 0，换台照发");
    }

    /** 份数与整批产出均为 BigInteger；实物分段不截断计划回执。 */
    @Test
    void overLongRequestsAndOutputsStayExactAndSplitIntoLongSegments() {
        var requested = new BigInteger("10000000000000000000"); // 1e19
        var machine = new FakeMachine("overworld@1,2,3");
        machine.capacityResult = new UselessBigIntegerBatchClient.Capacity(requested, "");
        machine.plannedOutputPerFiring = BigInteger.valueOf(3L);
        var prototype = unitPrototype();

        var result = UselessBigIntegerBatchClient.dispatch(null, PATTERN, prototype, requested,
                grid -> List.of(machine));

        assertNotNull(result);
        assertEquals(requested, result.accepted(), "实收份数逐位等于机器给的份数");
        assertEquals(requested.multiply(BigInteger.valueOf(3L)), machine.reportedPlannedTotal,
                "回执按计划产出的 BigInteger 合计交回（3e19 枚）");
        var longCeiling = BigInteger.valueOf(Long.MAX_VALUE);
        assertNotNull(result.expectedToNetwork());
        assertEquals(requested.multiply(BigInteger.valueOf(3L)), result.expectedToNetwork().getExact(PRODUCT),
                "整批计划回执完整保留 3e19 枚，交付分段不影响整批账");
        assertTrue(longCeiling.compareTo(requested.multiply(BigInteger.valueOf(3L))) < 0,
                "计划产出确实超 long，需要保留宽域");
        assertEquals(1000L, prototype[0].get(ORE));
    }

    /** 没装 useless_mod 的离线环境：闸口一律"不可用"，注册与推送都不抛、也不碰它的类。 */
    @Test
    void absentUselessModKeepsEveryGateShutAndNothingThrows() {
        assertFalse(UselessBigIntegerBatchClient.modLoaded(), "离线测试环境里没有 useless_mod");
        assertFalse(UselessBigIntegerBatchClient.apiAvailable(), "通道不在场即不可用");
        assertFalse(UselessBigIntegerBatchClient.acceptsPatternKind(PATTERN));
        assertTrue(UselessBigIntegerBatchClient.findMachines(null).isEmpty());

        // 注册入口即使被 mod 生命周期叫到，也只能静默返回：不得抛、不得拖垮本 mod 加载
        UselessBigIntegerCompat.registerCpuAdapter();
    }

    /**
     * F2：{@code rejectDetail} 那一串<b>整串不含空白</b>，印不下的格数补一格 {@code /更多=N}。
     *
     * <p>旧实现红在用 {@code " 更多="}（前置空格）拼格：这一串会被拼进 {@code declaredReject=} 那一格，
     * 按空白切 token 的下游读数把一格切成两段（与 {@code StallProbe#describeDeclaredRejects}
     * 用的 {@code /更多=} 不是同一把尺）。
     */
    @Test
    void rejectDetailIsOneWhitespaceFreeTokenWithSlashMoreCell() {
        var throttled = new FakeMachine("overworld@1,2,3");
        throttled.throttled = true;
        var noCapacity = new FakeMachine("overworld@4,5,6");
        var noAdmit = new FakeMachine("overworld@7,8,9");
        noAdmit.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(5_000L), "");
        noAdmit.admitReturnsNull = true;
        var noCommit = new FakeMachine("overworld@10,11,12");
        noCommit.capacityResult = new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(5_000L), "");
        noCommit.commitReturnsFalse = true;
        var sixth = new FakeMachine("overworld@13,14,15");

        var outcome = UselessBigIntegerBatchClient.dispatchOutcome(null, PATTERN, unitPrototype(),
                BigInteger.valueOf(5_000L),
                grid -> List.of(throttled, noCapacity, noAdmit, noCommit, sixth));

        assertNull(outcome.result(), "五台都没接单");
        assertEquals("throttled#overworld@1,2,3/capacity#overworld@4,5,6/admit-null#overworld@7,8,9"
                + "/commit-false-noTake#overworld@10,11,12/更多=1", outcome.rejectDetail(),
                "四格封顶 + 一格 /更多=：分隔符与 declaredReject 那把尺同一个形状");
        assertFalse(outcome.rejectDetail().contains(" "),
                "整串不许含空白：下游按空白切 token，一个空格就把这一格切成两段");
    }

    /**
     * 笔 2 第一档：{@code commit()} 回 {@code false} 且所有权<b>没</b>转移＝"被拒收、一分料没吃"。
     *
     * <p>这一条只断"没吃料"那一格的名字：把两档并成一个 {@code commit-false} 的写法红在这里，
     * 把两档名字写反的写法红在下一条。处置（回滚/回池/退避）与拆档之前逐字相同，本条不碰。
     */
    @Test
    void rejectedCommitWithoutOwnershipTransferNamesTheNoTakeCell() {
        var machine = new FakeMachine("overworld@1,2,3");
        machine.capacityResult =
                new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(9L), "");
        machine.commitReturnsFalse = true;

        var outcome = UselessBigIntegerBatchClient.dispatchOutcome(null, PATTERN, unitPrototype(),
                BigInteger.valueOf(9L), grid -> List.of(machine));

        assertNull(outcome.result(), "夹具前提：这一台拒收");
        assertEquals("commit-false-noTake#overworld@1,2,3", outcome.rejectDetail(),
                "没吃料那一档只能点自己那一格：" + outcome.rejectDetail());
        assertFalse(outcome.rejectDetail().contains("tookInput"),
                () -> "没吃料的一格不许冒领「已吃料」：" + outcome.rejectDetail());
    }

    /**
     * 笔 2 第二档：{@code commit()} 回 {@code false} 却<b>已经</b>把输入料收走——这一型过去与
     * "拒收没吃料"共用一句 {@code commit-false}，而调用方的回滚正是按"没吃料"写的，
     * 于是"料回不来"在实机日志里一个字都读不出来。
     */
    @Test
    void rejectedCommitThatTookInputNamesTheTookInputCellNotNoTake() {
        var machine = new FakeMachine("overworld@4,5,6");
        machine.capacityResult =
                new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(9L), "");
        machine.commitReturnsFalse = true;
        machine.commitTookOwnership = true;

        var outcome = UselessBigIntegerBatchClient.dispatchOutcome(null, PATTERN, unitPrototype(),
                BigInteger.valueOf(9L), grid -> List.of(machine));

        assertNull(outcome.result(), "夹具前提：报的仍是 false（处置不改）");
        assertEquals("commit-false-tookInput#overworld@4,5,6", outcome.rejectDetail(),
                "吃了料那一档必须自己点名：" + outcome.rejectDetail());
        assertFalse(outcome.rejectDetail().contains("noTake"),
                () -> "吃了料的一格不许被记成「一分料没吃」：" + outcome.rejectDetail());
    }

    /**
     * 笔 2 的"两档不混算"那一半：同一轮两台机器各拒一种 false，两格必须各点各的机器、
     * 各用自己的名字——把两档并成一个计数器、或让后一档覆盖前一档，都红在这里。
     */
    @Test
    void theTwoCommitRejectFlavoursKeepTheirOwnCellsPerMachine() {
        var tookInput = new FakeMachine("overworld@1,2,3");
        tookInput.capacityResult =
                new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(9L), "");
        tookInput.commitReturnsFalse = true;
        tookInput.commitTookOwnership = true;
        var noTake = new FakeMachine("overworld@4,5,6");
        noTake.capacityResult =
                new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(9L), "");
        noTake.commitReturnsFalse = true;

        var outcome = UselessBigIntegerBatchClient.dispatchOutcome(null, PATTERN, unitPrototype(),
                BigInteger.valueOf(9L), grid -> List.of(tookInput, noTake));

        assertEquals("commit-false-tookInput#overworld@1,2,3/commit-false-noTake#overworld@4,5,6",
                outcome.rejectDetail(), "两档各点各的机器，谁也不许顶谁：" + outcome.rejectDetail());
        assertEquals(2L, tookInput.commitCalls + noTake.commitCalls,
                "夹具前提：拆档没有少问任何一台（两台各被问到一次 commit）");
    }

    /**
     * 笔 2 第三档：所有权那一问<b>抛异常</b>＝问不到。这一档单独一格，
     * <b>不许</b>被默认成"没吃料"（那等于拿猜顶读数）——默认 {@code false} 的写法红在这里。
     */
    @Test
    void ownershipAskThatThrowsGetsItsOwnUnknownCell() {
        var machine = new FakeMachine("overworld@7,8,9");
        machine.capacityResult =
                new UselessBigIntegerBatchClient.Capacity(BigInteger.valueOf(9L), "");
        machine.commitReturnsFalse = true;
        machine.ownershipAskThrows = true;

        var outcome = UselessBigIntegerBatchClient.dispatchOutcome(null, PATTERN, unitPrototype(),
                BigInteger.valueOf(9L), grid -> List.of(machine));

        assertEquals("commit-false-ownershipUnknown#overworld@7,8,9", outcome.rejectDetail(),
                "问不到那一档既不许顶成没吃料、也不许顶成吃了料：" + outcome.rejectDetail());
    }

    // ==================== 夹具 ====================

    /** 假机器：按脚本给容量、准入与提交，并把计划产出同步交回回执槽（真机器就是同栈帧回调）。 */
    private static final class FakeMachine implements UselessBigIntegerBatchClient.Machine {

        private final String identity;
        private UselessBigIntegerBatchClient.Capacity capacityResult =
                new UselessBigIntegerBatchClient.Capacity(BigInteger.ZERO, "");
        private boolean admitReturnsNull;
        private boolean commitReturnsFalse;
        /** {@code commit()} 回 {@code false} 时这一台其实<b>已把输入料收走</b>（读数拆档用）。 */
        private boolean commitTookOwnership;
        /** 所有权那一问抛异常＝问不到（第三档，不许并进上面两档任何一档）。 */
        private boolean ownershipAskThrows;
        private boolean throttled;
        /** 这一台报得出的回网队列深度；{@code null}＝问不到（读数夹具用，默认问不到）。 */
        private UselessBigIntegerBatchClient.QueuePending queuePending;
        private BigInteger plannedOutputPerFiring = BigInteger.ZERO;

        private int capacityCalls;
        private int admitCalls;
        private int commitCalls;
        private long committedUnits;
        private BigInteger reportedPlannedTotal = BigInteger.ZERO;
        private KeyCounter[] lastHandedPrototype;

        private FakeMachine(String identity) {
            this.identity = identity;
        }

        @Override
        public String machineIdentity() {
            return identity;
        }

        @Override
        public boolean throttled() {
            return throttled;
        }

        /** 回网队列深度（纯读数口）：这一份夹具不扮那一档，交回 {@code null}＝问不到。 */
        @Override
        public @org.jetbrains.annotations.Nullable UselessBigIntegerBatchClient.QueuePending
                queuePending() {
            return queuePending;
        }

        @Override
        public UselessBigIntegerBatchClient.Capacity capacity(IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested) {
            capacityCalls++;
            lastHandedPrototype = unitPrototype;
            return capacityResult;
        }

        @Override
        public UselessBigIntegerBatchClient.BatchHandle admit(IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested, UselessBigIntegerReceipt receipt) {
            admitCalls++;
            // 契约：提交必须用准备时那一份数组对象
            if (unitPrototype != lastHandedPrototype) {
                throw new IllegalArgumentException("admit 与 capacity 必须是同一份原型数组");
            }
            if (admitReturnsNull) {
                return null;
            }
            return new FakeAdmission(this, unitPrototype, requested, receipt);
        }
    }

    private static final class FakeAdmission implements UselessBigIntegerBatchClient.BatchHandle {

        private final FakeMachine machine;
        private final KeyCounter[] prototype;
        private final BigInteger count;
        private final UselessBigIntegerReceipt receipt;

        private FakeAdmission(FakeMachine machine, KeyCounter[] prototype, BigInteger count,
                UselessBigIntegerReceipt receipt) {
            this.machine = machine;
            this.prototype = prototype;
            this.count = count;
            this.receipt = receipt;
        }

        @Override
        public BigInteger count() {
            return count;
        }

        @Override
        public boolean commit() {
            machine.commitCalls++;
            if (machine.commitReturnsFalse) {
                // 机器没吃任何料：数组不清空，调用方那 (count - 1) 份的扣账仍然有效
                return false;
            }
            long units = 0L;
            for (var slot : prototype) {
                for (var entry : slot) {
                    units += entry.getLongValue();
                }
                slot.clear(); // 与真机器一致：提交成功即逐槽清空，材料所有权转移
            }
            machine.committedUnits += units;
            if (machine.plannedOutputPerFiring.signum() > 0) {
                var planned = machine.plannedOutputPerFiring.multiply(count);
                machine.reportedPlannedTotal = planned;
                // 真机器在 commit 的同一层栈帧里经 onBatchAdmitted 把计划产出交回来
                receipt.addPlannedOutput(PRODUCT, planned);
            }
            return true;
        }

        /**
         * {@code commit()} 回 {@code false} 之后的第二问（public 口）：这一台到底把料吃没吃。
         * 桩按脚本答三种：没吃（默认）、已吃、问不到（抛）。
         */
        @Override
        public boolean hasTransferredInputOwnership() {
            if (machine.ownershipAskThrows) {
                throw new IllegalStateException("所有权这一问读不出来（桩）");
            }
            return machine.commitTookOwnership;
        }
    }

    private static final class FakePattern implements IPatternDetails {

        @Override
        public AEItemKey getDefinition() {
            return AEItemKey.of(Items.CRAFTING_TABLE);
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return new ArrayList<>();
        }
    }
}
