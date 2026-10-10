package git.chexson.chexsonsaeutils.parts.automation.expression;

import git.chexson.chexsonsaeutils.parts.automation.model.MultiLevelEmitterRules;

import java.util.List;

public interface MultiLevelEmitterExpressionPlan {
    default boolean evaluate(List<Boolean> slotResults) {
        return evaluateParticipating(MultiLevelEmitterRules.asParticipatingSlots(slotResults)).result();
    }

    MultiLevelEmitterRules.AggregationResult evaluateParticipating(List<MultiLevelEmitterRules.SlotEvaluation> slotResults);

    int highestReferencedSlot();
}
