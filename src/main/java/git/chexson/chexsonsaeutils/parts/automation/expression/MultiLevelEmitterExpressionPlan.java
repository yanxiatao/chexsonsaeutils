package git.chexson.chexsonsaeutils.parts.automation.expression;

import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;

import java.util.List;

public interface MultiLevelEmitterExpressionPlan {
    default boolean evaluate(List<Boolean> slotResults) {
        return evaluateParticipating(MultiLevelEmitterPart.asParticipatingSlots(slotResults)).result();
    }

    MultiLevelEmitterPart.AggregationResult evaluateParticipating(List<MultiLevelEmitterPart.SlotEvaluation> slotResults);

    int highestReferencedSlot();
}
