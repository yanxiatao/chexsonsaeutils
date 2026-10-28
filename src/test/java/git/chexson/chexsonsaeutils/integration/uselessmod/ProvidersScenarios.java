package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 供应器与可选模组桥接的行为场景；由类别测试统一调用。 */
public final class ProvidersScenarios {
    private ProvidersScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.integration.uselessmod",
                () -> UselessBigIntegerBatchClientCases.verify(temporaryRoot),
                () -> UselessBigIntegerReceiptCases.verify(temporaryRoot)
        );
    }

    /** UselessBigIntegerBatchClientCases 的独立场景夹具，按类别统一执行。 */
    public static final class UselessBigIntegerBatchClientCases {
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
        void noTargetReturnsNullAndKeepsTheWholePrototype() {
            var prototype = unitPrototype();
            var result = UselessBigIntegerBatchClient.dispatch(null, PATTERN, prototype,
                    BigInteger.valueOf(5_000L), grid -> List.of());

            assertNull(result, "网格里没有 bigint 机器＝通道不可用");
            assertEquals(1000L, prototype[0].get(ORE), "一枚料都没动：调用方按原路退回");
        }

        /** 容量不可用（{@code accepted=0}）：连 {@code admit} 都不该问一次，料原样留着。 */
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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.noTargetReturnsNullAndKeepsTheWholePrototype", () -> new UselessBigIntegerBatchClientCases().noTargetReturnsNullAndKeepsTheWholePrototype()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.unavailableCapacityIsNeverAdmittedAndKeepsTheWholePrototype", () -> new UselessBigIntegerBatchClientCases().unavailableCapacityIsNeverAdmittedAndKeepsTheWholePrototype()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.nullAdmitMovesToTheNextMachineAndNeverCommits", () -> new UselessBigIntegerBatchClientCases().nullAdmitMovesToTheNextMachineAndNeverCommits()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.failedCommitRollsBackAndTheNextMachineTakesTheBatch", () -> new UselessBigIntegerBatchClientCases().failedCommitRollsBackAndTheNextMachineTakesTheBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.throttledMachineIsSkippedForNonUrgentBatches", () -> new UselessBigIntegerBatchClientCases().throttledMachineIsSkippedForNonUrgentBatches()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.overLongRequestsAndOutputsStayExactAndSplitIntoLongSegments", () -> new UselessBigIntegerBatchClientCases().overLongRequestsAndOutputsStayExactAndSplitIntoLongSegments()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.absentUselessModKeepsEveryGateShutAndNothingThrows", () -> new UselessBigIntegerBatchClientCases().absentUselessModKeepsEveryGateShutAndNothingThrows()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.rejectDetailIsOneWhitespaceFreeTokenWithSlashMoreCell", () -> new UselessBigIntegerBatchClientCases().rejectDetailIsOneWhitespaceFreeTokenWithSlashMoreCell()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.rejectedCommitWithoutOwnershipTransferNamesTheNoTakeCell", () -> new UselessBigIntegerBatchClientCases().rejectedCommitWithoutOwnershipTransferNamesTheNoTakeCell()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.rejectedCommitThatTookInputNamesTheTookInputCellNotNoTake", () -> new UselessBigIntegerBatchClientCases().rejectedCommitThatTookInputNamesTheTookInputCellNotNoTake()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.theTwoCommitRejectFlavoursKeepTheirOwnCellsPerMachine", () -> new UselessBigIntegerBatchClientCases().theTwoCommitRejectFlavoursKeepTheirOwnCellsPerMachine()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerBatchClientCases.ownershipAskThatThrowsGetsItsOwnUnknownCell", () -> new UselessBigIntegerBatchClientCases().ownershipAskThatThrowsGetsItsOwnUnknownCell())
            );
        }
    }

    /** UselessBigIntegerReceiptCases 的独立场景夹具，按类别统一执行。 */
    public static final class UselessBigIntegerReceiptCases {
        private static final AEItemKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey BYPRODUCT = AEItemKey.of(Items.CLOCK);

        void realOutputClaimKeepsBigIntegerAndIgnoresPlannedCounts() {
            var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
            var id = java.util.UUID.randomUUID();
            receipt.noteBatchId(id);
            BigInteger actual = BigInteger.TEN.pow(40).add(BigInteger.valueOf(17));
            receipt.addPlannedOutput(PRODUCT, actual.multiply(BigInteger.TEN));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            receipt.bindOutputReceiver((grid, what, available) -> {
                calls.incrementAndGet();
                return what.equals(PRODUCT) ? available : BigInteger.ZERO;
            });
            var available = Map.<AEKey, BigInteger>of(PRODUCT, actual, BYPRODUCT, BigInteger.valueOf(20L));
            assertEquals(Map.of(), receipt.claimOutputs(java.util.UUID.randomUUID(), new ClaimGrid(), available));
            assertEquals(0, calls.get());
            assertEquals(Map.of(PRODUCT, actual), receipt.claimOutputs(id, new ClaimGrid(), available));
            assertEquals(2, calls.get());
        }

        void terminalReleasesActualOutputReceiverEvenWithoutTerminalLedger() {
            var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
            var id = java.util.UUID.randomUUID();
            receipt.noteBatchId(id);
            receipt.bindOutputReceiver((grid, what, available) -> available);
            receipt.deliverTerminal(id,
                    git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.CANCELLED);
            assertEquals(Map.of(), receipt.claimOutputs(id, new ClaimGrid(), Map.of(PRODUCT, BigInteger.TEN)));
        }

        /** 只作为网络身份；实物认领不得访问网络服务或进行存储插入。 */
        private static final class ClaimGrid implements appeng.api.networking.IGrid {
            private UnsupportedOperationException unused() {
                return new UnsupportedOperationException("实物直送无需网络服务");
            }
            public <C extends appeng.api.networking.IGridService> C getService(Class<C> type) { throw unused(); }
            public <T extends appeng.api.networking.events.GridEvent> T postEvent(T event) { throw unused(); }
            public Iterable<Class<?>> getMachineClasses() { throw unused(); }
            public Iterable<appeng.api.networking.IGridNode> getMachineNodes(Class<?> type) { throw unused(); }
            public <T> java.util.Set<T> getMachines(Class<T> type) { throw unused(); }
            public <T> java.util.Set<T> getActiveMachines(Class<T> type) { throw unused(); }
            public Iterable<appeng.api.networking.IGridNode> getNodes() { throw unused(); }
            public boolean isEmpty() { throw unused(); }
            public appeng.api.networking.IGridNode getPivot() { throw unused(); }
            public int size() { throw unused(); }
            public void export(com.google.gson.stream.JsonWriter writer) { throw unused(); }
        }

        /** 计划回执保留逐键精确量：没收到任何计划即 {@code null}（＝全额挂实物预期）。 */
        void plannedCounterIsTheSelfReportedPlanNotAnInsertReceipt() {
            var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
            assertNull(receipt.plannedAsWideCounter(), "一条计划都没交回来＝null，调用方退回实物挂预期");

            receipt.addPlannedOutput(PRODUCT, BigInteger.valueOf(5_000L));
            receipt.addPlannedOutput(PRODUCT, BigInteger.valueOf(2_500L));
            WideKeyCounter planned = receipt.plannedAsWideCounter();
            assertEquals(BigInteger.valueOf(7_500L), planned.getExact(PRODUCT), "同键复加：那份读数就是整批的计划量");
            assertEquals(1, planned.size(), "只报回来的那一键");
        }

        /** 计划量超 long 时仍保留整批，不混同交付分段粒度。 */
        void plannedKeyBeyondLongRetainsTheWholeBatch() {
            var receipt = new UselessBigIntegerReceipt("overworld@1,2,3");
            receipt.addPlannedOutput(PRODUCT, BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
                    receipt.plannedAsWideCounter().getExact(PRODUCT),
                    "保留整批数量，不能以交付分段大小替代");
        }

        /** 欠的那一截必须出表：自报清单比声明少两枚，差表里就得有这 −2。 */
        void shortfallAgainstTheDeclarationStaysVisibleInTheDelta() {
            var delta = UselessBigIntegerReceipt.selfReportDelta(
                    totals(PRODUCT, 100L), totals(PRODUCT, 98L));
            assertEquals(BigInteger.valueOf(-2L), delta.get(PRODUCT),
                    "少交付那一截在差表里是负的，不许被吞成空表");
            assertTrue(UselessBigIntegerReceipt.describeSelfReportDelta(delta).contains("欠2"),
                    "读数要把方向说出来：只印一个负数，读日志的人会当它是零头。读数="
                            + UselessBigIntegerReceipt.describeSelfReportDelta(delta));
        }

        /** 声明里有、自报清单里整个没有这一键：同样算欠，不许读成"对平"。 */
        void declaredKeyMissingFromTheSelfReportReadsAsShortfallNotAsBalanced() {
            var delta = UselessBigIntegerReceipt.selfReportDelta(
                    totals(PRODUCT, 100L), totals(BYPRODUCT, 30L));
            assertEquals(BigInteger.valueOf(-100L), delta.get(PRODUCT), "自报侧缺这一键＝整笔欠");
            assertEquals(BigInteger.valueOf(30L), delta.get(BYPRODUCT), "自报侧多出来那一键＝键形差（动态产物换了真键）");
            assertTrue(delta.size() == 2, "两截都要点名，缺一格就说不清是谁对不上");
        }

        /** 两侧逐键相等才是空表，且那句读数不许再声称"交付"。 */
        void equalSelfReportReadsAsBalancedWithoutClaimingDelivery() {
            var planned = new LinkedHashMap<AEKey, BigInteger>();
            planned.put(PRODUCT, BigInteger.valueOf(1_000L));
            planned.put(BYPRODUCT, BigInteger.valueOf(7L));
            var selfReported = new LinkedHashMap<AEKey, BigInteger>();
            selfReported.put(BYPRODUCT, BigInteger.valueOf(7L));
            selfReported.put(PRODUCT, BigInteger.valueOf(1_000L));

            assertTrue(UselessBigIntegerReceipt.selfReportDelta(planned, selfReported).isEmpty(),
                    "逐键相等＝空表，与键的先后无关");
            var described = UselessBigIntegerReceipt.describeSelfReportDelta(
                    UselessBigIntegerReceipt.selfReportDelta(planned, selfReported));
            assertTrue(described.contains("自报清单"), "对平的主体是自报清单，不是交付：" + described);
            assertTrue(described.contains("非") || described.contains("不证明"),
                    "这一行必须自带'看不出落库'的免责：" + described);
            assertTrue(!described.contains("已按实际入网量交付"),
                    "不许再出现那副把计划量说成已交付的口径：" + described);
        }

        /** 绑定前终局按原契约丢弃，不缓冲、不在随后绑定时重放。 */
        void terminalBeforeBindIsDroppedAndNeverReplayed() {
            var receipt = new UselessBigIntegerReceipt("test-machine");
            var id = java.util.UUID.randomUUID();
            receipt.noteBatchId(id);
            var before = UselessBigIntegerReceipt.terminalDroppedNoLedger();
            receipt.deliverTerminal(id,
                    git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
            assertEquals(before + 1L, UselessBigIntegerReceipt.terminalDroppedNoLedger());
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            receipt.bindTerminal((batch, outcome) -> calls.incrementAndGet(), id);
            assertEquals(0, calls.get());
            receipt.deliverTerminal(id,
                    git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
            assertEquals(1, calls.get());
        }

        /** 绑定后错误批次标识只计丢弃，不改变台账或交割量。 */
        void wrongTerminalIdentityCannotSettleBoundLedger() {
            var receipt = new UselessBigIntegerReceipt("test-machine");
            var id = java.util.UUID.randomUUID();
            receipt.noteBatchId(id);
            var ledger = new git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedgerImpl();
            var pattern = new appeng.api.crafting.IPatternDetails() {
                public AEItemKey getDefinition() { return AEItemKey.of(net.minecraft.world.item.Items.GLOWSTONE); }
                public IInput[] getInputs() { return new IInput[0]; }
                public java.util.List<appeng.api.stacks.GenericStack> getOutputs() { return java.util.List.of(); }
            };
            ledger.noteAdmitted(receipt, pattern, BigInteger.ONE, 10L, null);
            var before = UselessBigIntegerReceipt.terminalDroppedIdMismatch();
            receipt.deliverTerminal(java.util.UUID.randomUUID(),
                    git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalLedger.Outcome.SUCCESS);
            assertEquals(before + 1L, UselessBigIntegerReceipt.terminalDroppedIdMismatch());
            assertEquals(1, ledger.inflightBatchCount());
            assertEquals(0L, ledger.coverageCounters().terminalSuccess());
        }

        private static Map<AEKey, BigInteger> totals(AEKey key, long amount) {
            var totals = new LinkedHashMap<AEKey, BigInteger>();
            totals.put(key, BigInteger.valueOf(amount));
            return totals;
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.realOutputClaimKeepsBigIntegerAndIgnoresPlannedCounts", () -> new UselessBigIntegerReceiptCases().realOutputClaimKeepsBigIntegerAndIgnoresPlannedCounts()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.terminalReleasesActualOutputReceiverEvenWithoutTerminalLedger", () -> new UselessBigIntegerReceiptCases().terminalReleasesActualOutputReceiverEvenWithoutTerminalLedger()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.plannedCounterIsTheSelfReportedPlanNotAnInsertReceipt", () -> new UselessBigIntegerReceiptCases().plannedCounterIsTheSelfReportedPlanNotAnInsertReceipt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.plannedKeyBeyondLongRetainsTheWholeBatch", () -> new UselessBigIntegerReceiptCases().plannedKeyBeyondLongRetainsTheWholeBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.shortfallAgainstTheDeclarationStaysVisibleInTheDelta", () -> new UselessBigIntegerReceiptCases().shortfallAgainstTheDeclarationStaysVisibleInTheDelta()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.declaredKeyMissingFromTheSelfReportReadsAsShortfallNotAsBalanced", () -> new UselessBigIntegerReceiptCases().declaredKeyMissingFromTheSelfReportReadsAsShortfallNotAsBalanced()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.equalSelfReportReadsAsBalancedWithoutClaimingDelivery", () -> new UselessBigIntegerReceiptCases().equalSelfReportReadsAsBalancedWithoutClaimingDelivery()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.terminalBeforeBindIsDroppedAndNeverReplayed", () -> new UselessBigIntegerReceiptCases().terminalBeforeBindIsDroppedAndNeverReplayed()),
                () -> org.junit.jupiter.api.Assertions.assertAll("UselessBigIntegerReceiptCases.wrongTerminalIdentityCannotSettleBoundLedger", () -> new UselessBigIntegerReceiptCases().wrongTerminalIdentityCannotSettleBoundLedger())
            );
        }
    }
}
