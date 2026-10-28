package git.chexson.chexsonsaeutils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 构建指纹的真实文件摘要与 NeoForge 嵌套位置场景，由注册类别统一验证。 */
public final class RegistrationScenarios {
    private static final String ABC_SHA256_PREFIX = "ba7816bf8f01";

    private RegistrationScenarios() {}

    /** 使用独立临时文件验证摘要计算和嵌套位置还原。 */
    public static void verify(Path temporaryRoot) {
        assertAll("构建文件识别",
                () -> verifySha256(Files.createTempDirectory(temporaryRoot, "fingerprint-digest-")),
                () -> verifyNestedLocation(Files.createTempDirectory(temporaryRoot, "fingerprint-location-")));
    }

    private static void verifySha256(Path directory) throws Exception {
        Path file = directory.resolve("abc.bin");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertEquals(ABC_SHA256_PREFIX, Chexsonsaeutils.sha256First12(file.toFile()));
    }

    private static void verifyNestedLocation(Path directory) throws Exception {
        Path file = directory.resolve("chexsonsaeutils.jar");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        String location = file.toAbsolutePath().toString().replace('\\', '/');
        if (!location.startsWith("/")) {
            location = "/" + location;
        }
        var handler = new java.net.URLStreamHandler() {
            @Override
            protected java.net.URLConnection openConnection(java.net.URL url) {
                throw new UnsupportedOperationException("测试只解析嵌套位置，不打开连接：" + url);
            }
        };
        var nested = new java.net.URL("union", "localhost", -1, location + "%23504!/", handler);
        String line = Chexsonsaeutils.buildFingerprintLine("chexsonsaeutils", "test", nested);
        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
                .substring(0, 12);
        assertAll("NeoForge 嵌套位置还原",
                () -> assertTrue(line.endsWith("sha256=" + expected), line),
                () -> assertTrue(line.contains(" bytes=3"), line),
                () -> assertTrue(line.contains(" jar=" + file.toAbsolutePath().toString().replace(' ', '_')), line));
    }
}
