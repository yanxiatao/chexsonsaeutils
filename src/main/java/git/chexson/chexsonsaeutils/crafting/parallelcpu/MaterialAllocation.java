package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * 执行作业的材料归属账：提交配给、封套许可与成功消费分别登记，内部始终保留宽域真值。
 * 样板按对象身份认账，材料按精确键登记、按物品本体族计算配额。
 * 写入口由提交及成功推送流程调用，诊断只读金额与不可变行，不取得可写账本。
 */
interface MaterialAllocation {

    /** 保存指定任务的配给、封套和已消费真值，零值在册必须保留以维持硬配额语义。 */
    CompoundTag writePatternToNBT(IPatternDetails pattern, HolderLookup.Provider registries);

    /** 恢复账本并绑定到本次解码的样板身份，不按剩余任务量重新分配材料。 */
    void readPatternFromNBT(IPatternDetails pattern, CompoundTag data,
            HolderLookup.Provider registries);

    /** 配给的实际来源，用于诊断 src；仅记录来源，不作为阶段或重复调用保护开关。 */
    enum Source {
        /** 作业尚未建立提交配给。 */
        NONE,
        /** 提交时复制规划契约提供的配给。 */
        PLAN,
        /** 提交时按候选顺位排水并登记配给。 */
        SUBMIT
    }

    /**
     * 精确键的一笔不可变金额，供诊断遍历；零值在册仍保留该行。
     * @param key 原账登记的精确键
     * @param amount 该键的宽域真值，不经过 long 饱和
     */
    record KeyAmount(AEKey key, PlanAmount amount) {
    }

    /**
     * 一张身份样板的只读快照；三个键序均沿用各自账本登记序，列表不能修改。
     * @param pattern 归属账实际使用的样板身份，不能按 equals 合并
     * @param assigned 配给，包含封套许可与零值在册
     * @param envelopes 封套许可；只在诊断核实排水实记时扣除，不作为实物供给
     * @param eaten 成功提走的精确键金额
     */
    record Row(IPatternDetails pattern, List<KeyAmount> assigned,
            List<KeyAmount> envelopes, List<KeyAmount> eaten) {
        public Row {
            assigned = List.copyOf(assigned);
            envelopes = List.copyOf(envelopes);
            eaten = List.copyOf(eaten);
        }
    }

    /**
     * 提交时复制规划账，切断规划对象与执行收窄之间的可写别名。
     * 配给为空时返回 false，提交流程才转入排水登记；封套不决定是否需要排水。
     */
    boolean adopt(Map<IPatternDetails, WideKeyCounter> planned,
            Map<IPatternDetails, WideKeyCounter> envelopes);

    /**
     * 提交排水开始处理一张样板时登记其身份账；即使全部槽均为模具，也保留空账覆盖面。
     * 空账不构成配额依据，该入口不在诊断计算中调用。
     */
    void registerSubmitPattern(IPatternDetails pattern);

    /**
     * 提交排水逐键唯一登记口；算法由 CPU 持有，本账只登记每张样板每键的绝对配给增量。
     * 非正增量保留原有零值在册语义，不抵扣已登记的正配给。
     */
    void mintShare(IPatternDetails pattern, AEKey key, BigInteger amount);

    /** 成功推送后登记真实消费；long 单笔可复加成超过 long 的账量，非正数不记。 */
    void recordConsumption(IPatternDetails pattern, AEKey key, long amount);

    /** 大批成功推送的真实消费登记；全程宽域，不先转为 long，非正数不记。 */
    void recordConsumption(IPatternDetails pattern, AEKey key, BigInteger amount);

    /**
     * 部分提交刚到账、尚未执行消费时，按执行顺位摊派一份族供给并只收窄配给。
     * 调用方先排除无限源；供给为族到账＋到账之外来源＋族计划自产，均为精确量。
     * @param executionOrder 当前执行顺位，身份尾项按相等任务的顺位追加
     * @param key 被收窄的族中任一精确键
     * @param arrived 该族提交实际到账量，缺席视为零
     * @param outsideArrivals 该族到账轴之外的有效来源量，缺席视为零
     * @param plannedEmit 该族提交时的精确自产快照
     */
    void narrowArrival(Iterable<IPatternDetails> executionOrder, AEKey key,
            @Nullable PlanAmount arrived, @Nullable PlanAmount outsideArrivals,
            PlanAmount plannedEmit);

    /** 配给的实际建账来源；诊断据此选择对账口径，读取不重建配给。 */
    Source source();

    /** 是否存在身份配给账；用于诊断及没有配给的恢复作业判定。 */
    boolean hasAllocation();

    /** 身份配给账的数量，保持原 assigned= 覆盖面口径。 */
    int allocatedPatternCount();

    /** 该身份样板是否有非空配给账；有零值键的账仍算非空。 */
    boolean hasPatternQuota(@Nullable IPatternDetails pattern);

    /** 该身份样板在该材料族是否存在配额依据，零值在册也返回 true。 */
    boolean hasQuotaBasis(@Nullable IPatternDetails pattern, @Nullable AEKey key);

    /** 逐精确键读取配给；键未登记时为零，不向外暴露 counter。 */
    PlanAmount assignedAmount(IPatternDetails pattern, AEKey key);

    /** 逐精确键读取封套许可；该值不能作为实际供给。 */
    PlanAmount envelopeAmount(IPatternDetails pattern, AEKey key);

    /** 逐精确键读取真实消费；键未登记时为零。 */
    PlanAmount eatenAmount(IPatternDetails pattern, AEKey key);

    /** 族配给合计，沿用仅正金额参与合计的既有口径。 */
    PlanAmount assignedFamilyAmount(IPatternDetails pattern, AEKey key);

    /** 族真实消费合计，沿用仅正金额参与合计的既有口径。 */
    PlanAmount eatenFamilyAmount(IPatternDetails pattern, AEKey key);

    /** 原始族余额＝配给－消费，允许负值，供 left/over 诊断保留超额证据。 */
    PlanAmount rawFamilyBalance(IPatternDetails pattern, AEKey key);

    /**
     * 执行配额实时读口；没有非空身份账返回 null，存在身份账但该族缺席返回零。
     * 消费后余额夹零；带槽、模具与无限源语义仍由 reservation 判定，不在本账复写。
     */
    @Nullable PlanAmount remainingFamilyQuota(IPatternDetails pattern, AEKey key);

    /**
     * 冷路径的不可变行快照：任务身份先按 executionOrder，配给尾身份按相等任务顺位。
     * 孤立封套身份也交出，避免诊断漏减原账中不伴随配给的封套。
     * 领不到任务顺位的身份按首次登记序；规划输入若无序，这一档不承诺跨会话稳定。
     * 合法规划身份均有相等任务顺位，登记序仅为任务表之外的确定读法。
     * 此快照不用于执行配额，reservation 应持同一接口实时读取金额。
     */
    List<Row> rows(Iterable<IPatternDetails> executionOrder);
}
