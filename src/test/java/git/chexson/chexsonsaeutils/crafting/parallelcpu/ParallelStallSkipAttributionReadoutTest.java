package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停滞行<b>四格跳过归因</b>的用例：这四格每一格都对应任务循环里一条真 {@code continue} 的出口，
 * 所以两头都要钉住——既有牙，就不许退化成格式串里的字面量。
 *
 * <p>过去这里还有第五格 {@code defer=}，量的是任务循环头上那道"单发让路"。那一刀在自有并行 CPU
 * 这一路已经不是那颗种子的守门人：守门的是取料点上的环种留底（唯一那把尺），它按整批发数夹、
 * 而单发口径连"一批吃两枚"都看不见（实机 {@code taken=3} 那一次就是让它漏过去的）。
 * 判据与用力的证据都在
 * {@code ParallelRingSeedReserveAtExtractionTest#singleFiringYieldAddsNothingOnTopOfTheSeedReserve}：
 * 那里同时钉着"旧那一刀真跳的那一档由留底原样挡着"与"旧那一刀放行那一档留底照样挡着"。
 * 刀与它的读数一起删，原生 AE2 CPU 那一腿的让路不动（回落腿要保留）。
 */
final class ParallelStallSkipAttributionReadoutTest {

    /**
     * 读数钉成"喂进几个数就印几个数"：四格各随本轮计数，不许有哪一格钉死在某个常量上。
     *
     * <p>{@code noProvider=} 排在最前，正是最容易写死的一格——它过去和 {@code %d} 直接长在格式串里，
     * 谁把它换成常量都没人守。现在两档取值必须印出两个不同的数。
     */
    @Test
    void everyAttributionCellCarriesItsOwnRoundsNumberNotAFormatLiteral() {
        assertEquals(" noProvider=3 extractNull=4 pushRejected=5 materialBackoff=6",
                ParallelCraftingCpuLogic.describeSkipAttribution(3L, 4L, 5L, 6L));
        var oneNoProvider = ParallelCraftingCpuLogic.describeSkipAttribution(
                1L, 0L, 0L, 0L);
        var noneSkipped = ParallelCraftingCpuLogic.describeSkipAttribution(
                0L, 0L, 0L, 0L);
        assertNotEquals(noneSkipped, oneNoProvider,
                "noProvider 只走一条任务也必须看得出差别：这一格不许恒零");
        assertEquals(" noProvider=1 extractNull=0 pushRejected=0 materialBackoff=0", oneNoProvider);
        assertTrue(oneNoProvider.startsWith(" noProvider="),
                "格名与顺序不许漂（下游按空白切 token 认的就是这一格）：" + oneNoProvider);
        assertFalse(oneNoProvider.contains("  "), "一格之间只留一个空白：" + oneNoProvider);
        // 删掉的那一格不许以"恒零装饰"的形状回来：整串里再没有 defer 这个字。
        assertFalse(oneNoProvider.contains("defer"),
                "单发让路那一格已经随那一刀一起删了：" + oneNoProvider);
    }
}
