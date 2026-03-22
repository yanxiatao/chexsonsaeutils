package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterExpressionScreenTest {

    @Test
    void warningStateStillAllowsApply() {
        var result = MultiLevelEmitterScreen.validateExpressionDraft("#1 OR #2", 2, slotIndex -> slotIndex == 0);

        assertTrue(result.hasWarnings());
        assertFalse(result.isInvalid());
        assertTrue(MultiLevelEmitterScreen.canApplyExpression(result));
    }

    @Test
    void invalidStateDisablesApply() {
        var result = MultiLevelEmitterScreen.validateExpressionDraft("#4", 2, slotIndex -> true);

        assertTrue(result.isInvalid());
        assertFalse(MultiLevelEmitterScreen.canApplyExpression(result));
    }

    @Test
    void wrapSelectionAddsParenthesesWithoutReorderingText() {
        String wrapped = MultiLevelEmitterScreen.wrapSelectionWithParentheses("#1 OR #2", 0, 8);

        assertEquals("(#1 OR #2)", wrapped);
    }

    @Test
    void slotChipInsertionReplacesCurrentReferenceToken() {
        String replaced = MultiLevelEmitterScreen.insertSlotReference("#1 OR #2", 7, 7, 4);

        assertEquals("#1 OR #4", replaced);
    }

    @Test
    void dirtyExpressionDraftResetsWhenConfiguredSlotCountChanges() {
        MultiLevelEmitterScreen.ExpressionDraftSyncDecision decision =
                MultiLevelEmitterScreen.resolveExpressionDraftSync("#1 OR #2 OR #3", true, "#1 OR #2", 3, 2);

        assertEquals("#1 OR #2", decision.draftText());
        assertFalse(decision.dirty());
        assertTrue(decision.topologyReset());
    }
}
