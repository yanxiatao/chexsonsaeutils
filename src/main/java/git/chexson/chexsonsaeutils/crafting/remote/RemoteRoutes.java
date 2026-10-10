package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 接收网内的多来源目录；弱引用不延长区块生命周期，所有访问在服务端线程。 */
public final class RemoteRoutes {
    private static final List<WeakReference<RemoteEndpoint>> ENDPOINTS = new ArrayList<>();
    private RemoteRoutes() {}
    /** 服务端停止后不保留旧世界的活动目录。 */
    public static void clear() { ENDPOINTS.clear(); }
    public static void register(RemoteEndpoint endpoint) {
        ENDPOINTS.removeIf(ref -> ref.get() == null);
        for (var ref : ENDPOINTS) {
            var other = ref.get();
            if (other == endpoint) return;
            if (other != null && other.identity().equals(endpoint.identity())
                    && (other.world() == endpoint.world() || other.world() != null && endpoint.world() != null
                    && other.world().getServer() == endpoint.world().getServer()))
                throw new IllegalStateException("重复活动传递器 UUID：" + endpoint.identity());
        }
        ENDPOINTS.add(new WeakReference<>(endpoint));
    }
    public static void unregister(RemoteEndpoint endpoint) {
        ENDPOINTS.removeIf(ref -> ref.get() == null || ref.get() == endpoint);
    }
    public static List<RemoteEndpoint> sources(IGrid receiver, AEKey output) {
        ENDPOINTS.removeIf(ref -> ref.get() == null);
        var result = new ArrayList<RemoteEndpoint>();
        for (var ref : ENDPOINTS) {
            var endpoint = ref.get();
            if (endpoint != null && endpoint.receiverGrid() == receiver && endpoint.outputs().contains(output)) result.add(endpoint);
        }
        result.sort(Comparator.comparingInt(RemoteEndpoint::priority).reversed()
                .thenComparing(e -> e.ready() ? 0 : 1).thenComparing(RemoteEndpoint::load).thenComparing(e -> e.identity().toString()));
        return List.copyOf(result);
    }
    public static boolean enqueue(IGrid receiver, AEKey key, BigInteger amount) {
        for (var source : sources(receiver, key)) if (source.ready() && source.accept(key, amount)) return true;
        return false;
    }
    /** 只用于未提交订单的故障改选，旧来源必须在转移成功后等额扣除待办。 */
    public static boolean enqueueAlternative(IGrid receiver, AEKey key, UUID excluded, BigInteger amount) {
        var sources = sources(receiver, key);
        var previous = sources.stream().filter(e -> e.identity().equals(excluded)).findFirst().orElse(null);
        for (var endpoint : sources) {
            if (!endpoint.identity().equals(excluded) && endpoint.ready()
                    && (previous == null || endpoint.sourceGrid() != previous.sourceGrid())
                    && endpoint.accept(key, amount)) return true;
        }
        return false;
    }
}
