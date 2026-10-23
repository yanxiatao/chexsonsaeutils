package git.chexson.chexsonsaeutils.crafting.hostbatch;

/**
 * 已在接单期间生成真实缓冲产物的批量宿主，允许 CPU 在登记等待量并付清材料后立即认领。
 * 该能力不适用于需要后续 tick 加工的机器，也不得以计划产出代替真实缓冲。
 */
public interface ImmediateBatchOutputHost {
    /**
     * 将已生成的缓冲交给接收器，只扣除接收器实际接受的数量。
     * 调用方必须先完成本批等待量登记和材料交割；接收器负责同网检查。
     * 未被认领的余量继续保留在宿主中，由原有回流路径处理。
     *
     * @param receiver 发起 CPU 的真实产物接收口
     */
    void deliverBufferedBatchOutputs(DeclaredBatchOutputReceiver receiver);
}
