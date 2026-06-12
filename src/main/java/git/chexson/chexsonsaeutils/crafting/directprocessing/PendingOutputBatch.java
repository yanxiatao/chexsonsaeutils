package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record PendingOutputBatch(
        List<GenericStack> payload,
        @Nullable ProcessingLatencyOrigin latencyOrigin
) {
    public PendingOutputBatch {
        payload = payload == null ? List.of() : List.copyOf(payload);
    }

    public boolean isEmpty() {
        return payload.isEmpty();
    }

    public PendingOutputBatch withPayload(List<GenericStack> newPayload) {
        return new PendingOutputBatch(newPayload, latencyOrigin);
    }
}
