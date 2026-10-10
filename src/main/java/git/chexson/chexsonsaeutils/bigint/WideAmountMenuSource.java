package git.chexson.chexsonsaeutils.bigint;

/**
 * AE2 的 {@code CraftConfirmMenu} 由 mixin 注入宽域同步字段（M1）；该字段编译期不在
 * AE2 类上，客户端经此接口按引用读取。与合成求解侧的菜单溯源宿主接口同构：注入字段的
 * mixin 类实现本接口，读取方（我们的确认屏）把菜单向下转型到本接口。
 */
public interface WideAmountMenuSource {

    /** M1 同步过来的宽域账编码串（可能为空串，表示无溢出键）。 */
    String chexsonsaeutils$syncedWideAmounts();
}
