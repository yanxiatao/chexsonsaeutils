package git.chexson.chexsonsaeutils.menu.matrix;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms;
import appeng.api.inventories.InternalInventory;
import net.minecraft.world.item.ItemStack;

/**
 * 装配矩阵同步与交互所需的最小宿主能力：一台机器只要暴露全量样板库存、
 * 每格的展示栈与放置判定，就能接入同一套下发与点击处理。
 */
public interface PatternMatrixSource {

    /** 全量样板库存（与 AE2 样板管理终端读到同一份数据）。 */
    InternalInventory patternMatrixInventory();

    int patternMatrixTotalSlots();

    /** 该格应在客户端渲染成什么：一般是样板的产出物，避免下发整份配方。 */
    ItemStack patternMatrixDisplayStack(int slot);

    /** 该格的状态码，语义由具体机器的界面解释；无状态机统一返回 0。 */
    int patternMatrixStatusCode(int slot);

    /** 该格的原因码，语义由具体机器的界面解释；无状态机统一返回 0。 */
    int patternMatrixReasonCode(int slot);

    /**
     * 展示口径版本：状态/原因等派生数据在样板本体没变时也会重算（如配方索引热加载），
     * 递增此值可让下发引擎放弃快照、重发这些格子。无派生数据的机器恒返回 0。
     */
    long patternMatrixDetailVersion();

    /** 需要客户端本地化搜索的宿主提供输入输出摘要，其他宿主使用服务端搜索。 */
    default PatternMatrixSearchTerms patternMatrixSearchTerms(int slot) {
        return PatternMatrixSearchTerms.EMPTY;
    }

    boolean patternMatrixMayPlace(ItemStack stack);

    /**
     * 全量槽位搜索：命中槽在 {@code matchBits} 上置位（第 i 槽对应 {@code bits[i>>6]} 的 {@code i&63} 位），
     * 返回命中数。界面用它做整行过滤与命中着色。
     */
    int patternMatrixSearchMatches(String query, long[] matchBits);
}
