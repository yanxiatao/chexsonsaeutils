package git.chexson.chexsonsaeutils.network.matrix;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁定搜索回包的位图口径：尾部空字压缩但绝不压成空集，
 * 因为客户端把空位图读成「取消过滤」，零命中必须是「过滤后没有结果」。
 */
class PatternMatrixMatchesPayloadTest {

    @Test
    void trimsTrailingEmptyWords() {
        PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(7, 2, new long[]{0b101L, 0L, 0L});

        assertEquals(List.of(5L), payload.matchBits());
        assertArrayEquals(new long[]{5L}, payload.bitsArray());
        assertEquals(7, payload.containerId());
        assertEquals(2, payload.matchCount());
    }

    @Test
    void keepsOneWordWhenNothingMatched() {
        PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(1, 0, new long[10]);

        assertEquals(1, payload.matchBits().size());
        assertEquals(1, payload.bitsArray().length);
    }

    @Test
    void highSlotMatchesSurviveTheTrim() {
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
