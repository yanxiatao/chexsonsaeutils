package git.chexson.chexsonsaeutils.crafting;

public final class AeExternalIngressContext {

    private static final ThreadLocal<Integer> EXTERNAL_INGRESS_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private AeExternalIngressContext() {
    }

    public static void enter() {
        EXTERNAL_INGRESS_DEPTH.set(EXTERNAL_INGRESS_DEPTH.get() + 1);
    }

    public static void exit() {
        int nextDepth = EXTERNAL_INGRESS_DEPTH.get() - 1;
        if (nextDepth <= 0) {
            EXTERNAL_INGRESS_DEPTH.remove();
            return;
        }
        EXTERNAL_INGRESS_DEPTH.set(nextDepth);
    }

    public static boolean isActive() {
        return EXTERNAL_INGRESS_DEPTH.get() > 0;
    }
}
