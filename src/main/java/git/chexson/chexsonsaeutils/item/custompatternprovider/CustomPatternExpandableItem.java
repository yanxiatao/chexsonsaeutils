package git.chexson.chexsonsaeutils.item.custompatternprovider;

/**
 * 可扩容样板供应器物品标记接口（阶段 1 共享层泛化）。
 * <p>
 * 动机：扩容 GUI（{@link git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeMenu}）
 * 原硬编码 instanceof CustomPatternItem；定制样板供应器（阶段 2 新方块）物品同样支持
 * 扩容（CUSTOM_PATTERN_PAGES 组件语义），故抽象为标记接口，实现类即视为可扩容对象。
 * <p>
 * 泛化方案选择：相比「实现 IStorageComponent 且携带 CUSTOM_PATTERN_PAGES 组件」的
 * 隐式判断，标记接口是显式契约——扩容判断零成本（instanceof），且不误伤其他
 * IStorageComponent（存储元件等无页数语义的物品不会被扩容），框架样板供应器物品
 * 行为完全不变（实现本接口即保持原可扩容性）。
 * <p>
 * 实现要求：实现类应同时实现 {@link appeng.api.implementations.items.IStorageComponent}
 * （可放入扩容 GUI 存储槽），并携带 CUSTOM_PATTERN_PAGES
 * 组件语义（getOrDefault 读取、set 写入，见
 * {@link git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent#CUSTOM_PATTERN_PAGES}）。
 */
public interface CustomPatternExpandableItem {
}
