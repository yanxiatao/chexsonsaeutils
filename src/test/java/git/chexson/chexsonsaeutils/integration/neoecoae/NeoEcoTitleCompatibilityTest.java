package git.chexson.chexsonsaeutils.integration.neoecoae;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoEcoTitleCompatibilityTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path NEOECOAE_JAR = PROJECT_ROOT.resolve(Path.of(
            "reference-sources", "neoecoae-21.2.0-preview14.jar"));
    private static final String NEOECOAE_SCREEN =
            "cn.dancingsnow.neoecoae.client.ECOCraftConfirmScreen";
    private static final String AE2_SCREEN =
            "appeng.client.gui.me.crafting.CraftConfirmScreen";
    private static final String AE_BASE_SCREEN =
            "appeng.client.gui.AEBaseScreen";

    @Test
    void projectTitleMixinTargetsOnlyAe2CraftConfirmScreen() throws Exception {
        byte[] mixinClass = Files.readAllBytes(PROJECT_ROOT.resolve(Path.of(
                "build", "classes", "java", "main", "git", "chexson", "chexsonsaeutils", "mixin",
                "ae2", "client", "gui", "CraftConfirmScreenProvenanceMixin.class")));

        assertTrue(contains(mixinClass, AE2_SCREEN.replace('.', '/')));
        assertFalse(contains(mixinClass, NEOECOAE_SCREEN.replace('.', '/')));
    }

    @Test
    void preview14KeepsItsIndependentScreenAndCalculationTimePath() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{NEOECOAE_JAR.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> screen = Class.forName(NEOECOAE_SCREEN, false, loader);
            assertTrue(AE_BASE_SCREEN.equals(screen.getSuperclass().getName()));
            assertFalse(Class.forName(AE2_SCREEN, false, loader).isAssignableFrom(screen));
        }

        assertJarClassContains("cn/dancingsnow/neoecoae/client/ECOCraftConfirmScreen.class",
                "neoecoae$getCalculationNanos");
    }

    @Test
    void projectRegistersNoNeoEcoTitleOrCalculationMixin() throws Exception {
        String mixinConfig = Files.readString(PROJECT_ROOT.resolve(Path.of(
                "src", "main", "resources", "chexsonsaeutils.mixins.json")));
        String plugin = Files.readString(PROJECT_ROOT.resolve(Path.of(
                "src", "main", "java", "git", "chexson", "chexsonsaeutils", "mixin", "ae2",
                "ChexsonsaeutilsMixinPlugin.java")));

        assertFalse(mixinConfig.contains("ECOCraftConfirmScreen"));
        assertFalse(mixinConfig.contains("CalculationTime"));
        assertFalse(plugin.contains("ECOCraftConfirmScreen"));
        assertFalse(plugin.contains("CalculationTime"));
        assertTrue(mixinConfig.contains("neoecoae.ExtremeAssemblerNeoEcoBatchProviderMixin"));
    }

    private static void assertJarClassContains(String entryName, String needle) throws IOException {
        try (var jar = new JarFile(NEOECOAE_JAR.toFile())) {
            var entry = jar.getJarEntry(entryName);
            assertNotNull(entry);
            try (var stream = jar.getInputStream(entry)) {
                assertTrue(contains(stream.readAllBytes(), needle));
            }
        }
    }

    private static boolean contains(byte[] classBytes, String needle) {
        byte[] encoded = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int offset = 0; offset <= classBytes.length - encoded.length; offset++) {
            for (int index = 0; index < encoded.length; index++) {
                if (classBytes[offset + index] != encoded[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
