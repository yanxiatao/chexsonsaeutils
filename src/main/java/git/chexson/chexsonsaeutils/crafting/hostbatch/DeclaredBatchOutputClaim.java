package git.chexson.chexsonsaeutils.crafting.hostbatch;

/** 可认领真实产物的声明批凭据；与仅报告计划和终局的凭据分开声明能力。 */
public interface DeclaredBatchOutputClaim extends DeclaredBatchReceipt {
    /** CPU 登记精确等待量后绑定实物入口；机器在后续交付阶段才允许调用。 */
    void bindOutputReceiver(DeclaredBatchOutputReceiver receiver);
}
