package git.chexson.chexsonsaeutils.network.planning;

import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import git.chexson.chexsonsaeutils.config.PlanningServerConfig;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;

import java.util.List;

/** 排序协议的纯 DTO 校验与稳定字符串 ID 转换。 */
public final class PlanningOrderProtocol {
    public static final int COUNT = 4;

    private PlanningOrderProtocol() {
    }

    public static List<PlanningAlgorithmId> decode(List<String> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("Planning algorithm IDs must not be null");
        }
        List<PlanningAlgorithmId> parsed = ids.stream()
                .map(id -> PlanningAlgorithmId.parse(id).orElseThrow(
                        () -> new IllegalArgumentException("Unknown planning algorithm ID: " + id)))
                .toList();
        return PlanningServerConfig.requireCompleteOrder(parsed);
    }

    public static List<String> encode(List<PlanningAlgorithmId> order) {
        return order.stream().map(Enum::name).toList();
    }

    public static List<String> encode(PlanningAlgorithmOrderService.Snapshot snapshot) {
        return encode(snapshot.order());
    }
}
