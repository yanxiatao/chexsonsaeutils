package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import java.util.List;

public final class NeoEcoBatchOutputBuffer {

    private NeoEcoBatchOutputBuffer() {
    }

    public static boolean append(
            KeyCounter target,
            List<GenericStack> outputs,
            List<GenericStack> remainders,
            long count
    ) {
        if (count <= 0L) {
            return false;
        }
        boolean appended = appendAll(target, outputs, count);
        return appendAll(target, remainders, count) || appended;
    }

    private static boolean appendAll(KeyCounter target, List<GenericStack> stacks, long count) {
        boolean appended = false;
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            long current = Math.max(0L, target.get(stack.what()));
            long remaining = Long.MAX_VALUE - current;
            long multiplied = saturatingMultiply(stack.amount(), count);
            long delta = Math.min(remaining, multiplied);
            if (delta > 0L) {
                target.add(stack.what(), delta);
                appended = true;
            }
        }
        return appended;
    }

    private static long saturatingMultiply(long amount, long count) {
        return amount > Long.MAX_VALUE / count ? Long.MAX_VALUE : amount * count;
    }
}
