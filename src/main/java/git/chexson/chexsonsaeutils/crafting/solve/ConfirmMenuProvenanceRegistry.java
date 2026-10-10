package git.chexson.chexsonsaeutils.crafting.solve;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家 → 活跃确认菜单的弱引用登记表：规划完成时（计算线程）把 provenance
 * 直接写回菜单，绕开 summary 与 plan packet 两条被其他 mod 接管的链路。
 *
 * <p>登记发生在 {@code planJob}（服务端），消费发生在规划完成的计算线程；
 * 菜单字段的读取在服务端 tick 的 GuiSync 阶段——写入无需原子性，最坏情况
 * 是标题晚一 tick 出现。
 */
public final class ConfirmMenuProvenanceRegistry {

    private static final Map<UUID, WeakReference<MenuProvenanceHost>> ACTIVE = new ConcurrentHashMap<>();

    private ConfirmMenuProvenanceRegistry() {
    }

    public static void register(@Nullable Player player, MenuProvenanceHost menu) {
        if (player == null) {
            return;
        }
        ACTIVE.put(player.getUUID(), new WeakReference<>(menu));
        ACTIVE.entrySet().removeIf(entry -> entry.getValue().get() == null);
    }

    /** 把 provenance 写回该玩家的活跃确认菜单；无登记则静默跳过。 */
    public static void publish(@Nullable UUID playerId, long millis, String solver) {
        if (playerId == null) {
            return;
        }
        var ref = ACTIVE.get(playerId);
        var menu = ref == null ? null : ref.get();
        if (menu != null) {
            menu.chexsonsaeutils$setMenuProvenance(millis, solver);
        }
    }
}
