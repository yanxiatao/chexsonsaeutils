package git.chexson.chexsonsaeutils.network.planning;

import java.util.Objects;

/** 公共客户端响应路由；payload 处理不静态引用 Minecraft、Screen 或任何客户端类。 */
public final class PlanningOrderClientResponseRouter {
    private static volatile Receiver receiver = payload -> {
    };

    private PlanningOrderClientResponseRouter() {
    }

    public static void install(Receiver newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static void dispatch(PlanningOrderStatePayload payload) {
        receiver.receive(payload);
    }

    @FunctionalInterface
    public interface Receiver {
        void receive(PlanningOrderStatePayload payload);
    }
}
