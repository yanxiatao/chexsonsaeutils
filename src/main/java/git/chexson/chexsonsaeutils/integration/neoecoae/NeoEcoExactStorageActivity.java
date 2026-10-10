package git.chexson.chexsonsaeutils.integration.neoecoae;

/** 精确插入绕过 long 入口时，通知驱动器刷新单元状态。 */
public interface NeoEcoExactStorageActivity {
    /** 仅在真实插入成功后调用，模拟不得触发活动回调。 */
    void exactStorageInserted();
}
