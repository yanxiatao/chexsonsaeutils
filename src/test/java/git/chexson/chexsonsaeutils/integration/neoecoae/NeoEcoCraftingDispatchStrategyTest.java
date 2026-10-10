package git.chexson.chexsonsaeutils.integration.neoecoae;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoEcoCraftingDispatchStrategyTest {

    private static final Path NEOECOAE_JAR = Path.of(
            "reference-sources", "neoecoae-21.2.0-preview14.jar");
    private static final String STRATEGY_CLASS =
            "cn.dancingsnow.neoecoae.api.me.ECOCraftingDispatchStrategy";

    @Test
    void configuredAndHardLimitsBoundNormalPushes() throws Exception {
        try (var strategy = strategy()) {
            assertEquals(1024, strategy.beginTick(Integer.MAX_VALUE, 1024));
            assertEquals(393216, strategy.beginTick(Integer.MAX_VALUE, Integer.MAX_VALUE));
        }
    }

    @Test
    void recentNormalPushesReduceFollowingTickBudget() throws Exception {
        try (var strategy = strategy()) {
            strategy.finishTick(400);
            assertEquals(600, strategy.beginTick(999, 1000));
        }
    }

    private static StrategyAccess strategy() throws Exception {
        var loader = new URLClassLoader(new java.net.URL[]{NEOECOAE_JAR.toUri().toURL()}, null);
        try {
            Class<?> type = Class.forName(STRATEGY_CLASS, true, loader);
            return new StrategyAccess(
                    loader,
                    type.getConstructor().newInstance(),
                    type.getMethod("beginTick", int.class, int.class),
                    type.getMethod("finishTick", int.class)
            );
        } catch (Exception exception) {
            loader.close();
            throw exception;
        }
    }

    private record StrategyAccess(
            URLClassLoader loader,
            Object instance,
            java.lang.reflect.Method beginTick,
            java.lang.reflect.Method finishTick
    ) implements AutoCloseable {

        int beginTick(int coProcessors, int configuredLimit) throws Exception {
            return (int) invoke(beginTick, coProcessors, configuredLimit);
        }

        void finishTick(int operations) throws Exception {
            invoke(finishTick, operations);
        }

        private Object invoke(java.lang.reflect.Method method, Object... arguments) throws Exception {
            try {
                return method.invoke(instance, arguments);
            } catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw exception;
            }
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }
}
