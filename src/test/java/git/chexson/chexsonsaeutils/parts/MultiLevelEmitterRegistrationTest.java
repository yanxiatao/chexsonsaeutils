package git.chexson.chexsonsaeutils.parts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLevelEmitterRegistrationTest {

    @Test
    void emitterRegistryIdAndBindingKeysAreStable() {
        assertEquals("multi_level_emitter", MultiLevelEmitterItem.id());
        assertEquals("multi_level_emitter_menu", MultiLevelEmitterMenu.registrationKey());
        assertEquals("multi_level_emitter_screen", MultiLevelEmitterScreen.registrationKey());
    }

    @Test
    void modBootstrapContainsRegistrationAnchors() throws IOException {
        String modSource = readUtf8(javaSource("git/chexson/chexsonsaeutils/Chexsonsaeutils.java"));
        String screenSource = readUtf8(javaSource(
                "git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java"
        ));
        String itemModel = readUtf8(resourcePath("assets/chexsonsaeutils/models/item/multi_level_emitter.json"));
        JsonObject enUs = readLang(resourcePath("assets/chexsonsaeutils/lang/en_us.json"));
        JsonObject zhCn = readLang(resourcePath("assets/chexsonsaeutils/lang/zh_cn.json"));
        String gradleProperties = readUtf8(Path.of("gradle.properties"));
        Path itemTexture = resourcePath("assets/chexsonsaeutils/textures/item/multi_level_emitter.png");

        assertTrue(modSource.contains("MULTI_LEVEL_EMITTER_ITEM"), "missing emitter RegistryObject");
        assertTrue(modSource.contains("ITEMS.register(MultiLevelEmitterItem.id()"), "missing item registration call");
        assertTrue(modSource.contains("event.enqueueWork(Chexsonsaeutils::registerMultiLevelEmitterBootstrap)"),
                "missing common setup bootstrap enqueue");
        assertTrue(modSource.contains("Chexsonsaeutils.registerMultiLevelEmitterClientBindings()"),
                "missing client setup binding call");
        assertTrue(modSource.contains("MenuScreens.register(MULTI_LEVEL_EMITTER_MENU.get(), MultiLevelEmitterRuntimeScreen::new)"),
                "missing client screen registration for the custom MultiLevelEmitter menu");
        assertFalse(modSource.contains("modEventBus.addListener(ClientModEvents::onClientSetup)"),
                "client setup must not be registered twice");
        assertTrue(screenSource.contains("extends AEBaseScreen<MultiLevelEmitterMenu.RuntimeMenu>"),
                "runtime screen must create a real client screen for the custom menu");
        assertTrue(itemModel.contains("\"parent\": \"minecraft:item/generated\""),
                "item model must provide a generated inventory model");
        assertTrue(itemModel.contains("chexsonsaeutils:item/multi_level_emitter"),
                "item model must reference the mod texture");
        assertTrue(Files.exists(itemTexture) && Files.size(itemTexture) > 0,
                "item texture must exist");
        assertTrue(enUs.has("item.chexsonsaeutils.multi_level_emitter"),
                "English translations must include the emitter item name");
        assertTrue(zhCn.has("item.chexsonsaeutils.multi_level_emitter"),
                "Chinese translations must include the emitter item name");
        assertTrue(gradleProperties.contains("mod_name=Chexson's ae utils"),
                "gradle properties must expose the requested English mod display name");
        assertEquals("Chexson's ae utils", enUs.get("itemGroup.chexsonsaeutils").getAsString(),
                "English creative tab translation must use the requested English mod name");
        assertTrue(enUs.has("gui.chexsonsaeutils.multi_level_emitter.apply_expression"),
                "English translations must include emitter button labels");
        assertTrue(zhCn.has("gui.chexsonsaeutils.multi_level_emitter.apply_expression"),
                "Chinese translations must include emitter button labels");
        assertEquals("chexson \u7684 ae \u5de5\u5177", zhCn.get("itemGroup.chexsonsaeutils").getAsString(),
                "Chinese creative tab translation must use the requested mod name");
    }

    @Test
    void menuAndScreenRegistrationHooksStayCallable() {
        MultiLevelEmitterMenu.registerMenuBindings();
        MultiLevelEmitterScreen.registerClientBindings();
        assertTrue(MultiLevelEmitterItem.isRegistryPath(MultiLevelEmitterItem.id()));
    }

    private static JsonObject readLang(Path path) throws IOException {
        return JsonParser.parseString(readUtf8(path)).getAsJsonObject();
    }
}
