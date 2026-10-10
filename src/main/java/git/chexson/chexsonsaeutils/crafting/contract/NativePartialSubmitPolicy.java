package git.chexson.chexsonsaeutils.crafting.contract;

/** 自有 CPU 的原生部分提交策略；提交层依据行为契约识别，避免引用具体 CPU 实现。 */
public interface NativePartialSubmitPolicy {
    /** 是否可交由 AE2 原生 CPU 的部分提交流程处理；自有并行 CPU 使用自己的提交路径。 */
    boolean supportsNativePartialSubmit();
}
