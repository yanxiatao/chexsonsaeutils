package git.chexson.chexsonsaeutils.network.directprocessing.rules;

/** 接收服务器整合包规则，供客户端标记槽执行相同的放入校验。 */
public interface DirectProcessingPackRulesTarget {
    /** 应用当前菜单的服务器规则快照。 */
    void applyPackRulesSnapshot(String json);
}
