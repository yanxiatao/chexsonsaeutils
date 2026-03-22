package git.chexson.chexsonsaeutils.parts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        String modSource = readSource("src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java");
        String screenSource = readSource("src/main/java/git/chexson/chexsonsaeutils/client/MultiLevelEmitterRuntimeScreen.java");
        String itemModel = readSource("src/main/resources/assets/chexsonsaeutils/models/item/multi_level_emitter.json");
        String enUs = readSource("src/main/resources/assets/chexsonsaeutils/lang/en_us.json");
        String zhCn = readSource("src/main/resources/assets/chexsonsaeutils/lang/zh_cn.json");
        String gradleProperties = readSource("gradle.properties");
        Path itemTexture = Path.of("src/main/resources/assets/chexsonsaeutils/textures/item/multi_level_emitter.png");

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
        assertTrue(enUs.contains("item.chexsonsaeutils.multi_level_emitter"),
                "English translations must include the emitter item name");
        assertTrue(zhCn.contains("item.chexsonsaeutils.multi_level_emitter"),
                "Chinese translations must include the emitter item name");
        assertTrue(gradleProperties.contains("mod_name=Chexson's ae utils"),
                "gradle properties must expose the requested English mod display name");
        assertTrue(enUs.contains("\"itemGroup.chexsonsaeutils\": \"Chexson's ae utils\""),
                "English creative tab translation must use the requested English mod name");
        assertTrue(enUs.contains("gui.chexsonsaeutils.multi_level_emitter.apply_expression"),
                "English translations must include emitter button labels");
        assertTrue(zhCn.contains("gui.chexsonsaeutils.multi_level_emitter.apply_expression"),
                "Chinese translations must include emitter button labels");
        assertTrue(zhCn.contains("\"itemGroup.chexsonsaeutils\": \"chexson的ae工具\""),
                "Chinese creative tab translation must use the requested mod name");
    }

    @Test
    void menuAndScreenRegistrationHooksStayCallable() {
        MultiLevelEmitterMenu.registerMenuBindings();
        MultiLevelEmitterScreen.registerClientBindings();
        assertTrue(MultiLevelEmitterItem.isRegistryPath(MultiLevelEmitterItem.id()));
    }

    private static String readSource(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }
}
