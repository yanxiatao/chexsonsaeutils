package git.chexson.chexsonsaeutils.parts;

import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class MultiLevelEmitterMenuTestHarness {

    private MultiLevelEmitterMenuTestHarness() {
    }

    static MultiLevelEmitterMenu.RuntimeMenu detachedForRuntime(MultiLevelEmitterRuntimePart runtimePart) {
        MultiLevelEmitterMenu.RuntimeMenu menu = allocateMenu();
        menu.bindRuntimePart(runtimePart);
        return menu;
    }

    static MultiLevelEmitterMenu.RuntimeMenu fromNetwork(Inventory inventory, FriendlyByteBuf networkData) {
        MultiLevelEmitterMenu.RuntimeMenu menu = allocateMenu();
        menu.bindRuntimePart(resolveRuntimePart(inventory, networkData));
        return menu;
    }

    private static MultiLevelEmitterMenu.RuntimeMenu allocateMenu() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            return (MultiLevelEmitterMenu.RuntimeMenu) allocateInstance.invoke(
                    unsafe,
                    MultiLevelEmitterMenu.RuntimeMenu.class
            );
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate runtime menu test instance", exception);
        }
    }

    private static MultiLevelEmitterRuntimePart resolveRuntimePart(Inventory inventory, FriendlyByteBuf networkData) {
        try {
            Method method = MultiLevelEmitterMenu.class.getDeclaredMethod(
                    "resolveRuntimePart",
                    Inventory.class,
                    FriendlyByteBuf.class
            );
            method.setAccessible(true);
            return (MultiLevelEmitterRuntimePart) method.invoke(null, inventory, networkData);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to resolve runtime part for menu test instance", exception);
        }
    }
}
