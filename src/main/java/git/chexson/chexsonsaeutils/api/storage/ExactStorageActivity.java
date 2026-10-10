package git.chexson.chexsonsaeutils.api.storage;

/** 精确入口绕过 long 提取时，维持驱动器状态灯和活动通知。 */
public interface ExactStorageActivity {
    /** 只在真实库存变化后调用，模拟不通知。 */
    void exactStorageChanged();
}
