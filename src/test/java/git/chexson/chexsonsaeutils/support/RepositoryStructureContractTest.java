package git.chexson.chexsonsaeutils.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.projectPath;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.readUtf8;
import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.resourcePath;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryStructureContractTest {

    @Test
    void textFilesStayUtf8AndCrlfConfigured() throws IOException {
        String editorConfig = readUtf8(Path.of(".editorconfig"));
        String gitAttributes = readUtf8(Path.of(".gitattributes"));

        assertTrue(editorConfig.contains("charset = utf-8"), "editorconfig must pin UTF-8");
        assertTrue(editorConfig.contains("end_of_line = crlf"), "editorconfig must pin CRLF");
        assertTrue(gitAttributes.contains("eol=crlf"), "gitattributes must pin CRLF checkout");
    }

    @Test
    void buildTopologyStaysStable() throws IOException {
        String buildGradle = readUtf8(Path.of("build.gradle"));
        String minecraftMcpCompatGradle = readUtf8(Path.of("gradle", "minecraft-mcp-compat.gradle"));
        String mixinsConfig = readUtf8(resourcePath("chexsonsaeutils.mixins.json"));

        assertTrue(buildGradle.contains("id 'net.neoforged.moddev' version '2.0.141'"),
                "build.gradle must keep the NeoForge ModDevGradle baseline");
        assertTrue(buildGradle.contains("sourceSets.main.resources { srcDir 'src/generated/resources' }"),
                "build.gradle must keep the generated resources source-set wiring");
        assertTrue(buildGradle.contains("apply from: 'gradle/minecraft-mcp-compat.gradle'"),
                "build.gradle must delegate MCP compat task wiring to gradle/minecraft-mcp-compat.gradle");
        assertTrue(buildGradle.contains("from 'src/main/templates'"),
                "build.gradle must keep metadata generation sourced from templates");
        assertTrue(buildGradle.contains("filesMatching('META-INF/neoforge.mods.toml')"),
                "build.gradle must keep the NeoForge metadata expansion wiring");
        assertTrue(minecraftMcpCompatGradle.contains("src/minecraftMcpCompat/java"),
                "minecraft-mcp-compat.gradle must stage MCP compat sources from a dedicated template directory");
        assertTrue(minecraftMcpCompatGradle.contains("src/minecraftMcpCompat/resources"),
                "minecraft-mcp-compat.gradle must stage MCP compat resources from a dedicated template directory");
        assertTrue(buildGradle.contains("useJUnitPlatform()"),
                "build.gradle must keep JUnit 5 test execution");
        assertTrue(buildGradle.contains("systemProperty 'chexsonsaeutils.parallelCpuGameTests'"),
                "gameTestServer must expose the optional parallel CPU GameTest switch");
        assertTrue(buildGradle.contains("System.getProperty('chexsonsaeutils.parallelCpuGameTests', 'false')"),
                "parallel CPU GameTests must stay disabled by default and opt in through a system property");
        assertTrue(buildGradle.contains("systemProperty 'chexsonsaeutils.directProcessingGameTests'"),
                "gameTestServer must expose the optional direct-processing GameTest switch");
        assertTrue(buildGradle.contains("systemProperty 'chexsonsaeutils.highCapacityGameTests'"),
                "gameTestServer must expose the opt-out high-capacity GameTest switch for isolated runs");
        assertFalse(buildGradle.contains("chexsonsaeutils.formalMachineMegaCellsGameTest"),
                "Mega Cells GameTests must be controlled by ModList presence, not by an opt-in system property");
        assertFalse(buildGradle.contains("accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')"),
                "build.gradle must not regress to the legacy Forge access transformer baseline");
        assertFalse(buildGradle.contains("entryFile.text = '''"),
                "build.gradle must not inline the ModDev MCP entry source");
        assertFalse(minecraftMcpCompatGradle.contains("entryFile.text = '''"),
                "minecraft-mcp-compat.gradle must not inline the ModDev MCP entry source");
        assertFalse(buildGradle.contains("smokeHandlerFile.text = '''"),
                "build.gradle must not inline the MCP smoke handler source");
        assertFalse(minecraftMcpCompatGradle.contains("smokeHandlerFile.text = '''"),
                "minecraft-mcp-compat.gradle must not inline the MCP smoke handler source");
        assertFalse(minecraftMcpCompatGradle.contains("smokeHandlerFile.text += '''"),
                "minecraft-mcp-compat.gradle must not append MCP smoke handler source inline");
        assertFalse(buildGradle.contains("packFile.text = '''"),
                "build.gradle must not inline MCP resource pack metadata");
        assertFalse(minecraftMcpCompatGradle.contains("packFile.text = '''"),
                "minecraft-mcp-compat.gradle must not inline MCP resource pack metadata");
        assertTrue(mixinsConfig.contains("\"refmap\": \"chexsonsaeutils.refmap.json\""),
                "mixin config must keep the refmap name stable");
        assertTrue(mixinsConfig.contains("\"package\": \"git.chexson.chexsonsaeutils.mixin\""),
                "mixin config must keep the mixin package root stable");
        String packMetadata = readUtf8(resourcePath("pack.mcmeta"));
        assertTrue(packMetadata.contains("\"pack_format\": 34"),
                "main pack metadata must keep the 1.21.1 resource pack format");
        assertTrue(packMetadata.contains("\"max_inclusive\": 48"),
                "main pack metadata must cover the 1.21.1 data pack format for recipes and loot tables");
        assertTrue(Files.exists(projectPath("src", "main", "templates", "META-INF", "neoforge.mods.toml")),
                "NeoForge metadata must come from src/main/templates/META-INF/neoforge.mods.toml");
        assertTrue(Files.exists(projectPath("gradle", "minecraft-mcp-compat.gradle")),
                "MCP compat Gradle wiring must live under gradle/minecraft-mcp-compat.gradle");
        assertTrue(Files.exists(projectPath("src", "minecraftMcpCompat", "java", "xyz", "langyo", "minecraft", "mcp", "mod", "ModDevMcpMod.java")),
                "MCP compat entrypoint source must live under src/minecraftMcpCompat/java");
        assertTrue(Files.exists(projectPath("src", "minecraftMcpCompat", "java", "xyz", "langyo", "minecraft", "mcp", "mod", "ChexsonSmokeInputHandler.java")),
                "MCP smoke handler source must live under src/minecraftMcpCompat/java");
        assertTrue(Files.exists(projectPath("src", "minecraftMcpCompat", "resources", "pack.mcmeta")),
                "MCP compat resource metadata must live under src/minecraftMcpCompat/resources");
        assertFalse(Files.exists(projectPath("src", "main", "resources", "META-INF", "mods.toml")),
                "legacy Forge mods.toml must stay removed");
        assertTrue(Files.exists(resourcePath("assets/ae2/screens/multi_level_emitter.json")),
                "AE2 screen resource path must stay stable");
    }

    @Test
    void mcpBenchmarkProbeKeepsFormalMachineTelemetryGates() throws IOException {
        String smokeHandler = readUtf8(projectPath(
                "src",
                "minecraftMcpCompat",
                "java",
                "xyz",
                "langyo",
                "minecraft",
                "mcp",
                "mod",
                "ChexsonSmokeInputHandler.java"
        ));

        assertTrue(smokeHandler.contains("result.put(\"formalTimingCorrectionCount\", snapshot.formalTimingCorrectionCount())"),
                "MCP benchmark probe must expose formal timing correction telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"formalTimingProgressClampCount\", snapshot.formalTimingProgressClampCount())"),
                "MCP benchmark probe must expose formal timing progress clamp telemetry");
        assertTrue(smokeHandler.contains("result.put(\"formalTimingEtaClampCount\", snapshot.formalTimingEtaClampCount())"),
                "MCP benchmark probe must expose formal timing ETA clamp telemetry");
        assertTrue(smokeHandler.contains("result.put(\"formalStatusHeartbeatCount\", snapshot.formalStatusHeartbeatCount())"),
                "MCP benchmark probe must expose formal status heartbeat telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"cpuWaitingReturnBudgetStopCount\", snapshot.cpuWaitingReturnBudgetStopCount())"),
                "MCP benchmark probe must expose CPU_WAITING budget stop telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"largestCpuWaitingReturnAmount\", snapshot.largestCpuWaitingReturnAmount())"),
                "MCP benchmark probe must expose largest CPU_WAITING payload telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"cpuWaitingReturnOverBudgetCount\", snapshot.cpuWaitingReturnOverBudgetCount())"),
                "MCP benchmark probe must expose CPU_WAITING over-budget telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"cpuWaitingAeFallbackPartialInsertCount\", snapshot.cpuWaitingAeFallbackPartialInsertCount())"),
                "MCP benchmark probe must expose CPU_WAITING AE fallback partial insert telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"cpuWaitingNoProgressRetries\", snapshot.cpuWaitingNoProgressRetries())"),
                "MCP benchmark probe must expose CPU_WAITING no-progress retry telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"cpuWaitingRouteNanosMax\", snapshot.cpuWaitingRouteNanosMax())"),
                "MCP benchmark probe must expose CPU_WAITING route nanos telemetry");
        assertTrue(smokeHandler.contains("maxTickBudgetNanosObserved"),
                "MCP benchmark probe must expose max observed tick budget telemetry");
        assertTrue(smokeHandler.contains("minFormalTimingCorrectionCount"),
                "MCP benchmark probe must allow asserting timing correction hits");
        assertTrue(smokeHandler.contains("minFormalTimingProgressClampCount"),
                "MCP benchmark probe must allow asserting progress clamp hits");
        assertTrue(smokeHandler.contains("minFormalTimingEtaClampCount"),
                "MCP benchmark probe must allow asserting ETA clamp hits");
        assertTrue(smokeHandler.contains("minFormalStatusHeartbeatCount"),
                "MCP benchmark probe must allow asserting formal status heartbeat hits");
        assertTrue(smokeHandler.contains("maxCpuWaitingReturnBudgetStopCount"),
                "MCP benchmark probe must allow bounding CPU_WAITING budget stops");
        assertTrue(smokeHandler.contains("minLargestCpuWaitingReturnAmount"),
                "MCP benchmark probe must allow asserting CPU_WAITING payload scale");
        assertTrue(smokeHandler.contains("maxCpuWaitingReturnOverBudgetCount"),
                "MCP benchmark probe must allow bounding CPU_WAITING over-budget hits");
        assertTrue(smokeHandler.contains("maxCpuWaitingAeFallbackPartialInsertCount"),
                "MCP benchmark probe must allow bounding CPU_WAITING AE fallback partial inserts");
        assertTrue(smokeHandler.contains("maxCpuWaitingNoProgressRetries"),
                "MCP benchmark probe must allow bounding CPU_WAITING no-progress retries");
        assertTrue(smokeHandler.contains("maxCpuWaitingRouteNanosMax"),
                "MCP benchmark probe must allow bounding CPU_WAITING route nanos");
        assertTrue(smokeHandler.contains(
                        "result.put(\"maxExecutableRunsHitCount\", snapshot.maxExecutableRunsHitCount())"),
                "MCP benchmark probe must expose bulk extraction hit telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"maxExecutableRunsFallbackCount\", snapshot.maxExecutableRunsFallbackCount())"),
                "MCP benchmark probe must expose bulk extraction fallback telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"bulkExtractionLogicalExecutionsMax\", snapshot.bulkExtractionLogicalExecutionsMax())"),
                "MCP benchmark probe must expose max bulk extraction size telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"templatedDispatchHitCount\", snapshot.templatedDispatchHitCount())"),
                "MCP benchmark probe must expose dispatch-time template hit telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"compileCacheHitCount\", snapshot.compileCacheHitCount())"),
                "MCP benchmark probe must expose dispatch compile-cache hit telemetry");
        assertTrue(smokeHandler.contains(
                        "result.put(\"providerOverpressureRejectCount\", snapshot.providerOverpressureRejectCount())"),
                "MCP benchmark probe must expose dispatch overpressure rejection telemetry");
        assertTrue(smokeHandler.contains("formalTimingCorrectionCount below minimum"),
                "MCP benchmark probe must fail when timing corrections are below expectation");
        assertTrue(smokeHandler.contains("formalTimingProgressClampCount below minimum"),
                "MCP benchmark probe must fail when progress clamps are below expectation");
        assertTrue(smokeHandler.contains("formalTimingEtaClampCount below minimum"),
                "MCP benchmark probe must fail when ETA clamps are below expectation");
        assertTrue(smokeHandler.contains("formalStatusHeartbeatCount below minimum"),
                "MCP benchmark probe must fail when status heartbeats are below expectation");
        assertTrue(smokeHandler.contains("cpuWaitingReturnBudgetStopCount exceeded maximum"),
                "MCP benchmark probe must fail when CPU_WAITING budget stops exceed expectation");
        assertTrue(smokeHandler.contains("largestCpuWaitingReturnAmount below minimum"),
                "MCP benchmark probe must fail when CPU_WAITING payload scale is below expectation");
        assertTrue(smokeHandler.contains("cpuWaitingReturnOverBudgetCount exceeded maximum"),
                "MCP benchmark probe must fail when CPU_WAITING over-budget hits exceed expectation");
        assertTrue(smokeHandler.contains("cpuWaitingAeFallbackPartialInsertCount exceeded maximum"),
                "MCP benchmark probe must fail when CPU_WAITING partial fallback inserts exceed expectation");
        assertTrue(smokeHandler.contains("cpuWaitingNoProgressRetries exceeded maximum"),
                "MCP benchmark probe must fail when CPU_WAITING no-progress retries exceed expectation");
        assertTrue(smokeHandler.contains("cpuWaitingRouteNanosMax exceeded maximum"),
                "MCP benchmark probe must fail when CPU_WAITING route nanos exceed expectation");
    }

    @Test
    void emitterRootsStayOnCurrentTopology() {
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterPart.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterPart.java")
        );
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterUtils.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterUtils.java")
        );
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/automation/expression/MultiLevelEmitterExpressionCompiler.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/expression/MultiLevelEmitterExpressionCompiler.java")
        );
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterItem.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterItem.java")
        );
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/automation/MultiLevelEmitterRuntimePart.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterRuntimePart.java")
        );
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterMenu.java")
        );
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterScreen.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/parts/MultiLevelEmitterScreen.java")
        );
        assertCurrentPath(
                Path.of("src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/MultiLevelEmitterRuntimeScreen.java"),
                Path.of("src/main/java/git/chexson/chexsonsaeutils/client/MultiLevelEmitterRuntimeScreen.java")
        );

        assertTrue(Files.exists(javaSource("git/chexson/chexsonsaeutils/Chexsonsaeutils.java")),
                "main mod bootstrap class must remain stable");
    }

    private static void assertCurrentPath(Path currentPath, Path legacyPath) {
        Path resolvedCurrent = projectPath(currentPath.toString());
        Path resolvedLegacy = projectPath(legacyPath.toString());
        assertTrue(Files.exists(resolvedCurrent), () -> "Expected current path to exist: " + resolvedCurrent);
        assertFalse(Files.exists(resolvedLegacy), () -> "Legacy path must stay removed: " + resolvedLegacy);
    }
}
