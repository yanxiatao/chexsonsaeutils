package git.chexson.chexsonsaeutils.parts.expression;

import java.util.List;

public interface MultiLevelEmitterExpressionPlan {
    boolean evaluate(List<Boolean> slotResults);

    int highestReferencedSlot();
}
