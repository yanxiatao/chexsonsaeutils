package git.chexson.chexsonsaeutils.crafting.directprocessing;

/** 发布整合包规则的原子快照；每次变更使配方索引与方块派生缓存失效。 */
public final class DirectProcessingPackRulesRegistry {
    private static volatile DirectProcessingPackRules rules = new DirectProcessingPackRulesBuilder().build();
    private static volatile DirectProcessingPackRules clientRules = new DirectProcessingPackRulesBuilder().build();
    private static volatile long epoch;
    private DirectProcessingPackRulesRegistry() {}
    public static long epoch() { return epoch; }
    public static DirectProcessingPackRules current() { return rules; }
    public static DirectProcessingPackRules clientCurrent() { return clientRules; }
    public static synchronized void replace(DirectProcessingPackRules replacement) {
        rules = java.util.Objects.requireNonNull(replacement, "replacement");
        epoch++;
        MachineRecipeReloadTracker.markRecipeReloaded();
    }
    /** 接收菜单的服务端快照；客户端不发布服务器 epoch，集成服务器也不会形成回包循环。 */
    public static synchronized void applyClientSnapshot(String json) {
        clientRules = DirectProcessingPackRulesBuilder.fromJson(json).build();
    }
    public static void reset() { replace(new DirectProcessingPackRulesBuilder().build()); }
}
