package git.chexson.chexsonsaeutils.crafting.request;

import java.math.BigInteger;
import java.util.Optional;

/** 下单数量表达式：保持整数真值，支持科学记数、运算和 AEKey 单位换算。 */
public interface CraftingAmountExpression {
    /** 协议与结果的位数预算，防止网络请求制造无界数量。 */
    int MAX_LENGTH = 4096;
    /** 显示单位的小数点和运算符需要额外空间；协议仅传递有界最小单位整数。 */
    int MAX_INPUT_LENGTH = 8192;

    /**
     * 将显示单位表达式转换成最小存储单位；物品要求整数量，其他类型按原版规则向上取整。
     * @param expression 不含最终库存模式的前导等号；支持 +、-、*、/、^、括号和 e/E 指数
     * @param amountPerUnit AEKey 的每显示单位数量，必须为正
     * @return 正的精确数量；未完成、非法、非整数物品或超过预算的输入为空
     */
    Optional<BigInteger> parse(String expression, long amountPerUnit);

    /** 回填最小单位数量；显示值按足够精度向下舍入，重新解析并向上取整后必须恢复原数量。 */
    String format(BigInteger amount, long amountPerUnit);
}
