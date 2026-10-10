package git.chexson.chexsonsaeutils.client.gui.config;

import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 客户端显示模型；协议标识显式一一映射到 common PlanningAlgorithmId。 */
public enum PlanningAlgorithm {
    GRAPH(PlanningAlgorithmId.GRAPH),
    NATIVE_AE2(PlanningAlgorithmId.NATIVE);

    private static final Map<PlanningAlgorithmId, PlanningAlgorithm> BY_PROTOCOL_ID = buildReverseMapping();

    private final PlanningAlgorithmId protocolId;

    PlanningAlgorithm(PlanningAlgorithmId protocolId) {
        this.protocolId = protocolId;
    }

    public String id() {
        return this.protocolId.name();
    }

    public PlanningAlgorithmId protocolId() {
        return this.protocolId;
    }

    public String translationKey() {
        return this.protocolId.translationKey();
    }

    public String descriptionKey() {
        return this.protocolId.descriptionKey();
    }

    public static PlanningAlgorithm fromProtocolId(PlanningAlgorithmId id) {
        PlanningAlgorithm algorithm = BY_PROTOCOL_ID.get(Objects.requireNonNull(id, "id"));
        if (algorithm == null) {
            throw new IllegalArgumentException("Unmapped planning algorithm id: " + id);
        }
        return algorithm;
    }

    private static Map<PlanningAlgorithmId, PlanningAlgorithm> buildReverseMapping() {
        EnumMap<PlanningAlgorithmId, PlanningAlgorithm> mapping = new EnumMap<>(PlanningAlgorithmId.class);
        for (PlanningAlgorithm algorithm : values()) {
            if (mapping.put(algorithm.protocolId, algorithm) != null) {
                throw new IllegalStateException("Duplicate planning algorithm mapping: " + algorithm.protocolId);
            }
        }
        if (mapping.size() != PlanningAlgorithmId.values().length) {
            throw new IllegalStateException("Planning algorithm mapping is incomplete");
        }
        return Map.copyOf(mapping);
    }
}
