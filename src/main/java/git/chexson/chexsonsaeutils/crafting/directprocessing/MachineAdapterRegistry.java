package git.chexson.chexsonsaeutils.crafting.directprocessing;

import java.util.List;

public final class MachineAdapterRegistry {

    private static final MachineAdapterRegistry EMPTY = new MachineAdapterRegistry(List.of());

    private final List<MachineRecipeAdapter> adapters;

    public MachineAdapterRegistry(List<MachineRecipeAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public static MachineAdapterRegistry empty() {
        return EMPTY;
    }

    public List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity) {
        if (identity == null || adapters.isEmpty()) {
            return List.of();
        }
        for (MachineRecipeAdapter adapter : adapters) {
            List<RecipeTypeCandidate> candidates = adapter.resolveCandidates(identity);
            if (!candidates.isEmpty()) {
                return List.copyOf(candidates);
            }
        }
        return List.of();
    }

    /** 机器配方类型适配契约，由装配层提供实现并确定首命中优先级。 */
    public interface MachineRecipeAdapter {
        /** 根据绑定机器识别候选类型；不支持时返回空列表，首个非空结果终止后续适配。 */
        List<RecipeTypeCandidate> resolveCandidates(MachineIdentity identity);
    }
}
