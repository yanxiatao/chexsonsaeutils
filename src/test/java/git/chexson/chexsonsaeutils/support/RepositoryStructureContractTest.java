package git.chexson.chexsonsaeutils.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static git.chexson.chexsonsaeutils.support.SourceLayoutTestSupport.javaSource;
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
        String mixinsConfig = readUtf8(resourcePath("chexsonsaeutils.mixins.json"));

        assertTrue(buildGradle.contains("accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')"),
                "build.gradle must keep the access transformer path stable");
        assertTrue(buildGradle.contains("sourceSets.main.resources { srcDir 'src/generated/resources' }"),
                "build.gradle must keep the generated resources source-set wiring");
        assertTrue(buildGradle.contains("add sourceSets.main, \"${mod_id}.refmap.json\""),
                "build.gradle must keep main source-set refmap wiring");
        assertTrue(buildGradle.contains("config \"${mod_id}.mixins.json\""),
                "build.gradle must keep the mixin config wiring");
        assertTrue(buildGradle.contains("useJUnitPlatform()"),
                "build.gradle must keep JUnit 5 test execution");
        assertTrue(mixinsConfig.contains("\"refmap\": \"chexsonsaeutils.refmap.json\""),
                "mixin config must keep the refmap name stable");
        assertTrue(mixinsConfig.contains("\"package\": \"git.chexson.chexsonsaeutils.mixin\""),
                "mixin config must keep the mixin package root stable");
        assertTrue(Files.exists(resourcePath("assets/ae2/screens/multi_level_emitter.json")),
                "AE2 screen resource path must stay stable");
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
        assertTrue(Files.exists(currentPath), () -> "Expected current path to exist: " + currentPath);
        assertFalse(Files.exists(legacyPath), () -> "Legacy path must stay removed: " + legacyPath);
    }
}
