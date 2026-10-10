package git.chexson.chexsonsaeutils.terminal.display;

import java.util.List;
import java.util.function.ToIntFunction;

/** 完整数量的 tooltip 换行契约，测量由客户端字体提供，不改变或截断任何字符。 */
public interface TerminalTooltipLines {
    /** 按像素宽度拆分全部文本；返回不可变行，拼接后必须等于输入原文。 */
    List<String> wrap(String text, int width, ToIntFunction<String> measure);
}
