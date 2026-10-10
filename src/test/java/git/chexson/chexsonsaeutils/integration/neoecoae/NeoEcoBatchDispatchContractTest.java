package git.chexson.chexsonsaeutils.integration.neoecoae;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoEcoBatchDispatchContractTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path NEOECOAE_JAR = PROJECT_ROOT.resolve(Path.of(
            "reference-sources", "neoecoae-21.2.0-preview14.jar"));

    @Test
    void preview14BatchExecutorUsesOneCountedDispatchInsteadOfNormalPushLoop() throws Exception {
        byte[] executor = jarClassBytes(
                "cn/dancingsnow/neoecoae/impl/crafting/fastpath/ECOBatchCraftingExecutor.class");
        byte[] prepared = jarClassBytes(
                "cn/dancingsnow/neoecoae/impl/crafting/fastpath/ECOBatchCraftingExecutor$PreparedBatch.class");
        byte[] provider = jarClassBytes(
                "cn/dancingsnow/neoecoae/api/me/ECOBatchCapacityProvider.class");

        assertEquals(1, occurrences(executor, "eco$pushBatch"));
        assertTrue(contains(executor, "eco$getBatchCapacity"));
        assertTrue(contains(prepared, "craftCount"));
        assertTrue(contains(prepared, "dispatch"));
        assertFalse(contains(prepared, "pushPattern"));
        assertTrue(contains(provider, "eco$pushBatch"));
    }

    @Test
    void projectBridgeCallsPreview14PreparePushAndCraftCount() throws Exception {
        byte[] mixin = projectClassBytes(
                "git/chexson/chexsonsaeutils/mixin/neoecoae/ExtremeAssemblerNeoEcoBatchProviderMixin.class");
        byte[] preparedAdapter = projectClassBytes(
                "git/chexson/chexsonsaeutils/mixin/neoecoae/ExtremeAssemblerNeoEcoBatchProviderMixin$1.class");
        byte[] cpu = projectClassBytes(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuLogic.class");

        assertTrue(contains(mixin, "ECOBatchCraftingExecutor"));
        assertTrue(contains(mixin, "prepare"));
        assertTrue(contains(preparedAdapter, "push"));
        assertTrue(contains(preparedAdapter, "craftCount"));
        assertTrue(contains(cpu, "NeoEcoBatchDispatcher"));
        assertTrue(contains(cpu, "chexsonsaeutils$prepareNeoEcoBatch"));
        assertTrue(contains(cpu, "craftCount"));
        assertFalse(contains(cpu, "ParallelBatchExecutionLimiter"));
    }

    @Test
    void optionalIntegrationTypesDoNotLinkNeoEcoAeClasses() throws Exception {
        byte[] dispatcher = projectClassBytes(
                "git/chexson/chexsonsaeutils/integration/neoecoae/NeoEcoBatchDispatcher.class");
        byte[] prepared = projectClassBytes(
                "git/chexson/chexsonsaeutils/integration/neoecoae/NeoEcoPreparedBatch.class");
        byte[] host = projectClassBytes(
                "git/chexson/chexsonsaeutils/integration/neoecoae/NeoEcoBatchHost.class");
        byte[] cpu = projectClassBytes(
                "git/chexson/chexsonsaeutils/crafting/parallelcpu/ParallelCraftingCpuLogic.class");

        assertFalse(contains(dispatcher, "cn/dancingsnow/neoecoae"));
        assertFalse(contains(prepared, "cn/dancingsnow/neoecoae"));
        assertFalse(contains(host, "cn/dancingsnow/neoecoae"));
        assertFalse(contains(cpu, "cn/dancingsnow/neoecoae"));
    }

    private static byte[] jarClassBytes(String entryName) throws IOException {
        try (var jar = new JarFile(NEOECOAE_JAR.toFile())) {
            var entry = jar.getJarEntry(entryName);
            assertNotNull(entry);
            try (var stream = jar.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    private static byte[] projectClassBytes(String className) throws IOException {
        return Files.readAllBytes(PROJECT_ROOT.resolve(Path.of(
                "build", "classes", "java", "main", className)));
    }

    private static boolean contains(byte[] classBytes, String needle) {
        return occurrences(classBytes, needle) > 0;
    }

    private static int occurrences(byte[] classBytes, String needle) {
        byte[] encoded = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int count = 0;
        for (int offset = 0; offset <= classBytes.length - encoded.length; offset++) {
            int index = 0;
            while (index < encoded.length && classBytes[offset + index] == encoded[index]) {
                index++;
            }
            if (index == encoded.length) {
                count++;
            }
        }
        return count;
    }
}
