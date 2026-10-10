package git.chexson.chexsonsaeutils.network.matrix;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁定搜索回包的位图口径（D6b 两侧同一把尺）：{@code of()} 与线格式都保留生产者全长位图，
 * 不裁尾、不补回；「取消过滤」只由空查询腿显式下发空集合表达，
 * 带查询的零命中腿是「过滤后没有结果」的全长零位图。
 */
class PatternMatrixMatchesPayloadTest {

    @Test
    void keepsFullLengthWordsWithoutProducerTrim() {
        PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(7, 2, new long[]{0b101L, 0L, 0L});

        assertEquals(List.of(5L, 0L, 0L), payload.matchBits());
        assertArrayEquals(new long[]{5L, 0L, 0L}, payload.bitsArray());
        assertEquals(7, payload.containerId());
        assertEquals(2, payload.matchCount());
    }

    @Test
    void zeroHitKeepsEveryWordOfTheFullBitmap() {
        PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(1, 0, new long[10]);

        assertEquals(10, payload.matchBits().size());
        assertEquals(10, payload.bitsArray().length);
    }

    @Test
    void highSlotMatchesSurviveAtFullLength() {
        PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(1, 1, new long[]{0L, 0L, 0L, 1L});

        assertEquals(4, payload.matchBits().size());
        assertEquals(1L, payload.bitsArray()[3]);
    }

    @Test
    void blankQueryReplyMeansFilterCancelled() {
        PatternMatrixMatchesPayload payload = new PatternMatrixMatchesPayload(1, 0, List.of());

        assertEquals(0, payload.bitsArray().length);
    }

    @Test
    void visibleRequestSlotsDropJunkAndKeepOrder() {
        List<Integer> slots = new ArrayList<>();
        slots.add(3);
        slots.add(null);
        slots.add(-2);
        slots.add(4);

        assertArrayEquals(new int[]{3, 4}, PatternMatrixVisibleRequestPayload.toSlotArray(slots));
    }

    @Test
    void visibleRequestSlotsAreCappedAtTheAllowedWindow() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < PatternMatrixVisibleRequestPayload.MAX_SLOTS + 10; slot++) {
            slots.add(slot);
        }

        assertEquals(PatternMatrixVisibleRequestPayload.MAX_SLOTS,
                PatternMatrixVisibleRequestPayload.toSlotArray(slots).length);
    }
}
