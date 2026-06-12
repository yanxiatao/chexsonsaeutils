package xyz.langyo.minecraft.mcp.mod;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.StyleManager;
import appeng.core.definitions.AEItems;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.crafting.pattern.EncodedProcessingPattern;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.menu.MenuOpener;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.locator.MenuLocators;
import appeng.menu.slot.FakeSlot;
import appeng.parts.encoding.EncodingMode;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.PatternBenchmarkSnapshot;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.ContinuationFeatureGate;
import git.chexson.chexsonsaeutils.config.ProcessingPatternReplacementFeatureGate;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusService;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import git.chexson.chexsonsaeutils.menu.implementations.HighCapacityCraftingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterPart;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementDecoder;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementPersistence;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternSlotReplacementRule;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleDraft;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleHost;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRulePayload;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingSlotRuleStatus;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import xyz.langyo.minecraft.mcp.common.ReflectedInputHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ChexsonSmokeInputHandler extends ReflectedInputHandler {
    private static final String EMITTER_ITEM_ID = "chexsonsaeutils:multi_level_emitter";

    ChexsonSmokeInputHandler() {
        super(ReflectedInputHandler::executeOnRenderThread);
    }

    @Override
    protected Object dispatch(String method, Map<String, String> params, Object session) {
        try {
            return switch (method) {
                case "chexson_status" -> onRenderThread(this::handleStatus);
                case "chexson_give_item" -> onRenderThread(() -> handleGiveItem(params));
                case "chexson_emitter_snapshot" -> onRenderThread(this::handleEmitterSnapshot);
                case "chexson_emitter_configure" -> onRenderThread(() -> handleEmitterConfigure(params));
                case "chexson_emitter_snapshot_nearby" -> onServerThread(() -> handleEmitterSnapshotNearby(params));
                case "chexson_emitter_configure_nearby" -> onServerThread(() -> handleEmitterConfigureNearby(params));
                case "chexson_pattern_snapshot" -> onRenderThread(this::handlePatternSnapshot);
                case "chexson_pattern_configure" -> onRenderThread(() -> handlePatternConfigure(params));
                case "chexson_pattern_configure_nearby" -> onServerThread(() -> handlePatternConfigureNearby(params));
                case "chexson_pattern_execution_probe" -> onServerThread(() -> handlePatternExecutionProbe(params));
                case "chexson_continuation_probe" -> onRenderThread(() -> handleContinuationProbe(params));
                case "chexson_open_craft_confirm_nearby" -> onServerThread(() -> handleOpenCraftConfirmNearby(params));
                case "chexson_continuation_button_probe" -> onRenderThread(() -> handleContinuationButtonProbe(params));
                case "chexson_continuation_confirm_probe" -> onServerThread(() -> handleContinuationConfirmProbe(params));
                case "chexson_open_world_select" -> onRenderThread(() -> handleOpenWorldSelect(params));
                case "chexson_join_world" -> onRenderThread(() -> handleJoinWorld(params));
                case "chexson_scan_nearby" -> onServerThread(() -> handleScanNearby(params));
                case "chexson_place_emitter_nearby" -> onServerThread(() -> handlePlaceEmitterNearby(params));
                case "chexson_open_emitter_nearby" -> onServerThread(() -> handleOpenEmitterNearby(params));
                case "chexson_open_pattern_nearby" -> onServerThread(() -> handleOpenPatternNearby(params));
                case "chexson_high_capacity_place_nearby" -> onServerThread(() -> handlePlaceHighCapacityNearby(params));
                case "chexson_high_capacity_open_nearby" -> handleOpenHighCapacityNearby(params);
                case "chexson_high_capacity_snapshot_nearby" -> onServerThread(() -> handleHighCapacitySnapshotNearby(params));
                case "chexson_high_capacity_configure_nearby" -> onServerThread(() -> handleHighCapacityConfigureNearby(params));
                case "chexson_high_capacity_benchmark_probe" -> onServerThread(() -> handleHighCapacityBenchmarkProbe(params));
                default -> super.dispatch(method, params, session);
            };
        } catch (Throwable throwable) {
            return error(throwable);
        }
    }

    private Object onRenderThread(Callable<Object> callable) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        ReflectedInputHandler.executeOnRenderThread(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable throwable) {
                error.set(throwable);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                return error("Timed out waiting for render thread");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        }
        if (error.get() != null) {
            return error(error.get());
        }
        return result.get();
    }

    private Object onServerThread(Callable<Object> callable) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return error("No singleplayer server is available");
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        server.execute(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable throwable) {
                error.set(throwable);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                return error("Timed out waiting for server thread");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        }
        if (error.get() != null) {
            return error(error.get());
        }
        return result.get();
    }

    private boolean waitForClientHighCapacityBlockEntity(BlockPos pos, long timeoutMillis, long pollMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        long boundedPollMillis = Math.max(10L, pollMillis);
        do {
            Object observed = onRenderThread(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                return minecraft.level != null
                        && minecraft.level.getBlockEntity(pos) instanceof HighCapacityCraftingMachineBlockEntity;
            });
            if (Boolean.TRUE.equals(observed)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(boundedPollMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
    }

    private Map<String, Object> handleStatus() {
        Minecraft minecraft = Minecraft.getInstance();
        Map<String, Object> result = baseState(minecraft);
        ResourceLocation emitterId = ResourceLocation.parse(EMITTER_ITEM_ID);
        Item registeredItem = BuiltInRegistries.ITEM.get(emitterId);
        result.put("multiLevelEmitterRegistered", emitterId.equals(BuiltInRegistries.ITEM.getKey(registeredItem)));
        result.put("multiLevelEmitterRegistryPath", ChexsonsaeutilsContent.emitterRegistryPath());
        result.put("multiLevelEmitterMenuBindingsReady", MultiLevelEmitterMenu.hasRegisteredMenuBindings());
        result.put("multiLevelEmitterClientBindingsReady", MultiLevelEmitterScreen.hasClientBindingsRegistered());
        result.put("craftingContinuationEnabled", safeBoolean(ContinuationFeatureGate::isEnabledAtStartup));
        result.put("processingPatternReplacementEnabled", safeBoolean(ProcessingPatternReplacementFeatureGate::isEnabledAtStartup));
        result.put("craftingContinuationConfigValue", safeConfigValue(ChexsonsaeutilsCompatibilityConfig.CRAFTING_CONTINUATION_ENABLED));
        result.put("processingPatternReplacementConfigValue", safeConfigValue(ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED));
        result.put("patternMenuMixinHostAssignable", ProcessingSlotRuleHost.class.isAssignableFrom(PatternEncodingTermMenu.class));
        result.put("craftConfirmMenuMixinHostAssignable", CraftingContinuationSubmitBridge.ContinuationModeHost.class.isAssignableFrom(CraftConfirmMenu.class));
        result.put("craftingCpuMenuMixinHostAssignable", CraftingContinuationStatusService.SelectedCpuDetailHost.class.isAssignableFrom(CraftingCPUMenu.class));
        result.put("craftingCpuScreenMixinHostAssignable", CraftingContinuationStatusService.WaitingStackProjectionHost.class.isAssignableFrom(CraftingCPUScreen.class));
        return result;
    }

    private Map<String, Object> handleJoinWorld(Map<String, String> params) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen screen)) {
            return error("Current screen is not SelectWorldScreen: "
                    + (minecraft.screen == null ? "" : minecraft.screen.getClass().getName()));
        }

        Object worldList = readField(screen, "list");
        Method childrenMethod = worldList.getClass().getMethod("children");
        List<?> children = (List<?>) childrenMethod.invoke(worldList);
        int targetIndex = parseInt(params, "index", 0);
        int worldIndex = 0;
        for (Object child : children) {
            Method joinWorldMethod;
            try {
                joinWorldMethod = findMethod(child.getClass(), "joinWorld");
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            if (worldIndex++ != targetIndex) {
                continue;
            }
            Method canJoinMethod = findMethod(child.getClass(), "canJoin");
            boolean canJoin = Boolean.TRUE.equals(canJoinMethod.invoke(child));
            String levelName = String.valueOf(findMethod(child.getClass(), "getLevelName").invoke(child));
            if (!canJoin) {
                return error("World cannot be joined: " + levelName);
            }
            joinWorldMethod.invoke(child);
            Map<String, Object> result = ok();
            result.put("joined", true);
            result.put("worldIndex", targetIndex);
            result.put("levelName", levelName);
            return result;
        }

        return error("No joinable world entry at index " + targetIndex);
    }

    private Map<String, Object> handleOpenWorldSelect(Map<String, String> params) {
        Minecraft minecraft = Minecraft.getInstance();
        var previousScreen = minecraft.screen;
        SelectWorldScreen screen = new SelectWorldScreen(previousScreen);
        minecraft.setScreen(screen);

        Map<String, Object> result = ok();
        result.put("opened", minecraft.screen instanceof SelectWorldScreen);
        result.put("screen", minecraft.screen == null ? "" : minecraft.screen.getClass().getName());
        result.put("previousScreen", previousScreen == null ? "" : previousScreen.getClass().getName());
        return result;
    }

    private Map<String, Object> handleGiveItem(Map<String, String> params) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return error("No client player is available");
        }
        ResourceLocation itemId = ResourceLocation.parse(params.getOrDefault("item", EMITTER_ITEM_ID));
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (!itemId.equals(BuiltInRegistries.ITEM.getKey(item))) {
            return error("Unknown item: " + itemId);
        }

        int count = parseInt(params, "count", 1);
        int slot = parseInt(params, "slot", 0);
        ItemStack stack = new ItemStack(item, Math.max(1, count));
        minecraft.player.getInventory().setItem(slot, stack.copy());

        MinecraftServer server = minecraft.getSingleplayerServer();
        UUID playerId = minecraft.player.getUUID();
        if (server != null) {
            server.execute(() -> {
                ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
                if (serverPlayer != null) {
                    serverPlayer.getInventory().setItem(slot, stack.copy());
                    serverPlayer.containerMenu.broadcastChanges();
                }
            });
        }

        Map<String, Object> result = ok();
        result.put("item", itemId.toString());
        result.put("slot", slot);
        result.put("count", stack.getCount());
        result.put("serverSyncScheduled", server != null);
        return result;
    }

    private Map<String, Object> handleScanNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyScan scan = scanNearbyParts(player, parseInt(params, "radius", 8));
        Map<String, Object> result = ok();
        result.put("player", player.getGameProfile().getName());
        result.put("dimension", player.serverLevel().dimension().location().toString());
        result.put("position", positionMap(player.blockPosition()));
        result.put("radius", scan.radius());
        result.put("parts", scan.entries());
        result.put("multiLevelEmitters", scan.emitters().size());
        result.put("patternTerminals", scan.patternTerminals().size());
        return result;
    }

    private Map<String, Object> handlePlaceEmitterNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        ResourceLocation itemId = ResourceLocation.parse(EMITTER_ITEM_ID);
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (!(item instanceof IPartItem<?> rawPartItem)) {
            return error("Registered emitter item is not an AE2 part item: " + item.getClass().getName());
        }

        IPartItem<? extends IPart> partItem = (IPartItem<? extends IPart>) rawPartItem;
        for (NearbyHost host : scanPartHosts(player, parseInt(params, "radius", 8))) {
            for (Direction side : Direction.values()) {
                if (host.host().getPart(side) != null) {
                    continue;
                }
                IPart placed = host.host().addPart(partItem, side, player);
                if (placed instanceof MultiLevelEmitterRuntimePart emitter) {
                    Map<String, Object> result = ok();
                    result.put("placed", true);
                    result.put("target", nearbyPartMap(new NearbyPart<>(host.pos(), side, emitter)));
                    return result;
                }
            }
        }

        return error("No nearby AE2 part host side accepted the multi level emitter");
    }

    private Map<String, Object> handlePlaceHighCapacityNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> existing = findHighCapacityCraftingMachineNearby(
                player,
                parseInt(params, "radius", 8)
        );
        if (existing != null) {
            Map<String, Object> result = ok();
            result.put("placed", false);
            result.put("alreadyPresent", true);
            result.put("target", nearbyBlockEntityMap(existing));
            return result;
        }

        BlockPos placement = findNearbyAirPlacement(player, parseInt(params, "radius", 4));
        if (placement == null) {
            return error("No nearby air block was found for high capacity crafting machine placement");
        }

        player.serverLevel().setBlockAndUpdate(placement, Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get().defaultBlockState());
        BlockEntity placed = player.serverLevel().getBlockEntity(placement);
        if (!(placed instanceof HighCapacityCraftingMachineBlockEntity blockEntity)) {
            return error("Placed block did not create HighCapacityCraftingMachineBlockEntity");
        }

        Map<String, Object> result = ok();
        result.put("placed", true);
        result.put("target", nearbyBlockEntityMap(new NearbyBlockEntity<>(placement.immutable(), blockEntity)));
        return result;
    }

    private Map<String, Object> handleOpenEmitterNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyPart<MultiLevelEmitterRuntimePart> target = findEmitterNearby(player, parseInt(params, "radius", 8));
        if (target == null) {
            Map<String, Object> result = handleScanNearby(params);
            result.put("ok", false);
            result.put("error", "No nearby MultiLevelEmitterRuntimePart was found");
            return result;
        }

        MultiLevelEmitterMenu.openMenu(player, target.part());
        player.containerMenu.broadcastChanges();
        Map<String, Object> result = ok();
        result.put("opened", true);
        result.put("target", nearbyPartMap(target));
        result.put("menu", player.containerMenu.getClass().getName());
        return result;
    }

    private Map<String, Object> handleOpenHighCapacityNearby(Map<String, String> params) {
        int radius = parseInt(params, "radius", 8);
        long waitMillis = Math.max(0L, parseInt(params, "waitMillis", 1000));
        long pollMillis = Math.max(10L, parseInt(params, "pollMillis", 50));

        Object targetResult = onServerThread(() -> {
            ServerPlayer player = serverPlayer();
            if (player == null) {
                return error("No server player is available");
            }

            NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> target = findHighCapacityCraftingMachineNearby(player, radius);
            if (target == null) {
                Map<String, Object> result = error("No nearby HighCapacityCraftingMachineBlockEntity was found");
                result.put("candidates", highCapacityCandidates(player, radius));
                return result;
            }

            Map<String, Object> result = ok();
            result.put("target", nearbyBlockEntityMap(target));
            return result;
        });
        if (!(targetResult instanceof Map<?, ?> rawTarget)) {
            return error("Unexpected target resolution result: " + targetResult);
        }

        Map<String, Object> targetMap = new LinkedHashMap<>();
        rawTarget.forEach((key, value) -> targetMap.put(String.valueOf(key), value));
        if (!Boolean.TRUE.equals(targetMap.get("ok"))) {
            return targetMap;
        }

        Object targetValue = targetMap.get("target");
        if (!(targetValue instanceof Map<?, ?> rawTargetEntry)) {
            return error("Resolved target entry is missing");
        }
        Object posValue = rawTargetEntry.get("pos");
        if (!(posValue instanceof Map<?, ?> rawPos)) {
            return error("Resolved target position is missing");
        }

        BlockPos pos = new BlockPos(
                ((Number) rawPos.get("x")).intValue(),
                ((Number) rawPos.get("y")).intValue(),
                ((Number) rawPos.get("z")).intValue()
        );

        boolean clientReady = waitForClientHighCapacityBlockEntity(pos, waitMillis, pollMillis);
        if (!clientReady) {
            Map<String, Object> result = new LinkedHashMap<>(targetMap);
            result.put("ok", false);
            result.put("error", "Client did not observe HighCapacityCraftingMachineBlockEntity before menu open");
            result.put("waitMillis", waitMillis);
            result.put("pollMillis", pollMillis);
            return result;
        }

        Object openResult = onServerThread(() -> {
            ServerPlayer player = serverPlayer();
            if (player == null) {
                return error("No server player is available");
            }

            BlockEntity blockEntity = player.serverLevel().getBlockEntity(pos);
            if (!(blockEntity instanceof HighCapacityCraftingMachineBlockEntity highCapacityBlockEntity)) {
                return error("Resolved block entity changed before menu open");
            }

            boolean opened = MenuOpener.open(
                    Chexsonsaeutils.HIGH_CAPACITY_CRAFTING_MACHINE_MENU.get(),
                    player,
                    MenuLocators.forBlockEntity(highCapacityBlockEntity)
            );
            player.containerMenu.broadcastChanges();

            Map<String, Object> result = ok();
            result.put("opened", opened && player.containerMenu instanceof HighCapacityCraftingMachineMenu);
            result.put("target", nearbyBlockEntityMap(new NearbyBlockEntity<>(pos.immutable(), highCapacityBlockEntity)));
            result.put("menu", player.containerMenu.getClass().getName());
            result.put("clientReady", true);
            return result;
        });
        if (openResult instanceof Map<?, ?> rawOpenResult) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawOpenResult.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return error("Unexpected open result: " + openResult);
    }

    private Map<String, Object> handleEmitterSnapshotNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyPart<MultiLevelEmitterRuntimePart> target = findEmitterNearby(player, parseInt(params, "radius", 8));
        if (target == null) {
            return error("No nearby MultiLevelEmitterRuntimePart was found");
        }
        return emitterRuntimeSnapshot(target.part(), target, null);
    }

    private Map<String, Object> handleEmitterConfigureNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyPart<MultiLevelEmitterRuntimePart> target = findEmitterNearby(player, parseInt(params, "radius", 8));
        if (target == null) {
            return error("No nearby MultiLevelEmitterRuntimePart was found");
        }

        MultiLevelEmitterRuntimePart runtimePart = target.part();
        if (params.containsKey("slots")) {
            runtimePart.updateConfiguredItemCountFromUi(parseInt(
                    params,
                    "slots",
                    runtimePart.configuredItemCount()
            ));
        }
        applyRuntimeThresholds(params, runtimePart);
        applyRuntimeCycleCount(params, "comparisonCycles", runtimePart::cycleComparisonModeFromUi);
        applyRuntimeCycleCount(params, "matchingCycles", runtimePart::cycleMatchingModeFromUi);
        applyRuntimeCycleCount(params, "craftingCycles", runtimePart::cycleCraftingModeFromUi);
        applyConfiguredItems(params, runtimePart);
        if (params.containsKey("expression")) {
            runtimePart.applyExpressionFromUi(params.get("expression"));
        }

        Boolean monitorResult = null;
        if (params.containsKey("observed")) {
            monitorResult = runtimePart.evaluateConfiguredOutput(
                    parseLongList(params.get("observed")),
                    Boolean.parseBoolean(params.getOrDefault("networkActive", "true"))
            );
        }
        return emitterRuntimeSnapshot(runtimePart, target, monitorResult);
    }

    private Map<String, Object> handleHighCapacitySnapshotNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> target = findHighCapacityCraftingMachineNearby(
                player,
                parseInt(params, "radius", 8)
        );
        if (target == null) {
            Map<String, Object> result = error("No nearby HighCapacityCraftingMachineBlockEntity was found");
            result.put("candidates", highCapacityCandidates(player, parseInt(params, "radius", 8)));
            return result;
        }
        return highCapacitySnapshot(target.blockEntity(), target);
    }

    private Map<String, Object> handleHighCapacityConfigureNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> target = findHighCapacityCraftingMachineNearby(
                player,
                parseInt(params, "radius", 8)
        );
        if (target == null) {
            Map<String, Object> result = error("No nearby HighCapacityCraftingMachineBlockEntity was found");
            result.put("candidates", highCapacityCandidates(player, parseInt(params, "radius", 8)));
            return result;
        }

        HighCapacityCraftingMachineBlockEntity blockEntity = target.blockEntity();
        if (params.containsKey("page")) {
            int page = parseInt(params, "page", blockEntity.getPageIndex());
            if (page < 0 || page >= blockEntity.getPageCount()) {
                Map<String, Object> result = highCapacitySnapshot(blockEntity, target);
                result.put("ok", false);
                result.put("error", "Requested page is out of range");
                result.put("requestedPage", page);
                return result;
            }
            blockEntity.setActivePage(page);
        }
        if (params.containsKey("baseTicks")) {
            int baseTicks = parseInt(params, "baseTicks", blockEntity.getBaseOperationTicks());
            if (baseTicks < 1) {
                Map<String, Object> result = highCapacitySnapshot(blockEntity, target);
                result.put("ok", false);
                result.put("error", "baseTicks must be >= 1");
                result.put("requestedBaseTicks", baseTicks);
                return result;
            }
            blockEntity.setBaseOperationTicksForTest(baseTicks);
        }

        boolean countersReset = Boolean.parseBoolean(params.getOrDefault("resetCounters", "false"));
        boolean clearedPatterns = Boolean.parseBoolean(params.getOrDefault("clearPatterns", "false"));
        if (countersReset) {
            blockEntity.resetBenchmarkCountersForTest();
        }
        if (clearedPatterns) {
            blockEntity.clearPatternsForTest();
        }

        int inserted = 0;
        int fillCount = parseInt(params, "fillCount", 0);
        if (fillCount > 0) {
            inserted = blockEntity.fillCraftingPatternsForTest(
                    parseInt(params, "fillStartSlot", 0),
                    fillCount,
                    parseCraftingGrid(params)
            );
            if (inserted <= 0) {
                Map<String, Object> result = highCapacitySnapshot(blockEntity, target);
                result.put("ok", false);
                result.put("error", "Requested fill did not insert any crafting patterns");
                result.put("requestedFillCount", fillCount);
                result.put("insertedPatterns", inserted);
                return result;
            }
        }

        int submitted = 0;
        int submitCount = parseInt(params, "submitCount", 0);
        if (submitCount > 0) {
            submitted = blockEntity.submitFirstAvailablePatternForTest(submitCount);
            if (submitted <= 0) {
                Map<String, Object> result = highCapacitySnapshot(blockEntity, target);
                result.put("ok", false);
                result.put("error", "Requested submit did not enqueue any local crafting tasks");
                result.put("requestedSubmitCount", submitCount);
                result.put("submittedPatterns", submitted);
                return result;
            }
        }

        if (player.containerMenu instanceof HighCapacityCraftingMachineMenu menu) {
            menu.broadcastChanges();
        }

        Map<String, Object> result = highCapacitySnapshot(blockEntity, target);
        result.put("insertedPatterns", inserted);
        result.put("submittedPatterns", submitted);
        result.put("countersReset", countersReset);
        result.put("clearedPatterns", clearedPatterns);
        return result;
    }

    private Map<String, Object> handleHighCapacityBenchmarkProbe(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> target = findHighCapacityCraftingMachineNearby(
                player,
                parseInt(params, "radius", 8)
        );
        if (target == null) {
            Map<String, Object> result = error("No nearby HighCapacityCraftingMachineBlockEntity was found");
            result.put("candidates", highCapacityCandidates(player, parseInt(params, "radius", 8)));
            return result;
        }

        PatternBenchmarkSnapshot snapshot = target.blockEntity().snapshotBenchmark();
        Map<String, Object> result = highCapacitySnapshot(target.blockEntity(), target);
        String failure = null;

        int expectedPatterns = parseInt(params, "expectedPatterns", -1);
        int expectedTotalNonEmpty = parseInt(params, "expectedTotalNonEmpty", -1);
        int minDecodeCalls = parseInt(params, "minDecodeCalls", -1);
        int minDecodeCacheHits = parseInt(params, "minDecodeCacheHits", -1);
        int minDirtyScanSlots = parseInt(params, "minDirtyScanSlots", -1);
        int minJobsSubmitted = parseInt(params, "minJobsSubmitted", -1);
        int minJobsCompleted = parseInt(params, "minJobsCompleted", -1);
        int maxQueuedTasks = parseInt(params, "maxQueuedTasks", Integer.MAX_VALUE);
        int maxRunningTasks = parseInt(params, "maxRunningTasks", Integer.MAX_VALUE);
        long minCpuWaitingReturnAmount = parseLong(params, "minCpuWaitingReturnAmount", -1L);
        long minFormalTimingCorrectionCount = parseLong(params, "minFormalTimingCorrectionCount", -1L);
        long minFormalTimingProgressClampCount = parseLong(params, "minFormalTimingProgressClampCount", -1L);
        long minFormalTimingEtaClampCount = parseLong(params, "minFormalTimingEtaClampCount", -1L);
        long minFormalStatusHeartbeatCount = parseLong(params, "minFormalStatusHeartbeatCount", -1L);
        long maxCpuWaitingReturnBudgetStopCount = parseLong(params, "maxCpuWaitingReturnBudgetStopCount", Long.MAX_VALUE);
        long minLargestCpuWaitingReturnAmount = parseLong(params, "minLargestCpuWaitingReturnAmount", -1L);
        long maxCpuWaitingReturnOverBudgetCount = parseLong(params, "maxCpuWaitingReturnOverBudgetCount", Long.MAX_VALUE);
        long maxCpuWaitingAeFallbackPartialInsertCount = parseLong(
                params,
                "maxCpuWaitingAeFallbackPartialInsertCount",
                Long.MAX_VALUE
        );
        long maxCpuWaitingNoProgressRetries = parseLong(params, "maxCpuWaitingNoProgressRetries", Long.MAX_VALUE);
        long maxCpuWaitingRouteNanosMax = parseLong(params, "maxCpuWaitingRouteNanosMax", Long.MAX_VALUE);
        long maxTickBudgetNanosObserved = parseLong(params, "maxTickBudgetNanosObserved", Long.MAX_VALUE);
        long maxNonFormalProviderHitCount = parseLong(params, "maxNonFormalProviderHitCount", 0L);
        long minFormalMachineOptimizationHitCount = parseLong(params, "minFormalMachineOptimizationHitCount", -1L);
        long maxFormalMachineOptimizationHitCount = parseLong(
                params,
                "maxFormalMachineOptimizationHitCount",
                Long.MAX_VALUE
        );
        int minLargestObservedBatchSize = parseInt(params, "minLargestObservedBatchSize", -1);
        int minPeakRunningTasks = parseInt(params, "minPeakRunningTasks", -1);
        int minPeakRunningUniquePatterns = parseInt(params, "minPeakRunningUniquePatterns", -1);

        if (expectedPatterns >= 0 && snapshot.decodedPatternCount() != expectedPatterns) {
            failure = "decodedPatternCount mismatch";
        } else if (expectedTotalNonEmpty >= 0 && snapshot.totalNonEmptyPatternSlots() != expectedTotalNonEmpty) {
            failure = "totalNonEmptyPatternSlots mismatch";
        } else if (minDecodeCalls >= 0 && snapshot.decodePatternCount() < minDecodeCalls) {
            failure = "decodePatternCount below minimum";
        } else if (minDecodeCacheHits >= 0 && snapshot.decodeCacheHitCount() < minDecodeCacheHits) {
            failure = "decodeCacheHitCount below minimum";
        } else if (minDirtyScanSlots >= 0 && snapshot.dirtyRefreshScannedSlots() < minDirtyScanSlots) {
            failure = "dirtyRefreshScannedSlots below minimum";
        } else if (minJobsSubmitted >= 0 && snapshot.jobsSubmitted() < minJobsSubmitted) {
            failure = "jobsSubmitted below minimum";
        } else if (minJobsCompleted >= 0 && snapshot.jobsCompleted() < minJobsCompleted) {
            failure = "jobsCompleted below minimum";
        } else if (snapshot.queuedTasks() > maxQueuedTasks) {
            failure = "queuedTasks exceeded maximum";
        } else if (snapshot.runningTasks() > maxRunningTasks) {
            failure = "runningTasks exceeded maximum";
        } else if (snapshot.nonFormalProviderHitCount() > maxNonFormalProviderHitCount) {
            failure = "nonFormalProviderHitCount exceeded maximum";
        } else if (minFormalMachineOptimizationHitCount >= 0L
                && snapshot.formalMachineOptimizationHitCount() < minFormalMachineOptimizationHitCount) {
            failure = "formalMachineOptimizationHitCount below minimum";
        } else if (snapshot.formalMachineOptimizationHitCount() > maxFormalMachineOptimizationHitCount) {
            failure = "formalMachineOptimizationHitCount exceeded maximum";
        } else if (minCpuWaitingReturnAmount >= 0L && snapshot.cpuWaitingReturnAmount() < minCpuWaitingReturnAmount) {
            failure = "cpuWaitingReturnAmount below minimum";
        } else if (minFormalTimingCorrectionCount >= 0L
                && snapshot.formalTimingCorrectionCount() < minFormalTimingCorrectionCount) {
            failure = "formalTimingCorrectionCount below minimum";
        } else if (minFormalTimingProgressClampCount >= 0L
                && snapshot.formalTimingProgressClampCount() < minFormalTimingProgressClampCount) {
            failure = "formalTimingProgressClampCount below minimum";
        } else if (minFormalTimingEtaClampCount >= 0L
                && snapshot.formalTimingEtaClampCount() < minFormalTimingEtaClampCount) {
            failure = "formalTimingEtaClampCount below minimum";
        } else if (minFormalStatusHeartbeatCount >= 0L
                && snapshot.formalStatusHeartbeatCount() < minFormalStatusHeartbeatCount) {
            failure = "formalStatusHeartbeatCount below minimum";
        } else if (snapshot.cpuWaitingReturnBudgetStopCount() > maxCpuWaitingReturnBudgetStopCount) {
            failure = "cpuWaitingReturnBudgetStopCount exceeded maximum";
        } else if (minLargestCpuWaitingReturnAmount >= 0L
                && snapshot.largestCpuWaitingReturnAmount() < minLargestCpuWaitingReturnAmount) {
            failure = "largestCpuWaitingReturnAmount below minimum";
        } else if (snapshot.cpuWaitingReturnOverBudgetCount() > maxCpuWaitingReturnOverBudgetCount) {
            failure = "cpuWaitingReturnOverBudgetCount exceeded maximum";
        } else if (snapshot.cpuWaitingAeFallbackPartialInsertCount() > maxCpuWaitingAeFallbackPartialInsertCount) {
            failure = "cpuWaitingAeFallbackPartialInsertCount exceeded maximum";
        } else if (snapshot.cpuWaitingNoProgressRetries() > maxCpuWaitingNoProgressRetries) {
            failure = "cpuWaitingNoProgressRetries exceeded maximum";
        } else if (snapshot.cpuWaitingRouteNanosMax() > maxCpuWaitingRouteNanosMax) {
            failure = "cpuWaitingRouteNanosMax exceeded maximum";
        } else if (snapshot.maxTickBudgetNanosObserved() > maxTickBudgetNanosObserved) {
            failure = "maxTickBudgetNanosObserved exceeded maximum";
        } else if (minLargestObservedBatchSize >= 0 && snapshot.largestObservedBatchSize() < minLargestObservedBatchSize) {
            failure = "largestObservedBatchSize below minimum";
        } else if (minPeakRunningTasks >= 0 && snapshot.peakRunningTasks() < minPeakRunningTasks) {
            failure = "peakRunningTasks below minimum";
        } else if (minPeakRunningUniquePatterns >= 0
                && snapshot.peakRunningUniquePatterns() < minPeakRunningUniquePatterns) {
            failure = "peakRunningUniquePatterns below minimum";
        }

        if (failure != null) {
            result.put("ok", false);
            result.put("error", failure);
        }
        return result;
    }

    private Map<String, Object> handleOpenPatternNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyPart<IPatternTerminalMenuHost> target = findPatternTerminalNearby(player, parseInt(params, "radius", 8));
        if (target == null) {
            Map<String, Object> result = handleScanNearby(params);
            result.put("ok", false);
            result.put("error", "No nearby IPatternTerminalMenuHost was found");
            return result;
        }

        boolean used = target.part() instanceof IPart part
                && part.onUseWithoutItem(player, Vec3.atCenterOf(target.pos()));

        Map<String, Object> result = ok();
        result.put("opened", used);
        result.put("target", nearbyPartMap(target));
        result.put("menu", player.containerMenu.getClass().getName());
        result.put("host", target.part().getClass().getName());
        return result;
    }

    private Map<String, Object> handleOpenCraftConfirmNearby(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyPart<IPatternTerminalMenuHost> target = findPatternTerminalNearby(player, parseInt(params, "radius", 8));
        if (target == null) {
            Map<String, Object> result = handleScanNearby(params);
            result.put("ok", false);
            result.put("error", "No nearby IPatternTerminalMenuHost was found");
            return result;
        }

        Object renderResult = onRenderThread(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return error("No client player is available");
            }

            CraftConfirmMenu menu = new CraftConfirmMenu(0, minecraft.player.getInventory(), target.part());
            minecraft.player.containerMenu = menu;
            CraftConfirmScreen screen = new CraftConfirmScreen(
                    menu,
                    minecraft.player.getInventory(),
                    Component.translatable("gui.ae2.CraftConfirm"),
                    StyleManager.loadStyleDoc("/screens/craft_confirm.json")
            );
            minecraft.setScreen(screen);

            Map<String, Object> result = ok();
            result.put("opened", true);
            result.put("screen", screen.getClass().getName());
            result.put("menu", menu.getClass().getName());
            result.put("craftConfirmMenuMixinHostAssignable", menu instanceof CraftingContinuationSubmitBridge.ContinuationModeHost);
            return result;
        });

        if (renderResult instanceof Map<?, ?> rawResult) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawResult.forEach((key, value) -> result.put(String.valueOf(key), value));
            result.put("target", nearbyPartMap(target));
            result.put("host", target.part().getClass().getName());
            return result;
        }
        return error("Unexpected render thread result: " + renderResult);
    }

    private Map<String, Object> handlePatternConfigureNearby(Map<String, String> params) throws Exception {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyPart<IPatternTerminalMenuHost> target = findPatternTerminalNearby(player, parseInt(params, "radius", 8));
        if (target == null) {
            return error("No nearby IPatternTerminalMenuHost was found");
        }

        PatternEncodingTermMenu menu = new PatternEncodingTermMenu(
                0,
                player.getInventory(),
                target.part()
        );
        if (!(menu instanceof ProcessingSlotRuleHost host)) {
            return error("PatternEncodingTermMenu is missing ProcessingSlotRuleHost");
        }

        int slot = parseInt(params, "slot", 0);
        setField(menu, "mode", EncodingMode.PROCESSING);
        ItemStack sourceStack = stackFromId(params.getOrDefault("source", "minecraft:iron_ingot"));
        ItemStack outputStack = stackFromId(params.getOrDefault("output", "minecraft:iron_block"));
        ItemStack candidateStack = stackFromId(params.getOrDefault("candidate", "minecraft:copper_ingot"));
        FakeSlot[] inputSlots = menu.getProcessingInputSlots();
        FakeSlot[] outputSlots = menu.getProcessingOutputSlots();
        if (slot < 0 || slot >= inputSlots.length) {
            return error("Invalid processing input slot: " + slot);
        }
        inputSlots[slot].set(sourceStack.copy());
        if (outputSlots.length > 0) {
            outputSlots[0].set(outputStack.copy());
        }

        Set<ResourceLocation> selectedTags = parseResourceSet(params.get("tags"));
        Set<ResourceLocation> explicitCandidates = parseResourceSet(params.get("candidates"));
        if (explicitCandidates.isEmpty()) {
            explicitCandidates.add(BuiltInRegistries.ITEM.getKey(candidateStack.getItem()));
        }
        host.requestSaveProcessingSlotRuleDraft(new ProcessingSlotRulePayload(slot, selectedTags, explicitCandidates));

        ItemStack encodedPattern = invokeEncodeProcessingPattern(menu);
        String encodedItem = stackId(encodedPattern);
        boolean encodedPatternCreated = !encodedPattern.isEmpty();
        if (encodedPatternCreated) {
            Slot encodedPatternSlot = (Slot) readField(menu, "encodedPatternSlot");
            encodedPatternSlot.set(encodedPattern.copy());
            host.requestClearProcessingSlotRuleDraft(slot);
        }

        ProcessingSlotRuleStatus restored = host.getProcessingSlotRuleStatus(slot);
        Map<String, Object> result = patternSnapshot(menu, slot, restored);
        result.put("target", nearbyPartMap(target));
        result.put("encodedPatternCreated", encodedPatternCreated);
        result.put("encodedPatternItem", encodedItem);
        result.put("encodedMetadataPresent", new ProcessingPatternReplacementPersistence()
                .hasReplacementMetadata(encodedPattern));
        result.put("restoredAfterEncode", restored != null && restored.visibleDraft() != null
                && restored.visibleDraft().explicitCandidateIds().containsAll(explicitCandidates));
        return result;
    }

    private Map<String, Object> handlePatternExecutionProbe(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        Level level = player == null ? Minecraft.getInstance().level : player.level();
        ItemStack sourceStack = stackFromId(params.getOrDefault("source", "minecraft:iron_ingot"));
        ItemStack outputStack = stackFromId(params.getOrDefault("output", "minecraft:iron_block"));
        ItemStack candidateStack = stackFromId(params.getOrDefault("candidate", "minecraft:copper_ingot"));
        if (sourceStack.isEmpty() || outputStack.isEmpty() || candidateStack.isEmpty()) {
            return error("Source, output, and candidate must be valid items");
        }

        ResourceLocation sourceId = BuiltInRegistries.ITEM.getKey(sourceStack.getItem());
        ResourceLocation candidateId = BuiltInRegistries.ITEM.getKey(candidateStack.getItem());
        Set<ResourceLocation> selectedTags = parseResourceSet(params.get("tags"));
        Set<ResourceLocation> explicitCandidates = parseResourceSet(params.get("candidates"));
        if (explicitCandidates.isEmpty()) {
            explicitCandidates.add(candidateId);
        }

        ItemStack encodedPattern = createEncodedProcessingPattern(sourceStack, outputStack);
        ProcessingPatternReplacementPersistence persistence = new ProcessingPatternReplacementPersistence();
        persistence.writeRules(encodedPattern, List.of(new ProcessingPatternSlotReplacementRule(
                0,
                sourceId,
                selectedTags,
                explicitCandidates
        )));

        IPatternDetails helperDecoded = PatternDetailsHelper.decodePattern(encodedPattern, level);
        IPatternDetails directDecoded = new ProcessingPatternReplacementDecoder().decodePattern(encodedPattern, level);
        IPatternDetails decoded = helperDecoded != null ? helperDecoded : directDecoded;

        Map<String, Object> result = ok();
        result.put("encodedMetadataPresent", persistence.hasReplacementMetadata(encodedPattern));
        result.put("encodedProcessingComponentPresent", encodedPattern.get(AEComponents.ENCODED_PROCESSING_PATTERN) != null);
        result.put("helperDecodedClass", helperDecoded == null ? null : helperDecoded.getClass().getName());
        result.put("directDecodedClass", directDecoded == null ? null : directDecoded.getClass().getName());
        result.put("decodedClass", decoded == null ? null : decoded.getClass().getName());
        result.put("decodedByHelper", helperDecoded instanceof ReplacementAwareProcessingPattern);
        result.put("replacementAware", decoded instanceof ReplacementAwareProcessingPattern);
        result.put("source", stackId(sourceStack));
        result.put("candidate", stackId(candidateStack));

        if (decoded == null) {
            result.put("ok", false);
            result.put("error", "PatternDetailsHelper and direct decoder both returned null");
            return result;
        }

        AEItemKey sourceKey = itemKey(sourceStack);
        AEItemKey candidateKey = itemKey(candidateStack);
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(candidateKey, 1L, Actionable.MODULATE);
        KeyCounter[] extracted = CraftingCpuHelper.extractPatternInputs(
                decoded,
                inventory,
                level,
                new KeyCounter(),
                new KeyCounter()
        );

        result.put("extracted", extracted != null);
        result.put("extractedSourceCount", extracted == null ? 0L : extracted[0].get(sourceKey));
        result.put("extractedCandidateCount", extracted == null ? 0L : extracted[0].get(candidateKey));
        if (extracted == null) {
            result.put("ok", false);
            result.put("error", "CraftingCpuHelper could not extract replacement-aware inputs");
            return result;
        }

        List<Map<String, Object>> pushedInputs = new ArrayList<>();
        decoded.pushInputsToExternalInventory(extracted, (what, amount) -> {
            Map<String, Object> pushed = new LinkedHashMap<>();
            pushed.put("key", keyId(what));
            pushed.put("amount", amount);
            pushedInputs.add(pushed);
        });
        result.put("pushedInputs", pushedInputs);
        result.put("candidateUsedForPush", pushedInputs.stream()
                .anyMatch(entry -> candidateId.toString().equals(entry.get("key")) && Long.valueOf(1L).equals(entry.get("amount"))));
        if (!Boolean.TRUE.equals(result.get("decodedByHelper"))
                || !Boolean.TRUE.equals(result.get("replacementAware"))
                || !Long.valueOf(1L).equals(result.get("extractedCandidateCount"))
                || !Boolean.TRUE.equals(result.get("candidateUsedForPush"))) {
            result.put("ok", false);
            result.put("error", "Replacement-aware processing probe did not use the replacement candidate");
        }
        return result;
    }

    private Map<String, Object> handleEmitterSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        MultiLevelEmitterMenu.RuntimeMenu menu = currentEmitterMenu(minecraft);
        if (menu == null) {
            MultiLevelEmitterRuntimePart runtimePart = currentOrNearbyEmitter(minecraft, Map.of());
            if (runtimePart != null) {
                return emitterSnapshot(menuForRuntime(runtimePart, minecraft), minecraft, null);
            }
            Map<String, Object> result = baseState(minecraft);
            result.put("ok", false);
            result.put("error", "Current menu is not MultiLevelEmitterMenu.RuntimeMenu and no nearby runtime part was found");
            return result;
        }
        return emitterSnapshot(menu, minecraft, null);
    }

    private Map<String, Object> handleEmitterConfigure(Map<String, String> params) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiLevelEmitterMenu.RuntimeMenu menu = currentEmitterMenu(minecraft);
        MultiLevelEmitterRuntimePart runtimePart = menu == null
                ? currentOrNearbyEmitter(minecraft, params)
                : emitterRuntimePart(menu);
        if (menu == null) {
            if (runtimePart == null) {
                return error("Current menu is not MultiLevelEmitterMenu.RuntimeMenu and no nearby runtime part was found");
            }
            menu = menuForRuntime(runtimePart, minecraft);
        }

        if (params.containsKey("slots")) {
            menu.setConfiguredSlotCount(parseInt(params, "slots", menu.configuredSlotCount()));
        }
        for (int slot = 0; slot < menu.configuredSlotCount(); slot++) {
            String key = "threshold" + slot;
            if (params.containsKey(key)) {
                menu.commitThreshold(slot, parseLong(params, key, menu.thresholdForSlot(slot)), Long.MAX_VALUE);
            }
        }
        MultiLevelEmitterMenu.RuntimeMenu configuredMenu = menu;
        applyCycleCount(params, "comparisonCycles", slot -> configuredMenu.cycleComparisonMode(slot));
        applyCycleCount(params, "matchingCycles", slot -> configuredMenu.cycleMatchingMode(slot));
        applyCycleCount(params, "craftingCycles", slot -> configuredMenu.cycleCraftingMode(slot));

        if (runtimePart == null) {
            runtimePart = emitterRuntimePart(menu);
        }
        if (runtimePart != null) {
            applyConfiguredItems(params, runtimePart);
        }
        if (params.containsKey("expression")) {
            menu.applyExpression(params.get("expression"));
        }

        Boolean monitorResult = null;
        if (runtimePart != null && params.containsKey("observed")) {
            monitorResult = runtimePart.evaluateConfiguredOutput(
                    parseLongList(params.get("observed")),
                    Boolean.parseBoolean(params.getOrDefault("networkActive", "true"))
            );
        }

        return emitterSnapshot(menu, minecraft, monitorResult);
    }

    private Map<String, Object> handlePatternSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        PatternEncodingTermMenu menu = currentPatternMenu(minecraft);
        if (menu == null) {
            Map<String, Object> result = baseState(minecraft);
            result.put("ok", false);
            result.put("error", "Current menu is not PatternEncodingTermMenu");
            return result;
        }
        return patternSnapshot(menu, parseInt(Map.of(), "slot", 0), null);
    }

    private Map<String, Object> handlePatternConfigure(Map<String, String> params) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        PatternEncodingTermMenu menu = currentPatternMenu(minecraft);
        if (menu == null) {
            return error("Current menu is not PatternEncodingTermMenu");
        }
        if (!(menu instanceof ProcessingSlotRuleHost host)) {
            return error("PatternEncodingTermMenu is missing ProcessingSlotRuleHost");
        }

        int slot = parseInt(params, "slot", 0);
        menu.setMode(EncodingMode.PROCESSING);
        ItemStack sourceStack = stackFromId(params.getOrDefault("source", "minecraft:iron_ingot"));
        ItemStack outputStack = stackFromId(params.getOrDefault("output", "minecraft:iron_block"));
        ItemStack candidateStack = stackFromId(params.getOrDefault("candidate", "minecraft:copper_ingot"));
        FakeSlot[] inputSlots = menu.getProcessingInputSlots();
        FakeSlot[] outputSlots = menu.getProcessingOutputSlots();
        if (slot < 0 || slot >= inputSlots.length) {
            return error("Invalid processing input slot: " + slot);
        }
        inputSlots[slot].set(sourceStack.copy());
        if (outputSlots.length > 0) {
            outputSlots[0].set(outputStack.copy());
        }

        Set<ResourceLocation> selectedTags = parseResourceSet(params.get("tags"));
        Set<ResourceLocation> explicitCandidates = parseResourceSet(params.get("candidates"));
        if (explicitCandidates.isEmpty()) {
            explicitCandidates.add(BuiltInRegistries.ITEM.getKey(candidateStack.getItem()));
        }
        host.requestSaveProcessingSlotRuleDraft(new ProcessingSlotRulePayload(slot, selectedTags, explicitCandidates));

        ItemStack encodedPattern = invokeEncodeProcessingPattern(menu);
        String encodedItem = stackId(encodedPattern);
        boolean encodedPatternCreated = !encodedPattern.isEmpty();
        if (encodedPatternCreated) {
            Slot encodedPatternSlot = (Slot) readField(menu, "encodedPatternSlot");
            encodedPatternSlot.set(encodedPattern.copy());
            host.requestClearProcessingSlotRuleDraft(slot);
        }

        ProcessingSlotRuleStatus restored = host.getProcessingSlotRuleStatus(slot);
        Map<String, Object> result = patternSnapshot(menu, slot, restored);
        result.put("encodedPatternCreated", encodedPatternCreated);
        result.put("encodedPatternItem", encodedItem);
        result.put("restoredAfterEncode", restored != null && restored.visibleDraft() != null);
        return result;
    }

    private Map<String, Object> handleContinuationProbe(Map<String, String> params) {
        Minecraft minecraft = Minecraft.getInstance();
        Map<String, Object> result = baseState(minecraft);
        result.put("craftConfirmMenuMixinHostAssignable", CraftingContinuationSubmitBridge.ContinuationModeHost.class.isAssignableFrom(CraftConfirmMenu.class));
        result.put("craftingCpuMenuMixinHostAssignable", CraftingContinuationStatusService.SelectedCpuDetailHost.class.isAssignableFrom(CraftingCPUMenu.class));
        result.put("craftingCpuScreenMixinHostAssignable", CraftingContinuationStatusService.WaitingStackProjectionHost.class.isAssignableFrom(CraftingCPUScreen.class));

        AbstractContainerMenu currentMenu = currentMenu(minecraft);
        if (currentMenu instanceof CraftConfirmMenu confirmMenu) {
            CraftingContinuationMode before = CraftingContinuationSubmitBridge.getConfirmMode(confirmMenu);
            CraftingContinuationMode requested = CraftingContinuationMode.valueOf(
                    params.getOrDefault("mode", "IGNORE_MISSING").toUpperCase()
            );
            CraftingContinuationSubmitBridge.setConfirmMode(confirmMenu, requested);
            result.put("confirmMenuPresent", true);
            result.put("confirmModeBefore", before.name());
            result.put("confirmModeAfter", CraftingContinuationSubmitBridge.getConfirmMode(confirmMenu).name());
        } else {
            result.put("confirmMenuPresent", false);
        }

        if (currentMenu instanceof CraftingContinuationStatusService.SelectedCpuDetailHost host) {
            result.put("cpuMenuProjectionPresent", true);
            result.put("cpuPartialWaiting", host.chexsonsaeutils$partialWaiting());
            result.put("cpuFinalOutput", host.chexsonsaeutils$finalOutput());
            result.put("cpuRequestedAmount", host.chexsonsaeutils$requestedAmount());
            result.put("cpuWaitingBranchLines", host.chexsonsaeutils$waitingBranchLines());
            result.put("cpuWaitingStackLines", host.chexsonsaeutils$waitingStackLines());
        } else {
            result.put("cpuMenuProjectionPresent", false);
        }

        if (minecraft.screen instanceof CraftingContinuationStatusService.WaitingStackProjectionHost host) {
            result.put("cpuScreenProjectionPresent", true);
            result.put("screenPartialWaiting", host.chexsonsaeutils$partialWaiting());
            result.put("screenWaitingStackAmounts", host.chexsonsaeutils$waitingStackAmounts());
        } else {
            result.put("cpuScreenProjectionPresent", false);
        }
        result.put("craftConfirmScreenPresent", minecraft.screen instanceof CraftConfirmScreen);
        result.put("craftingCpuScreenPresent", minecraft.screen instanceof CraftingCPUScreen);
        return result;
    }

    private Map<String, Object> handleContinuationButtonProbe(Map<String, String> params) {
        Minecraft minecraft = Minecraft.getInstance();
        Map<String, Object> result = baseState(minecraft);
        result.put("craftConfirmScreenPresent", minecraft.screen instanceof CraftConfirmScreen);
        AbstractContainerMenu currentMenu = currentMenu(minecraft);
        if (!(minecraft.screen instanceof CraftConfirmScreen screen) || !(currentMenu instanceof CraftConfirmMenu confirmMenu)) {
            result.put("ok", false);
            result.put("error", "Current screen/menu is not CraftConfirmScreen/CraftConfirmMenu");
            return result;
        }

        Button button = findContinuationModeButton(screen);
        CraftingContinuationMode before = CraftingContinuationSubmitBridge.getConfirmMode(confirmMenu);
        result.put("buttonPresent", button != null);
        if (button != null) {
            result.put("buttonMessageBefore", button.getMessage().getString());
            result.put("buttonVisibleBefore", button.visible);
            result.put("buttonActiveBefore", button.active);
            result.put("buttonX", button.getX());
            result.put("buttonY", button.getY());
            result.put("buttonWidth", button.getWidth());
            result.put("buttonHeight", button.getHeight());
        }
        result.put("confirmModeBefore", before.name());

        boolean click = Boolean.parseBoolean(params.getOrDefault("click", "true"));
        if (button != null && click) {
            button.onPress();
        }

        CraftingContinuationMode after = CraftingContinuationSubmitBridge.getConfirmMode(confirmMenu);
        result.put("clicked", button != null && click);
        result.put("confirmModeAfter", after.name());
        if (button != null) {
            result.put("buttonMessageAfter", button.getMessage().getString());
            result.put("buttonVisibleAfter", button.visible);
            result.put("buttonActiveAfter", button.active);
        }
        result.put("modeChangedByButton", button != null && before != after);
        if (click && (button == null || !button.visible || !button.active || before == after)) {
            result.put("ok", false);
            result.put("error", "Continuation mode button was not present, usable, or able to change mode");
        }
        return result;
    }

    private Map<String, Object> handleContinuationConfirmProbe(Map<String, String> params) {
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return error("No server player is available");
        }

        NearbyPart<IPatternTerminalMenuHost> target = findPatternTerminalNearby(player, parseInt(params, "radius", 8));
        if (target == null) {
            return error("No nearby IPatternTerminalMenuHost was found");
        }

        CraftConfirmMenu menu = new CraftConfirmMenu(0, player.getInventory(), target.part());
        CraftingContinuationMode before = CraftingContinuationSubmitBridge.getConfirmMode(menu);
        CraftingContinuationMode requested = CraftingContinuationMode.valueOf(
                params.getOrDefault("mode", "IGNORE_MISSING").toUpperCase()
        );
        CraftingContinuationSubmitBridge.setConfirmMode(menu, requested);

        Map<String, Object> result = ok();
        result.put("target", nearbyPartMap(target));
        result.put("craftConfirmMenuClass", menu.getClass().getName());
        result.put("craftConfirmMenuMixinHostAssignable", menu instanceof CraftingContinuationSubmitBridge.ContinuationModeHost);
        result.put("confirmModeBefore", before.name());
        result.put("confirmModeAfter", CraftingContinuationSubmitBridge.getConfirmMode(menu).name());
        result.put("threadModeDuringScope", CraftingContinuationSubmitBridge.withContinuationMode(
                requested,
                () -> CraftingContinuationSubmitBridge.currentMode().name()
        ));
        result.put("threadModeAfterScope", CraftingContinuationSubmitBridge.currentMode().name());
        result.put("craftingCpuMenuMixinHostAssignable", CraftingContinuationStatusService.SelectedCpuDetailHost.class.isAssignableFrom(CraftingCPUMenu.class));
        result.put("craftingCpuScreenMixinHostAssignable", CraftingContinuationStatusService.WaitingStackProjectionHost.class.isAssignableFrom(CraftingCPUScreen.class));
        return result;
    }

    private Map<String, Object> emitterSnapshot(
            MultiLevelEmitterMenu.RuntimeMenu menu,
            Minecraft minecraft,
            Boolean monitorResult
    ) {
        Map<String, Object> result = baseState(minecraft);
        result.put("menuBoundRuntimePart", menu.hasRuntimePartBinding());
        result.put("hasFuzzyCard", menu.hasFuzzyCardInstalled());
        result.put("hasCraftingCard", menu.hasCraftingCardInstalled());
        result.put("monitorResult", monitorResult);

        MultiLevelEmitterScreen.RuntimeScreenState state = MultiLevelEmitterScreen.snapshotState(menu);
        result.put("configuredSlots", state.configuredSlots());
        result.put("markedSlots", state.markedSlots());
        result.put("visibleSlots", state.visibleSlots());
        result.put("totalSlots", state.totalSlots());
        result.put("appliedExpressionText", state.appliedExpressionText());
        result.put("expressionOwnership", state.expressionOwnership().name());
        result.put("expressionInvalid", state.expressionInvalid());
        List<Map<String, Object>> slots = new ArrayList<>();
        for (MultiLevelEmitterScreen.SlotView slot : state.slots()) {
            Map<String, Object> slotMap = new LinkedHashMap<>();
            slotMap.put("slotIndex", slot.slotIndex());
            slotMap.put("enabled", slot.enabled());
            slotMap.put("configured", slot.configured());
            slotMap.put("marked", slot.marked());
            slotMap.put("threshold", slot.threshold());
            slotMap.put("comparisonMode", slot.comparisonMode().name());
            slotMap.put("matchingMode", slot.matchingMode().name());
            slotMap.put("craftingMode", slot.craftingMode().name());
            slotMap.put("duplicateEmitToCraftTarget", slot.duplicateEmitToCraftTarget());
            slots.add(slotMap);
        }
        result.put("slots", slots);
        return result;
    }

    private Map<String, Object> emitterRuntimeSnapshot(
            MultiLevelEmitterRuntimePart runtimePart,
            NearbyPart<MultiLevelEmitterRuntimePart> target,
            Boolean monitorResult
    ) {
        Map<String, Object> result = ok();
        result.put("target", nearbyPartMap(target));
        result.put("monitorResult", monitorResult);
        result.put("configuredSlots", runtimePart.configuredItemCount());
        result.put("markedSlots", runtimePart.markedItemCount());
        result.put("appliedExpressionText", runtimePart.appliedExpressionText());
        result.put("expressionOwnership", runtimePart.expressionOwnership().name());
        result.put("expressionInvalid", runtimePart.expressionIsInvalid());
        result.put("hasFuzzyCard", runtimePart.hasFuzzyCardInstalled());
        result.put("hasCraftingCard", runtimePart.hasCraftingCardInstalled());
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int slot = 0; slot < runtimePart.configuredItemCount(); slot++) {
            Map<String, Object> slotMap = new LinkedHashMap<>();
            slotMap.put("slotIndex", slot);
            slotMap.put("marked", runtimePart.hasConfiguredItem(slot));
            slotMap.put("item", stackId(runtimePart.configuredItemStack(slot)));
            slotMap.put("threshold", runtimePart.thresholds().getOrDefault(slot, 1L));
            List<MultiLevelEmitterPart.ComparisonMode> comparisonModes = runtimePart.comparisonModes();
            slotMap.put("comparisonMode", slot < comparisonModes.size()
                    ? comparisonModes.get(slot).name()
                    : MultiLevelEmitterPart.ComparisonMode.GREATER_OR_EQUAL.name());
            slotMap.put("matchingMode", runtimePart.matchingModeForSlot(slot).name());
            slotMap.put("craftingMode", runtimePart.craftingModeForSlot(slot).name());
            slotMap.put("duplicateEmitToCraftTarget", runtimePart.hasDuplicateEmitToCraftTarget(slot));
            slots.add(slotMap);
        }
        result.put("slots", slots);
        return result;
    }

    private Map<String, Object> patternSnapshot(
            PatternEncodingTermMenu menu,
            int slot,
            ProcessingSlotRuleStatus suppliedStatus
    ) {
        Map<String, Object> result = baseState(Minecraft.getInstance());
        result.put("mode", menu.getMode().name());
        result.put("processingInputSlots", menu.getProcessingInputSlots().length);
        result.put("processingOutputSlots", menu.getProcessingOutputSlots().length);
        result.put("slot", slot);
        if (menu instanceof ProcessingSlotRuleHost host) {
            ProcessingSlotRuleStatus status = suppliedStatus == null ? host.getProcessingSlotRuleStatus(slot) : suppliedStatus;
            result.put("sourceStack", stackId(host.getProcessingSlotRuleSourceStack(slot)));
            result.put("status", status == null ? "" : status.visualState().name());
            result.put("draft", draftMap(status == null ? null : status.visibleDraft()));
        } else {
            result.put("error", "PatternEncodingTermMenu is missing ProcessingSlotRuleHost");
        }
        return result;
    }

    private Map<String, Object> highCapacitySnapshot(
            HighCapacityCraftingMachineBlockEntity blockEntity,
            NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> target
    ) {
        PatternBenchmarkSnapshot snapshot = blockEntity.snapshotBenchmark();
        Map<String, Object> result = ok();
        result.put("target", nearbyBlockEntityMap(target));
        result.put("pageIndex", snapshot.pageIndex());
        result.put("pageCount", snapshot.pageCount());
        result.put("totalPatternSlots", snapshot.totalPatternSlots());
        result.put("visiblePatternSlots", blockEntity.getVisiblePatternSlots());
        result.put("activePatternSlots", snapshot.activePatternSlots());
        result.put("decodedPatternCount", snapshot.decodedPatternCount());
        result.put("queuedTasks", snapshot.queuedTasks());
        result.put("runningTasks", snapshot.runningTasks());
        result.put("queueDepth", snapshot.queuedTasks() + snapshot.runningTasks());
        result.put("laneCount", snapshot.laneCount());
        result.put("currentOperationTicks", snapshot.currentOperationTicks());
        result.put("baseOperationTicks", snapshot.baseOperationTicks());
        result.put("perTickWorkUnits", snapshot.perTickWorkUnits());
        result.put("outputBufferSlotsUsed", snapshot.outputBufferSlotsUsed());
        result.put("totalNonEmptyPatternSlots", snapshot.totalNonEmptyPatternSlots());
        result.put("activePageStartSlot", snapshot.activePageStartSlot());
        result.put("activePageEndSlot", snapshot.activePageEndSlot());
        result.put("localOptimizationHitCount", snapshot.localOptimizationHitCount());
        result.put("decodePatternCount", snapshot.decodePatternCount());
        result.put("decodeCacheHitCount", snapshot.decodeCacheHitCount());
        result.put("dirtyRefreshScannedSlots", snapshot.dirtyRefreshScannedSlots());
        result.put("providerUpdateCount", snapshot.providerUpdateCount());
        result.put("jobsSubmitted", snapshot.jobsSubmitted());
        result.put("jobsCompleted", snapshot.jobsCompleted());
        result.put("outputBufferRetryCount", snapshot.outputBufferRetryCount());
        result.put("outputBufferFlushCount", snapshot.outputBufferFlushCount());
        result.put("networkPatternExposureCount", snapshot.networkPatternExposureCount());
        result.put("aeCraftingLookupCount", snapshot.aeCraftingLookupCount());
        result.put("aeCraftingLookupHitCount", snapshot.aeCraftingLookupHitCount());
        result.put("aeCraftingPlanCount", snapshot.aeCraftingPlanCount());
        result.put("aeCraftingPlanSuccessCount", snapshot.aeCraftingPlanSuccessCount());
        result.put("planningRequestCount", snapshot.planningRequestCount());
        result.put("planningSuccessCount", snapshot.planningSuccessCount());
        result.put("planningFailureCount", snapshot.planningFailureCount());
        result.put("planningChunkCount", snapshot.planningChunkCount());
        result.put("largestPlanningChunkSize", snapshot.largestPlanningChunkSize());
        result.put("planningAggregationHitCount", snapshot.planningAggregationHitCount());
        result.put("planningAggregationFallbackCount", snapshot.planningAggregationFallbackCount());
        result.put("planningReplacementPathHitCount", snapshot.planningReplacementPathHitCount());
        result.put("planningWallClockNanosMax", snapshot.planningWallClockNanosMax());
        result.put("planningRequestedAmountMax", snapshot.planningRequestedAmountMax());
        result.put("nonFormalProviderHitCount", snapshot.nonFormalProviderHitCount());
        result.put("formalMachineOptimizationHitCount", snapshot.formalMachineOptimizationHitCount());
        result.put("planningEstimatedWorkMax", snapshot.planningEstimatedWorkMax());
        result.put("planningWorkTriggeredCount", snapshot.planningWorkTriggeredCount());
        result.put("nodeHostCapabilityPresent", snapshot.nodeHostCapabilityPresent());
        result.put("forcedProviderRefreshCount", snapshot.forcedProviderRefreshCount());
        result.put("deterministicPlanningHitCount", snapshot.deterministicPlanningHitCount());
        result.put("deterministicPlanningFallbackCount", snapshot.deterministicPlanningFallbackCount());
        result.put("tickBudgetHardStopCount", snapshot.tickBudgetHardStopCount());
        result.put("formalTimingCorrectionCount", snapshot.formalTimingCorrectionCount());
        result.put("formalTimingProgressClampCount", snapshot.formalTimingProgressClampCount());
        result.put("formalTimingEtaClampCount", snapshot.formalTimingEtaClampCount());
        result.put("formalStatusHeartbeatCount", snapshot.formalStatusHeartbeatCount());
        result.put("cpuWaitingReturnBudgetStopCount", snapshot.cpuWaitingReturnBudgetStopCount());
        result.put("largestCpuWaitingReturnAmount", snapshot.largestCpuWaitingReturnAmount());
        result.put("cpuWaitingReturnOverBudgetCount", snapshot.cpuWaitingReturnOverBudgetCount());
        result.put("cpuWaitingReturnAmount", snapshot.cpuWaitingReturnAmount());
        result.put("cpuWaitingAeFallbackPartialInsertCount", snapshot.cpuWaitingAeFallbackPartialInsertCount());
        result.put("cpuWaitingNoProgressRetries", snapshot.cpuWaitingNoProgressRetries());
        result.put("cpuWaitingRouteNanosMax", snapshot.cpuWaitingRouteNanosMax());
        result.put("maxTickBudgetNanosObserved", snapshot.maxTickBudgetNanosObserved());
        result.put("aeStorageInsertAttemptCount", snapshot.aeStorageInsertAttemptCount());
        result.put("aeStorageInsertSuccessCount", snapshot.aeStorageInsertSuccessCount());
        result.put("aeStorageInsertFallbackCount", snapshot.aeStorageInsertFallbackCount());
        result.put("peakRunningTasks", snapshot.peakRunningTasks());
        result.put("peakRunningUniquePatterns", snapshot.peakRunningUniquePatterns());
        result.put("submittedUniquePatternCount", snapshot.submittedUniquePatternCount());
        result.put("coalescedTaskCount", snapshot.coalescedTaskCount());
        result.put("coalescedJobsSaved", snapshot.coalescedJobsSaved());
        result.put("maxExecutionCountPerTaskObserved", snapshot.maxExecutionCountPerTaskObserved());
        result.put("batchedAeReturnCount", snapshot.batchedAeReturnCount());
        result.put("pendingAeReturnCount", snapshot.pendingAeReturnCount());
        result.put("aeReturnBlockedTicks", snapshot.aeReturnBlockedTicks());
        result.put("aeReturnRetryCount", snapshot.aeReturnRetryCount());
        result.put("fastPathAcceptedCount", snapshot.fastPathAcceptedCount());
        result.put("fastPathFallbackCount", snapshot.fastPathFallbackCount());
        result.put("outstandingLogicalExecutions", snapshot.outstandingLogicalExecutions());
        result.put("distinctBatchKeys", snapshot.distinctBatchKeys());
        result.put("activeLaneCapacity", snapshot.activeLaneCapacity());
        result.put("effectiveLaneCount", snapshot.effectiveLaneCount());
        result.put("effectiveCompletionBudget", snapshot.effectiveCompletionBudget());
        result.put("effectiveDispatchBudget", snapshot.effectiveDispatchBudget());
        result.put("effectiveCompletionSliceBudget", snapshot.effectiveCompletionSliceBudget());
        result.put("fastPathExtractionBudget", snapshot.fastPathExtractionBudget());
        result.put("softBudget", snapshot.softBudget());
        result.put("hardBudget", snapshot.hardBudget());
        result.put("dynamicScaleUpCount", snapshot.dynamicScaleUpCount());
        result.put("dynamicScaleDownCount", snapshot.dynamicScaleDownCount());
        result.put("largestObservedBatchSize", snapshot.largestObservedBatchSize());
        result.put("largestCompletionSliceExecutionsObserved", snapshot.largestCompletionSliceExecutionsObserved());
        result.put("pendingCompletionTicks", snapshot.pendingCompletionTicks());
        result.put("completionSlicesProcessed", snapshot.completionSlicesProcessed());
        result.put("templatedCompletionHitCount", snapshot.templatedCompletionHitCount());
        result.put("templatedCompletionSavedExecutions", snapshot.templatedCompletionSavedExecutions());
        result.put("pendingCompletionCount", snapshot.pendingCompletionCount());
        result.put("completionQueuePeak", snapshot.completionQueuePeak());
        result.put("completionBacklogExecutionsPeak", snapshot.completionBacklogExecutionsPeak());
        result.put("submitBenchmarkCount", snapshot.submitBenchmarkCount());
        result.put("submitBenchmarkSuccessCount", snapshot.submitBenchmarkSuccessCount());
        result.put("submitBenchmarkWallClockNanosMax", snapshot.submitBenchmarkWallClockNanosMax());
        result.put("maxExecutableRunsHitCount", snapshot.maxExecutableRunsHitCount());
        result.put("maxExecutableRunsFallbackCount", snapshot.maxExecutableRunsFallbackCount());
        result.put("bulkExtractionLogicalExecutionsMax", snapshot.bulkExtractionLogicalExecutionsMax());
        result.put("templatedDispatchHitCount", snapshot.templatedDispatchHitCount());
        result.put("compileCacheHitCount", snapshot.compileCacheHitCount());
        result.put("providerOverpressureRejectCount", snapshot.providerOverpressureRejectCount());
        result.put("mainNodeReady", blockEntity.getMainNode().isReady());
        result.put("mainNodeActive", blockEntity.getMainNode().isActive());
        result.put("gridPresent", blockEntity.getGrid() != null);
        result.put("connectionCount", blockEntity.getMainNode().getNode() == null
                ? 0
                : blockEntity.getMainNode().getNode().getConnections().size());
        result.put("isBusy", blockEntity.isBusy());
        return result;
    }

    private MultiLevelEmitterMenu.RuntimeMenu currentEmitterMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = currentMenu(minecraft);
        return menu instanceof MultiLevelEmitterMenu.RuntimeMenu runtimeMenu ? runtimeMenu : null;
    }

    private PatternEncodingTermMenu currentPatternMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = currentMenu(minecraft);
        return menu instanceof PatternEncodingTermMenu patternMenu ? patternMenu : null;
    }

    private AbstractContainerMenu currentMenu(Minecraft minecraft) {
        return minecraft.player == null ? null : minecraft.player.containerMenu;
    }

    private Map<String, Object> baseState(Minecraft minecraft) {
        Map<String, Object> result = ok();
        result.put("screen", minecraft.screen == null ? "" : minecraft.screen.getClass().getName());
        result.put("menu", currentMenu(minecraft) == null ? "" : currentMenu(minecraft).getClass().getName());
        result.put("playerPresent", minecraft.player != null);
        result.put("levelPresent", minecraft.level != null);
        return result;
    }

    private MultiLevelEmitterRuntimePart currentOrNearbyEmitter(Minecraft minecraft, Map<String, String> params) {
        MultiLevelEmitterMenu.RuntimeMenu menu = currentEmitterMenu(minecraft);
        MultiLevelEmitterRuntimePart runtimePart = menu == null ? null : emitterRuntimePart(menu);
        if (runtimePart != null) {
            return runtimePart;
        }
        AtomicReference<MultiLevelEmitterRuntimePart> reference = new AtomicReference<>();
        Object result = onServerThread(() -> {
            ServerPlayer player = serverPlayer();
            NearbyPart<MultiLevelEmitterRuntimePart> nearby = player == null
                    ? null
                    : findEmitterNearby(player, parseInt(params, "radius", 8));
            reference.set(nearby == null ? null : nearby.part());
            return ok();
        });
        return result instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok")) ? null : reference.get();
    }

    private MultiLevelEmitterMenu.RuntimeMenu menuForRuntime(
            MultiLevelEmitterRuntimePart runtimePart,
            Minecraft minecraft
    ) {
        return new MultiLevelEmitterMenu.RuntimeMenu(
                0,
                minecraft.player == null ? null : minecraft.player.getInventory(),
                MultiLevelEmitterMenu.registeredMenuType(),
                runtimePart
        );
    }

    private MultiLevelEmitterRuntimePart emitterRuntimePart(MultiLevelEmitterMenu.RuntimeMenu menu) {
        try {
            Object value = readField(menu, "runtimePart");
            return value instanceof MultiLevelEmitterRuntimePart runtimePart ? runtimePart : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private void applyConfiguredItems(Map<String, String> params, MultiLevelEmitterRuntimePart runtimePart) {
        for (int slot = 0; slot < runtimePart.configuredItemCount(); slot++) {
            String key = "item" + slot;
            if (!params.containsKey(key)) {
                continue;
            }
            ItemStack stack = stackFromId(params.get(key));
            runtimePart.getConfig().setStack(slot, stack.isEmpty() ? null : new GenericStack(AEItemKey.of(stack), 1L));
        }
    }

    private void applyRuntimeThresholds(Map<String, String> params, MultiLevelEmitterRuntimePart runtimePart) {
        for (int slot = 0; slot < runtimePart.configuredItemCount(); slot++) {
            String key = "threshold" + slot;
            if (params.containsKey(key)) {
                runtimePart.updateThresholdFromUi(
                        slot,
                        MultiLevelEmitterMenu.sanitizeAndClampThreshold(
                                parseLong(params, key, runtimePart.thresholds().getOrDefault(slot, 1L)),
                                Long.MAX_VALUE
                        )
                );
            }
        }
    }

    private static void applyRuntimeCycleCount(
            Map<String, String> params,
            String key,
            SlotConsumer consumer
    ) {
        int cycles = parseInt(params, key, 0);
        for (int slot = 0; slot < 64; slot++) {
            int slotCycles = parseInt(params, key + slot, cycles);
            for (int iteration = 0; iteration < slotCycles; iteration++) {
                consumer.accept(slot);
            }
        }
    }

    private ItemStack invokeEncodeProcessingPattern(PatternEncodingTermMenu menu) throws Exception {
        Method method = findMethod(PatternEncodingTermMenu.class, "encodeProcessingPattern");
        method.setAccessible(true);
        Object value = method.invoke(menu);
        return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private ItemStack createEncodedProcessingPattern(ItemStack sourceStack, ItemStack outputStack) {
        ItemStack encodedPattern = AEItems.PROCESSING_PATTERN.stack();
        encodedPattern.set(AEComponents.ENCODED_PROCESSING_PATTERN, new EncodedProcessingPattern(
                List.of(genericStack(sourceStack, 1L)),
                List.of(genericStack(outputStack, 1L))
        ));
        return encodedPattern;
    }

    private static GenericStack genericStack(ItemStack stack, long amount) {
        return new GenericStack(itemKey(stack), amount);
    }

    private static AEItemKey itemKey(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return AEItemKey.of(copy);
    }

    private static String keyId(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            return itemKey.getId().toString();
        }
        return String.valueOf(key);
    }

    private Button findContinuationModeButton(CraftConfirmScreen screen) {
        try {
            Object value = readField(screen, "chexsonsaeutils$continuationModeButton");
            if (value instanceof Button button) {
                return button;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        for (Object child : screen.children()) {
            if (child instanceof Button button && isContinuationModeButton(button)) {
                return button;
            }
        }
        return null;
    }

    private static boolean isContinuationModeButton(Button button) {
        String message = button.getMessage().getString();
        return button.getWidth() == 98
                || "Default".equals(message)
                || "Ignore Missing".equals(message);
    }
    private ServerPlayer serverPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(minecraft.player.getUUID());
    }

    private NearbyPart<MultiLevelEmitterRuntimePart> findEmitterNearby(ServerPlayer player, int radius) {
        for (NearbyPart<IPart> part : scanPartInstances(player, radius)) {
            if (part.part() instanceof MultiLevelEmitterRuntimePart emitter) {
                return new NearbyPart<>(part.pos(), part.side(), emitter);
            }
        }
        return null;
    }

    private NearbyPart<IPatternTerminalMenuHost> findPatternTerminalNearby(ServerPlayer player, int radius) {
        for (NearbyPart<IPart> part : scanPartInstances(player, radius)) {
            if (part.part() instanceof IPatternTerminalMenuHost host) {
                return new NearbyPart<>(part.pos(), part.side(), host);
            }
        }
        return null;
    }

    private NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> findHighCapacityCraftingMachineNearby(ServerPlayer player, int radius) {
        for (NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> nearby : scanBlockEntitiesNearby(
                player,
                radius,
                HighCapacityCraftingMachineBlockEntity.class
        )) {
            return nearby;
        }
        return null;
    }

    private List<Map<String, Object>> highCapacityCandidates(ServerPlayer player, int radius) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (NearbyBlockEntity<HighCapacityCraftingMachineBlockEntity> nearby : scanBlockEntitiesNearby(
                player,
                radius,
                HighCapacityCraftingMachineBlockEntity.class
        )) {
            candidates.add(nearbyBlockEntityMap(nearby));
        }
        return candidates;
    }

    private NearbyScan scanNearbyParts(ServerPlayer player, int requestedRadius) {
        int radius = Math.max(1, Math.min(32, requestedRadius));
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> emitters = new ArrayList<>();
        List<Map<String, Object>> patternTerminals = new ArrayList<>();
        for (NearbyPart<IPart> nearbyPart : scanPartInstances(player, radius)) {
            Map<String, Object> entry = nearbyPartMap(nearbyPart);
            entries.add(entry);
            if (nearbyPart.part() instanceof MultiLevelEmitterRuntimePart) {
                emitters.add(entry);
            }
            if (nearbyPart.part() instanceof IPatternTerminalMenuHost) {
                patternTerminals.add(entry);
            }
        }
        return new NearbyScan(radius, entries, emitters, patternTerminals);
    }

    private List<NearbyPart<IPart>> scanPartInstances(ServerPlayer player, int requestedRadius) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = Math.max(1, Math.min(32, requestedRadius));
        List<NearbyPart<IPart>> parts = new ArrayList<>();
        for (NearbyHost nearbyHost : scanPartHosts(player, radius)) {
            for (Direction side : Direction.values()) {
                IPart part = nearbyHost.host().getPart(side);
                if (part != null) {
                    parts.add(new NearbyPart<>(nearbyHost.pos(), side, part));
                }
            }
        }
        return parts;
    }

    private List<NearbyHost> scanPartHosts(ServerPlayer player, int requestedRadius) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = Math.max(1, Math.min(32, requestedRadius));
        List<NearbyHost> hosts = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof IPartHost host)) {
                continue;
            }
            hosts.add(new NearbyHost(pos.immutable(), host));
        }
        return hosts;
    }

    private <T extends BlockEntity> List<NearbyBlockEntity<T>> scanBlockEntitiesNearby(
            ServerPlayer player,
            int requestedRadius,
            Class<T> type
    ) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = Math.max(1, Math.min(32, requestedRadius));
        List<NearbyBlockEntity<T>> blockEntities = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (type.isInstance(blockEntity)) {
                blockEntities.add(new NearbyBlockEntity<>(pos.immutable(), type.cast(blockEntity)));
            }
        }
        return blockEntities;
    }

    private static Map<String, Object> nearbyPartMap(NearbyPart<?> nearbyPart) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pos", positionMap(nearbyPart.pos()));
        result.put("side", nearbyPart.side().getName());
        result.put("class", nearbyPart.part().getClass().getName());
        if (nearbyPart.part() instanceof IPart part) {
            IPartItem<?> item = part.getPartItem();
            result.put("item", item == null ? "" : IPartItem.getId(item).toString());
        }
        return result;
    }

    private static Map<String, Object> nearbyBlockEntityMap(NearbyBlockEntity<?> nearbyBlockEntity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pos", positionMap(nearbyBlockEntity.pos()));
        result.put("class", nearbyBlockEntity.blockEntity().getClass().getName());
        result.put("block", BuiltInRegistries.BLOCK.getKey(
                ((BlockEntity) nearbyBlockEntity.blockEntity()).getBlockState().getBlock()
        ).toString());
        return result;
    }

    private static ItemStack[] parseCraftingGrid(Map<String, String> params) {
        ItemStack[] craftingGrid = new ItemStack[9];
        boolean hasInput = false;
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            String key = "grid" + slot;
            ItemStack stack = params.containsKey(key) ? stackFromId(params.get(key)) : ItemStack.EMPTY;
            craftingGrid[slot] = stack;
            hasInput |= !stack.isEmpty();
        }
        if (!hasInput) {
            craftingGrid[0] = stackFromId("minecraft:oak_log");
        }
        return craftingGrid;
    }

    private static BlockPos findNearbyAirPlacement(ServerPlayer player, int requestedRadius) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int radius = Math.max(1, Math.min(16, requestedRadius));
        for (int dy = 0; dy <= 2; dy++) {
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, dy, -radius), center.offset(radius, dy, radius))) {
                if (level.isEmptyBlock(pos) && !level.isEmptyBlock(pos.below())) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    private static Map<String, Object> positionMap(BlockPos pos) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("x", pos.getX());
        result.put("y", pos.getY());
        result.put("z", pos.getZ());
        return result;
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static ItemStack stackFromId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.parse(itemId);
        Item item = BuiltInRegistries.ITEM.get(id);
        if (!id.equals(BuiltInRegistries.ITEM.getKey(item))) {
            throw new IllegalArgumentException("Unknown item: " + itemId);
        }
        return item.getDefaultInstance();
    }

    private static String stackId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static Map<String, Object> draftMap(ProcessingSlotRuleDraft draft) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (draft == null) {
            result.put("present", false);
            return result;
        }
        result.put("present", true);
        result.put("slotIndex", draft.slotIndex());
        result.put("sourceTagIds", draft.sourceTagIds().stream().map(ResourceLocation::toString).toList());
        result.put("selectedTagIds", draft.selectedTagIds().stream().map(ResourceLocation::toString).toList());
        result.put("explicitCandidateIds", draft.explicitCandidateIds().stream().map(ResourceLocation::toString).toList());
        return result;
    }

    private static Set<ResourceLocation> parseResourceSet(String raw) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String item : raw.split(",")) {
            if (!item.isBlank()) {
                ids.add(ResourceLocation.parse(item.trim()));
            }
        }
        return ids;
    }

    private static List<Long> parseLongList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .filter(value -> !value.isBlank())
                .map(value -> Long.parseLong(value.trim()))
                .toList();
    }

    private static void applyCycleCount(Map<String, String> params, String key, SlotConsumer consumer) {
        int cycles = parseInt(params, key, 0);
        for (int slot = 0; slot < 64; slot++) {
            int slotCycles = parseInt(params, key + slot, cycles);
            for (int iteration = 0; iteration < slotCycles; iteration++) {
                consumer.accept(slot);
            }
        }
    }

    private static int parseInt(Map<String, String> params, String key, int fallback) {
        try {
            return Integer.parseInt(params.getOrDefault(key, Integer.toString(fallback)).trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long parseLong(Map<String, String> params, String key, long fallback) {
        try {
            return Long.parseLong(params.getOrDefault(key, Long.toString(fallback)).trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Boolean safeConfigValue(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (RuntimeException ignored) {
            return value.getDefault();
        }
    }

    private static boolean safeBoolean(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    private static Map<String, Object> error(Throwable throwable) {
        return error(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", message);
        return result;
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    @FunctionalInterface
    private interface SlotConsumer {
        void accept(int slot);
    }

    private record NearbyPart<T>(BlockPos pos, Direction side, T part) {
    }

    private record NearbyBlockEntity<T>(BlockPos pos, T blockEntity) {
    }

    private record NearbyHost(BlockPos pos, IPartHost host) {
    }

    private record NearbyScan(
            int radius,
            List<Map<String, Object>> entries,
            List<Map<String, Object>> emitters,
            List<Map<String, Object>> patternTerminals
    ) {
    }

}

