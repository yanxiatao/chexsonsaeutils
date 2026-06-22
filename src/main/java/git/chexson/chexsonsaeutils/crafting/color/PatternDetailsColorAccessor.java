package git.chexson.chexsonsaeutils.crafting.color;

import org.jetbrains.annotations.Nullable;

/**
 * 样板颜色读取接口。
 *
 * 供染色样板相关代码读取编码样板的颜色元数据。
 */
public interface PatternDetailsColorAccessor {

    /**
     * 读取样板颜色。
     *
     * @return RGB 颜色值，未染色时返回 -1。
     */
    int chexsonsaeutils$getColor();

    /**
     * 判断是否带有可展示的颜色。
     *
     * @return 有颜色返回 true。
     */
    default boolean chexsonsaeutils$hasColor() {
        return chexsonsaeutils$getColor() != -1;
    }
}
