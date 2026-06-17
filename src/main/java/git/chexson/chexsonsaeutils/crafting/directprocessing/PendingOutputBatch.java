package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public record PendingOutputBatch(
        List<GenericStack> payload,
        @Nullable ProcessingLatencyOrigin latencyOrigin,
        @Nullable UUID sourceCraftingId
) {
    public PendingOutputBatch {
        payload = payload == null ? List.of() : List.copyOf(payload);
    }

    public boolean isEmpty() {
        return payload.isEmpty();
    }

    public PendingOutputBatch withPayload(List<GenericStack> newPayload) {
        return new PendingOutputBatch(newPayload, latencyOrigin, sourceCraftingId);
    }
}
