package git.chexson.chexsonsaeutils.network.matrix;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 矩阵搜索与同步的行为场景；由类别测试统一调用。 */
public final class MatrixScenarios {
    private MatrixScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.network.matrix",
                () -> PatternMatrixMatchesPayloadCases.verify(temporaryRoot)
        );
    }

    /** PatternMatrixMatchesPayloadCases 的独立场景夹具，按类别统一执行。 */
    public static final class PatternMatrixMatchesPayloadCases {
        void keepsFullLengthWordsWithoutProducerTrim() {
            PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(7, 2, new long[]{0b101L, 0L, 0L});

            assertEquals(List.of(5L, 0L, 0L), payload.matchBits());
            assertArrayEquals(new long[]{5L, 0L, 0L}, payload.bitsArray());
            assertEquals(7, payload.containerId());
            assertEquals(2, payload.matchCount());
        }

        void zeroHitKeepsEveryWordOfTheFullBitmap() {
            PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(1, 0, new long[10]);

            assertEquals(10, payload.matchBits().size());
            assertEquals(10, payload.bitsArray().length);
        }

        void highSlotMatchesSurviveAtFullLength() {
            PatternMatrixMatchesPayload payload = PatternMatrixMatchesPayload.of(1, 1, new long[]{0L, 0L, 0L, 1L});

            assertEquals(4, payload.matchBits().size());
            assertEquals(1L, payload.bitsArray()[3]);
        }

        void blankQueryReplyMeansFilterCancelled() {
            PatternMatrixMatchesPayload payload = new PatternMatrixMatchesPayload(1, 0, List.of());

            assertEquals(0, payload.bitsArray().length);
        }

        void visibleRequestSlotsDropJunkAndKeepOrder() {
            List<Integer> slots = new ArrayList<>();
            slots.add(3);
            slots.add(null);
            slots.add(-2);
            slots.add(4);

            assertArrayEquals(new int[]{3, 4}, PatternMatrixVisibleRequestPayload.toSlotArray(slots));
        }

        void visibleRequestSlotsAreCappedAtTheAllowedWindow() {
            List<Integer> slots = new ArrayList<>();
            for (int slot = 0; slot < PatternMatrixVisibleRequestPayload.MAX_SLOTS + 10; slot++) {
                slots.add(slot);
            }

            assertEquals(PatternMatrixVisibleRequestPayload.MAX_SLOTS,
                    PatternMatrixVisibleRequestPayload.toSlotArray(slots).length);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PatternMatrixMatchesPayloadCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixMatchesPayloadCases.keepsFullLengthWordsWithoutProducerTrim", () -> new PatternMatrixMatchesPayloadCases().keepsFullLengthWordsWithoutProducerTrim()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixMatchesPayloadCases.zeroHitKeepsEveryWordOfTheFullBitmap", () -> new PatternMatrixMatchesPayloadCases().zeroHitKeepsEveryWordOfTheFullBitmap()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixMatchesPayloadCases.highSlotMatchesSurviveAtFullLength", () -> new PatternMatrixMatchesPayloadCases().highSlotMatchesSurviveAtFullLength()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixMatchesPayloadCases.blankQueryReplyMeansFilterCancelled", () -> new PatternMatrixMatchesPayloadCases().blankQueryReplyMeansFilterCancelled()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixMatchesPayloadCases.visibleRequestSlotsDropJunkAndKeepOrder", () -> new PatternMatrixMatchesPayloadCases().visibleRequestSlotsDropJunkAndKeepOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PatternMatrixMatchesPayloadCases.visibleRequestSlotsAreCappedAtTheAllowedWindow", () -> new PatternMatrixMatchesPayloadCases().visibleRequestSlotsAreCappedAtTheAllowedWindow())
            );
        }
    }
}
