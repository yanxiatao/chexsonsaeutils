package git.chexson.chexsonsaeutils.network.planning;

import git.chexson.chexsonsaeutils.config.PlanningServerConfig;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 网络协议的行为场景；由类别测试统一调用。 */
public final class ProtocolScenarios {
    private ProtocolScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.network.planning",
                () -> PlanningOrderProtocolCases.verify(temporaryRoot)
        );
    }

    /** PlanningOrderProtocolCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanningOrderProtocolCases {
        void roundTripsStableEnumNames() {
            // 线上格式就是串名册：写侧交串、读侧还回同一串序（decode 不再吐枚举）。
            List<String> encoded = PlanningOrderProtocol.encode(
                    List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE));
            List<String> decoded = PlanningOrderProtocol.decode(encoded);

            assertEquals(PlanningServerConfig.DEFAULT_ORDER, decoded);
            assertEquals("GRAPH", encoded.getFirst());
        }

        void rejectsWrongCountDuplicateAndUnknownIds() {
            assertThrows(IllegalArgumentException.class, () -> PlanningOrderProtocol.decode(
                    List.of("GRAPH")));
            assertThrows(IllegalArgumentException.class, () -> PlanningOrderProtocol.decode(
                    List.of("GRAPH", "GRAPH")));
            assertThrows(IllegalArgumentException.class, () -> PlanningOrderProtocol.decode(
                    List.of("GRAPH", "UNKNOWN")));
            // 协议这一头<b>不</b>做静默退化：旧客户端送来 3 元序（含退役的冻结路线档）必须当场判错，
            // 与 normalizeOrder 对磁盘值的"丢掉→恰好完整→照收"是两条不同的腿。
            assertThrows(IllegalArgumentException.class, () -> PlanningOrderProtocol.decode(
                    List.of("FROZEN_ROUTE", "GRAPH", "NATIVE")));
        }

        void mapsServiceStatusWithoutOrdinalProtocol() {
            assertEquals(PlanningOrderStatePayload.Result.UPDATED,
                    PlanningOrderUpdatePayload.mapResult(
                            git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService.UpdateStatus.UPDATED));
            assertEquals("NATIVE",
                    PlanningOrderProtocol.decode(
                            PlanningOrderProtocol.encode(
                                    List.of(PlanningAlgorithmId.GRAPH, PlanningAlgorithmId.NATIVE))).get(1));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanningOrderProtocolCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningOrderProtocolCases.roundTripsStableEnumNames", () -> new PlanningOrderProtocolCases().roundTripsStableEnumNames()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningOrderProtocolCases.rejectsWrongCountDuplicateAndUnknownIds", () -> new PlanningOrderProtocolCases().rejectsWrongCountDuplicateAndUnknownIds()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanningOrderProtocolCases.mapsServiceStatusWithoutOrdinalProtocol", () -> new PlanningOrderProtocolCases().mapsServiceStatusWithoutOrdinalProtocol())
            );
        }
    }
}
