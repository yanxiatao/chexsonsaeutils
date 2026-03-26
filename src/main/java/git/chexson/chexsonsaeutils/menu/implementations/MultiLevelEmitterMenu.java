package git.chexson.chexsonsaeutils.menu.implementations;

import appeng.menu.locator.MenuLocator;
import appeng.menu.locator.MenuLocators;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterUtils;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionCompiler;
import git.chexson.chexsonsaeutils.parts.automation.expression.MultiLevelEmitterExpressionOwnership;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class MultiLevelEmitterMenu {

    private static final String REGISTRATION_KEY = MultiLevelEmitterItem.MENU_BINDING_KEY;
    private static final Component MENU_TITLE = Component.translatable("item.chexsonsaeutils.multi_level_emitter");
    public static final int SLOT_CAPACITY = 64;
    private static final int MIN_CONFIGURED_SLOTS = 1;
    private static final String ACTION_SET_CONFIGURED_SLOT_COUNT = "setConfiguredSlotCount";
    private static final String ACTION_COMMIT_THRESHOLD = "commitThreshold";
    private static final String ACTION_CYCLE_COMPARISON_MODE = "cycleComparisonMode";
    private static final String ACTION_CYCLE_MATCHING_MODE = "cycleMatchingMode";
    private static final String ACTION_CYCLE_CRAFTING_MODE = "cycleCraftingMode";
    private static final String ACTION_APPLY_EXPRESSION = "applyExpression";
    private static Supplier<MenuType<RuntimeMenu>> menuTypeSupplier;
    private static final AtomicReference<RuntimeBindingResolver> runtimeBindingResolver =
            new AtomicReference<>((inventory, data) -> null);
    private static final AtomicBoolean MENU_BINDINGS_REGISTERED = new AtomicBoolean(false);

    private MultiLevelEmitterMenu() {
    }

    public static String registrationKey() {
        return REGISTRATION_KEY;
    }

    @FunctionalInterface
    public interface RuntimeBindingResolver {
        MultiLevelEmitterRuntimePart resolve(Inventory inventory, FriendlyByteBuf networkData);
    }

    public static void registerMenuBindings() {
        MENU_BINDINGS_REGISTERED.set(
                menuTypeSupplier != null
                        && menuTypeSupplier.get() != null
                        && runtimeBindingResolver.get() != null
        );
    }

    public static void registerMenuBindings(
            Supplier<MenuType<RuntimeMenu>> menuTypeSupplier,
            RuntimeBindingResolver runtimeBindingResolver
    ) {
        MultiLevelEmitterMenu.menuTypeSupplier = Objects.requireNonNull(menuTypeSupplier, "menuTypeSupplier");
        MultiLevelEmitterMenu.runtimeBindingResolver.set(
                Objects.requireNonNull(runtimeBindingResolver, "runtimeBindingResolver")
        );
        registerMenuBindings();
    }

    public static void registerRuntimeBindingResolver(RuntimeBindingResolver runtimeBindingResolver) {
        MultiLevelEmitterMenu.runtimeBindingResolver.set(
                Objects.requireNonNull(runtimeBindingResolver, "runtimeBindingResolver")
        );
        registerMenuBindings();
    }

    public static boolean hasRegisteredMenuBindings() {
        return MENU_BINDINGS_REGISTERED.get();
    }

    public static MenuType<RuntimeMenu> registeredMenuType() {
        if (menuTypeSupplier == null) {
            throw new IllegalStateException("Menu bindings not initialized for multi_level_emitter");
        }
        MenuType<RuntimeMenu> menuType = menuTypeSupplier.get();
        if (menuType == null) {
            throw new IllegalStateException("MenuType supplier returned null for multi_level_emitter");
        }
        return menuType;
    }

    private static MultiLevelEmitterRuntimePart resolveRuntimePart(Inventory inventory, FriendlyByteBuf networkData) {
        if (inventory != null && networkData != null && networkData.readableBytes() > 0) {
            MenuLocator locator = MenuLocators.readFromPacket(networkData);
            MultiLevelEmitterRuntimePart located = locator.locate(inventory.player, MultiLevelEmitterRuntimePart.class);
            if (located != null) {
                return located;
            }
        }
        RuntimeBindingResolver resolver = runtimeBindingResolver.get();
        if (resolver == null) {
            return null;
        }
        return resolver.resolve(inventory, networkData);
    }

    public static void openMenu(ServerPlayer player, MultiLevelEmitterRuntimePart runtimePart) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(runtimePart, "runtimePart");
        MenuLocator locator = MenuLocators.forPart(runtimePart);
        MenuProvider menuProvider = new SimpleMenuProvider(
                (containerId, inventory, ignoredPlayer) ->
                        new RuntimeMenu(containerId, inventory, registeredMenuType(), runtimePart),
                MENU_TITLE
        );
        NetworkHooks.openScreen(player, menuProvider, buffer -> MenuLocators.writeToPacket(buffer, locator));
    }

    public static long sanitizeThresholdInput(long rawThreshold) {
        return Math.max(0L, rawThreshold);
    }

    public static long sanitizeAndClampThreshold(long rawThreshold, long maxValue) {
        long sanitized = sanitizeThresholdInput(rawThreshold);
        return Math.min(sanitized, Math.max(0L, maxValue));
    }

    public static int configuredSlotCount(boolean[] configuredSlots) {
        int count = 0;
        for (boolean configured : configuredSlots) {
            if (configured) {
                count++;
            }
        }
        return count;
    }

    public static boolean canDeleteTrailingEmptySlot(boolean[] configuredSlots, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= configuredSlots.length) {
            return false;
        }
        if (configuredSlots[slotIndex]) {
            return false;
        }
        int highestConfigured = -1;
        for (int i = 0; i < configuredSlots.length; i++) {
            if (configuredSlots[i]) {
                highestConfigured = i;
            }
        }
        return slotIndex > highestConfigured;
    }

    public static List<MultiLevelEmitterPart.ComparisonMode> resetAllComparisonsToGreaterOrEqual(int slots) {
        int count = Math.max(0, slots);
        List<MultiLevelEmitterPart.ComparisonMode> modes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            modes.add(MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL);
        }
        return modes;
    }

    public static int nextVisibleSlotCountUnlimited(int configuredCount) {
        int safeConfigured = Math.max(MIN_CONFIGURED_SLOTS, configuredCount);
        return safeConfigured + 1;
    }

    public static int[] compactConfiguredSlots(int[] configuredSlotIndexes) {
        int[] compacted = new int[configuredSlotIndexes.length];
        for (int i = 0; i < configuredSlotIndexes.length; i++) {
            compacted[i] = i;
        }
        return compacted;
    }

    public static MultiLevelEmitterPart.ComparisonMode nextComparisonMode(MultiLevelEmitterPart.ComparisonMode current) {
        MultiLevelEmitterPart.ComparisonMode effective = current == null
                ? MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL
                : current;
        return switch (effective) {
            case GREATER_OR_EQUAL -> MultiLevelEmitterPart.ComparisonMode.LESS_THAN;
            case LESS_THAN -> MultiLevelEmitterPart.ComparisonMode.EQUAL;
            case EQUAL -> MultiLevelEmitterPart.ComparisonMode.NOT_EQUAL;
            case NOT_EQUAL -> MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL;
        };
    }

    public record ThresholdPayload(int slotIndex, long rawThreshold, long maxValue) {
    }

    public record SlotIndexPayload(int slotIndex) {
    }

    public record ExpressionPayload(String rawExpression) {
    }

    public static final class RuntimeMenu extends AEBaseMenu {

        private MultiLevelEmitterRuntimePart runtimePart;

        public RuntimeMenu(int containerId, Inventory inventory) {
            this(containerId, inventory, registeredMenuType(), null);
        }

        public RuntimeMenu(
                int containerId,
                Inventory inventory,
                MenuType<?> menuType,
                MultiLevelEmitterRuntimePart runtimePart
        ) {
            super(menuType, containerId, inventory, runtimePart);
            this.runtimePart = runtimePart;
            registerClientAction(ACTION_SET_CONFIGURED_SLOT_COUNT, Integer.class, this::applyConfiguredSlotCountOnServer);
            registerClientAction(ACTION_COMMIT_THRESHOLD, ThresholdPayload.class, this::applyThresholdCommitOnServer);
            registerClientAction(ACTION_CYCLE_COMPARISON_MODE, SlotIndexPayload.class,
                    payload -> applyComparisonToggleOnServer(payload.slotIndex()));
            registerClientAction(ACTION_CYCLE_MATCHING_MODE, SlotIndexPayload.class,
                    payload -> applyMatchingModeToggleOnServer(payload.slotIndex()));
            registerClientAction(ACTION_CYCLE_CRAFTING_MODE, SlotIndexPayload.class,
                    payload -> applyCraftingModeToggleOnServer(payload.slotIndex()));
            registerClientAction(ACTION_APPLY_EXPRESSION, ExpressionPayload.class, this::applyExpressionOnServer);

            if (inventory != null && runtimePart != null) {
                initializeSlots(inventory, runtimePart);
            }
        }

        public static RuntimeMenu fromNetwork(int containerId, Inventory inventory, FriendlyByteBuf networkData) {
            MenuType<?> resolvedMenuType = menuTypeSupplier == null ? null : menuTypeSupplier.get();
            MultiLevelEmitterRuntimePart runtimePart = resolveRuntimePart(inventory, networkData);
            return new RuntimeMenu(containerId, inventory, resolvedMenuType, runtimePart);
        }

        public static RuntimeMenu detachedForRuntime(int containerId, MultiLevelEmitterRuntimePart runtimePart) {
            throw new UnsupportedOperationException("Detached runtime menus are only supported through the test harness.");
        }

        public void bindRuntimePart(MultiLevelEmitterRuntimePart runtimePart) {
            this.runtimePart = runtimePart;
        }

        public boolean hasRuntimePartBinding() {
            return runtimePart != null;
        }

        public void setConfiguredSlotCount(int configuredSlots) {
            applyConfiguredSlotCountMutation(configuredSlots, true);
        }

        public void commitThreshold(int slotIndex, long rawThreshold, long maxValue) {
            if (runtimePart == null) {
                return;
            }
            long threshold = sanitizeAndClampThreshold(rawThreshold, maxValue);
            runtimePart.updateThresholdFromUi(slotIndex, threshold);
            if (isLiveClientMenu()) {
                sendClientAction(ACTION_COMMIT_THRESHOLD, new ThresholdPayload(slotIndex, rawThreshold, maxValue));
            }
        }

        public void cycleComparisonMode(int slotIndex) {
            if (runtimePart == null) {
                return;
            }
            runtimePart.cycleComparisonModeFromUi(slotIndex);
            if (isLiveClientMenu()) {
                sendClientAction(ACTION_CYCLE_COMPARISON_MODE, new SlotIndexPayload(slotIndex));
            }
        }

        public void cycleMatchingMode(int slotIndex) {
            if (runtimePart == null) {
                return;
            }
            runtimePart.cycleMatchingModeFromUi(slotIndex);
            if (isLiveClientMenu()) {
                sendClientAction(ACTION_CYCLE_MATCHING_MODE, new SlotIndexPayload(slotIndex));
            }
        }

        public void cycleCraftingMode(int slotIndex) {
            if (runtimePart == null) {
                return;
            }
            runtimePart.cycleCraftingModeFromUi(slotIndex);
            if (isLiveClientMenu()) {
                sendClientAction(ACTION_CYCLE_CRAFTING_MODE, new SlotIndexPayload(slotIndex));
            }
        }

        public void applyExpression(String rawExpression) {
            if (runtimePart == null || !canApplyExpression(rawExpression)) {
                return;
            }
            runtimePart.applyExpressionFromUi(rawExpression);
            if (isLiveClientMenu()) {
                sendClientAction(ACTION_APPLY_EXPRESSION, new ExpressionPayload(rawExpression));
            }
        }

        public int configuredSlotCount() {
            return runtimePart == null ? MIN_CONFIGURED_SLOTS : runtimePart.configuredItemCount();
        }

        public int markedSlotCount() {
            return runtimePart == null ? 0 : runtimePart.markedItemCount();
        }

        public int totalSlotCapacity() {
            return SLOT_CAPACITY;
        }

        public boolean hasFuzzyCardInstalled() {
            return runtimePart != null && runtimePart.hasFuzzyCardInstalled();
        }

        public boolean hasCraftingCardInstalled() {
            return runtimePart != null && runtimePart.hasCraftingCardInstalled();
        }

        public IUpgradeInventory getUpgrades() {
            return runtimePart == null ? null : runtimePart.getUpgrades();
        }

        public int visibleSlotCount() {
            return MultiLevelEmitterUtils.calculateVisibleSlotCount(configuredSlotCount(), totalSlotCapacity());
        }

        public boolean isSlotEnabled(int slotIndex) {
            return slotIndex >= 0 && slotIndex < visibleSlotCount();
        }

        public boolean isSlotConfigured(int slotIndex) {
            return isSlotEnabled(slotIndex);
        }

        public boolean hasMarkedItem(int slotIndex) {
            return runtimePart != null && runtimePart.hasConfiguredItem(slotIndex);
        }

        public String appliedExpressionText() {
            return runtimePart == null ? "" : runtimePart.appliedExpressionText();
        }

        public MultiLevelEmitterExpressionOwnership expressionOwnership() {
            return runtimePart == null
                    ? MultiLevelEmitterExpressionOwnership.AUTO
                    : runtimePart.expressionOwnership();
        }

        public boolean expressionIsInvalid() {
            return runtimePart != null && runtimePart.expressionIsInvalid();
        }

        public long thresholdForSlot(int slotIndex) {
            if (slotIndex < 0 || slotIndex >= totalSlotCapacity()) {
                return 1L;
            }
            if (runtimePart == null) {
                return 1L;
            }
            return runtimePart.thresholds().getOrDefault(slotIndex, 1L);
        }

        public MultiLevelEmitterPart.ComparisonMode comparisonModeForSlot(int slotIndex) {
            if (slotIndex < 0 || slotIndex >= totalSlotCapacity()) {
                return MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL;
            }
            if (runtimePart == null) {
                return MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL;
            }
            List<MultiLevelEmitterPart.ComparisonMode> modes = runtimePart.comparisonModes();
            return slotIndex < modes.size()
                    ? modes.get(slotIndex)
                    : MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL;
        }

        public MultiLevelEmitterPart.MatchingMode matchingModeForSlot(int slotIndex) {
            if (slotIndex < 0 || slotIndex >= totalSlotCapacity()) {
                return MultiLevelEmitterPart.MatchingMode.STRICT;
            }
            if (runtimePart == null) {
                return MultiLevelEmitterPart.MatchingMode.STRICT;
            }
            return runtimePart.matchingModeForSlot(slotIndex);
        }

        public MultiLevelEmitterPart.CraftingMode craftingModeForSlot(int slotIndex) {
            if (slotIndex < 0 || slotIndex >= totalSlotCapacity()) {
                return MultiLevelEmitterPart.CraftingMode.NONE;
            }
            if (runtimePart == null) {
                return MultiLevelEmitterPart.CraftingMode.NONE;
            }
            return runtimePart.craftingModeForSlot(slotIndex);
        }

        public boolean duplicateEmitToCraftTarget(int slotIndex) {
            return runtimePart != null && runtimePart.hasDuplicateEmitToCraftTarget(slotIndex);
        }

        private void initializeSlots(Inventory inventory, MultiLevelEmitterRuntimePart runtimePart) {
            IUpgradeInventory upgrades = runtimePart.getUpgrades();
            for (int upgradeSlotIndex = 0; upgradeSlotIndex < upgrades.size(); upgradeSlotIndex++) {
                var upgradeSlot = new RestrictedInputSlot(
                        RestrictedInputSlot.PlacableItemType.UPGRADES,
                        upgrades,
                        upgradeSlotIndex
                );
                upgradeSlot.setNotDraggable();
                addSlot(upgradeSlot, SlotSemantics.UPGRADE);
            }
            var configMenu = runtimePart.getConfig().createMenuWrapper();
            for (int slotIndex = 0; slotIndex < SLOT_CAPACITY; slotIndex++) {
                addSlot(new FakeSlot(configMenu, slotIndex), SlotSemantics.CONFIG);
            }
            createPlayerInventorySlots(inventory);
        }

        private void applyConfiguredSlotCountOnServer(int configuredSlots) {
            applyConfiguredSlotCountMutation(configuredSlots, false);
        }

        private void applyConfiguredSlotCountMutation(int configuredSlots, boolean sendToServer) {
            if (runtimePart == null) {
                return;
            }
            int normalized = Math.max(MIN_CONFIGURED_SLOTS, Math.min(SLOT_CAPACITY, configuredSlots));
            runtimePart.updateConfiguredItemCountFromUi(normalized);
            if (sendToServer && isLiveClientMenu()) {
                sendClientAction(ACTION_SET_CONFIGURED_SLOT_COUNT, normalized);
            }
        }

        private void applyThresholdCommitOnServer(ThresholdPayload payload) {
            if (runtimePart != null && payload != null) {
                runtimePart.updateThresholdFromUi(
                        payload.slotIndex(),
                        sanitizeAndClampThreshold(payload.rawThreshold(), payload.maxValue())
                );
            }
        }

        private void applyComparisonToggleOnServer(int slotIndex) {
            if (runtimePart != null) {
                runtimePart.cycleComparisonModeFromUi(slotIndex);
            }
        }

        private void applyMatchingModeToggleOnServer(int slotIndex) {
            if (runtimePart != null) {
                runtimePart.cycleMatchingModeFromUi(slotIndex);
            }
        }

        private void applyCraftingModeToggleOnServer(int slotIndex) {
            if (runtimePart != null) {
                runtimePart.cycleCraftingModeFromUi(slotIndex);
            }
        }

        private void applyExpressionOnServer(ExpressionPayload payload) {
            if (runtimePart == null || payload == null) {
                return;
            }
            if (!canApplyExpression(payload.rawExpression())) {
                return;
            }
            runtimePart.applyExpressionFromUi(payload.rawExpression());
        }

        private boolean canApplyExpression(String rawExpression) {
            if (runtimePart == null) {
                return false;
            }
            return !MultiLevelEmitterExpressionCompiler.compile(
                    rawExpression,
                    runtimePart.configuredItemCount(),
                    runtimePart::hasConfiguredItem
            ).isInvalid();
        }

        private boolean isLiveClientMenu() {
            try {
                return runtimePart != null && getPlayerInventory() != null && isClientSide();
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
