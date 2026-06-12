package git.chexson.chexsonsaeutils.crafting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighCapacityCraftingMachineRegistrationTest {

    @Test
    void highCapacityCraftingMachineAnchorsStayRegistered() throws IOException {
        String modSource = readUtf8(javaSource("git/chexson/chexsonsaeutils/Chexsonsaeutils.java"));
        String contentSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/registration/ChexsonsaeutilsContent.java"
        ));
        String menuSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/menu/implementations/HighCapacityCraftingMachineMenu.java"
        ));
        String blockEntitySource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/HighCapacityCraftingMachineBlockEntity.java"
        ));
        String hostSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/blockentity/crafting/AbstractHighCapacityCraftingHostBlockEntity.java"
        ));
        String blockSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/block/crafting/HighCapacityCraftingMachineBlock.java"
        ));
        String screenSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/gui/implementations/HighCapacityCraftingMachineScreen.java"
        ));
        String screenJson = readUtf8(resourcePath("assets/ae2/screens/high_capacity_crafting_machine.json"));
        String blockState = readUtf8(resourcePath("assets/chexsonsaeutils/blockstates/high_capacity_crafting_machine.json"));
        String recipe = readUtf8(resourcePath("data/chexsonsaeutils/recipe/high_capacity_crafting_machine.json"));
        JsonObject enUs = readLang(resourcePath("assets/chexsonsaeutils/lang/en_us.json"));
        JsonObject zhCn = readLang(resourcePath("assets/chexsonsaeutils/lang/zh_cn.json"));

        assertTrue(contentSource.contains("RegisteredBlock<HighCapacityCraftingMachineBlock>"),
                "formal machine block and item must use the unified registration helper");
        assertTrue(modSource.contains("ChexsonsaeutilsContent.register(modEventBus)"),
                "main mod class must delegate deferred-register wiring to the content registry");
        assertTrue(contentSource.contains("HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK"),
                "missing formal machine block registration");
        assertTrue(contentSource.contains("HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY"),
                "missing formal machine block entity registration");
        assertTrue(contentSource.contains("HIGH_CAPACITY_CRAFTING_MACHINE_MENU"),
                "missing formal machine menu registration");
        assertTrue(contentSource.contains("Capabilities.ItemHandler.BLOCK"),
                "formal machine must expose item handler automation capability");
        assertTrue(contentSource.contains("AECapabilities.IN_WORLD_GRID_NODE_HOST"),
                "formal machine must expose AE2 in-world node host capability for cable discovery");
        assertTrue(!contentSource.contains("HIGH_CAPACITY_PATTERN_TEST"),
                "test machine content must stay removed from registration");
        assertTrue(menuSource.contains("RestrictedInputSlot.PlacableItemType.MOLECULAR_ASSEMBLER_PATTERN"),
                "formal machine must keep accepting AE2 molecular-assembler supported patterns only");
        assertTrue(blockEntitySource.contains("extends AbstractHighCapacityCraftingHostBlockEntity"),
                "formal machine must share the local host core");
        assertTrue(blockEntitySource.contains("BatchExecutionMode.SAME_PATTERN_DRAIN"),
                "formal machine constructor must default new placements to SAME_PATTERN_DRAIN");
        assertTrue(hostSource.contains(": BatchExecutionMode.OFF;"),
                "shared host must keep missing batchMode legacy fallback at OFF");
        assertTrue(blockSource.contains("onBlockRemovedFromWorld()"),
                "formal machine block must clean up external pattern persistence when removed");
        assertTrue(screenSource.contains("drawFG("),
                "formal machine screen must render custom foreground details through AE2-safe hooks");
        assertTrue(screenSource.contains("drawBG("),
                "formal machine screen must render slot highlight through AE2-safe hooks");
        assertTrue(menuSource.contains("highlightedPageSlotMask"),
                "formal machine menu must sync page-local multi-highlight mask");
        assertTrue(hostSource.contains("searchAndHighlightNext"),
                "formal machine host must support rotating search results");
        assertTrue(hostSource.contains("DynamicExecutionBudgetModel"),
                "formal machine host must use a dynamic execution budget model");
        assertTrue(hostSource.contains("lastEffectiveLaneCount"),
                "formal machine benchmark snapshot must expose effective dynamic lane count");
        assertTrue(screenSource.contains("menu.highlightedPageSlotMask"),
                "formal machine screen must draw all page-local search matches");
        assertTrue(!screenSource.contains("drawSlotBackgrounds(guiGraphics, offsetX, offsetY, SlotSemantics.UPGRADE)"),
                "formal machine screen must not double-draw upgrade slot backgrounds");
        assertTrue(screenJson.contains("\"translate\": \"gui.chexsonsaeutils.high_capacity_crafting_machine\""),
                "formal machine screen title must stay stable");
        assertTrue(blockState.contains("\"chexsonsaeutils:block/high_capacity_crafting_machine\""),
                "formal machine blockstate must point at the block model");
        assertTrue(recipe.contains("\"ae2:molecular_assembler\""),
                "formal machine recipe must require a molecular assembler");
        assertTrue(enUs.has("block.chexsonsaeutils.high_capacity_crafting_machine"),
                "English translations must include the formal machine name");
        assertTrue(zhCn.has("block.chexsonsaeutils.high_capacity_crafting_machine"),
                "Chinese translations must include the formal machine name");
        assertTrue(enUs.has("gui.chexsonsaeutils.high_capacity_crafting_machine.search_hint"),
                "English translations must include the search hint");
        assertTrue(zhCn.has("gui.chexsonsaeutils.high_capacity_crafting_machine.search_hint"),
                "Chinese translations must include the search hint");
    }

    private static JsonObject readLang(Path path) throws IOException {
        return JsonParser.parseString(readUtf8(path)).getAsJsonObject();
    }
}
