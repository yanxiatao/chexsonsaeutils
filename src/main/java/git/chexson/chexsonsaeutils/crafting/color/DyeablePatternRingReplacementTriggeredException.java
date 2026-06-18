package git.chexson.chexsonsaeutils.crafting.color;

/**
 * 同色样板 ring replacement 的轻量控制流信号。
 */
final class DyeablePatternRingReplacementTriggeredException extends RuntimeException {
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
