package git.chexson.chexsonsaeutils.network.planning;

import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import git.chexson.chexsonsaeutils.config.PlanningServerConfig;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;

import java.util.Arrays;
import java.util.List;

/** 排序协议的纯 DTO 校验与稳定字符串 ID 转换。 */
public final class PlanningOrderProtocol {

    /**
     * 排序族线格式共用上限（D6b：写侧与读侧同一把尺，取值由字段合法语义的确定上界导出，
     * 不是"够大就行"的魔数）：线上 ids 必须是算法全集的一个排列。
     */
    public static final int MAX_PLANNING_ALGORITHM_IDS = PlanningAlgorithmId.values().length;

    /** 单个算法 ID 的确定上界＝枚举名最大长度。 */
    public static final int MAX_PLANNING_ID_LENGTH = Arrays.stream(PlanningAlgorithmId.values())
            .mapToInt(id -> id.name().length())
            .max()
            .orElseThrow();

    /** 结果字段的确定上界＝{@link PlanningOrderStatePayload.Result} 枚举名最大长度。 */
    public static final int MAX_RESULT_NAME_LENGTH = Arrays.stream(PlanningOrderStatePayload.Result.values())
            .mapToInt(result -> result.name().length())
            .max()
            .orElseThrow();

    private PlanningOrderProtocol() {
    }

    public static List<String> decode(List<String> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("Planning algorithm IDs must not be null");
        }
        // 线上串先过枚举这一头的认档与归一（trim＋转大写根），认不出当场判错；归一结果以串交回，
        // 完整排列的判据在 config 侧的串名册上，本包不引能力类型到 config。
        List<String> canonical = ids.stream()
                .map(id -> PlanningAlgorithmId.parse(id).orElseThrow(
                        () -> new IllegalArgumentException("Unknown planning algorithm ID: " + id)).name())
                .toList();
        return PlanningServerConfig.requireCompleteOrder(canonical);
    }

    public static List<String> encode(List<PlanningAlgorithmId> order) {
        return order.stream().map(Enum::name).toList();
    }

    public static List<String> encode(PlanningAlgorithmOrderService.Snapshot snapshot) {
        // 快照里的序已是串名册形状，编码这一头只剩串拷贝。
        return List.copyOf(snapshot.order());
    }
}
