package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import appeng.api.upgrades.IUpgradeableObject;

/**
 * 定制样板供应器宿主接口（方块与面板共用）。
 * <p>
 * 动机：{@link CustomPatternProviderMenu} 的泛型 T 有双边界
 * （{@link CustomPatternProviderLogicHost} & {@link IUpgradeableObject}），
 * MenuTypeBuilder 的 hostClass 需要能同时容纳方块实体与面板的类型字面量——
 * 两个接口的交集无法作为 Class 字面量，javac 也无法用接口交集推断构造器
 * 方法引用（T := 单个接口不满足另一边界），故定义本接口作为两者的公共超类型。
 * <p>
 * 成员作用：无新增方法，仅合并双边界；{@link CustomPatternProviderBlockEntity}
 * 与 {@link git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPart}
 * 均实现本接口，MenuTypeBuilder hostClass 使用本接口后，MenuOpener 对
 * BlockEntityLocator 与 PartLocator 的 host 解析都通过 instanceof 校验。
 */
public interface CustomPatternProviderHost extends CustomPatternProviderLogicHost, IUpgradeableObject {
}