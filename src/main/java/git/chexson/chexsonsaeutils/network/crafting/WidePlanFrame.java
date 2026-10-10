package git.chexson.chexsonsaeutils.network.crafting;

/** 确认菜单的精确账本接收状态；分片完整后才允许显示对应计划。 */
public interface WidePlanFrame {
    /** 接收带版本的分片，防止重新规划时混入旧计划数量。 */
    void accept(long revision, int index, int count, String data);

    /** 读取菜单当前版本的完整账本；未收齐或版本不符时返回空串。 */
    String completed(long revision);
}
