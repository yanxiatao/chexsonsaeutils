package git.chexson.chexsonsaeutils.support;

import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SourceLayoutTestSupport {

    private static final Path JAVA_MAIN = Path.of("src", "main", "java");
    private static final Path RESOURCES_MAIN = Path.of("src", "main", "resources");

    private SourceLayoutTestSupport() {
    }

    public static String readUtf8(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public static Path javaSource(String... relativeCandidates) {
        return assertExactlyOneExists(resolveCandidates(JAVA_MAIN, relativeCandidates));
    }

    public static Path resourcePath(String... relativeCandidates) {
        return assertExactlyOneExists(resolveCandidates(RESOURCES_MAIN, relativeCandidates));
    }

    public static Path assertExactlyOneExists(Path... candidates) {
        List<Path> existing = new ArrayList<>();
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                existing.add(candidate);
            }
        }
        Assertions.assertEquals(
                1,
                existing.size(),
                () -> "Expected exactly one existing path, but found " + existing.size() + ": " + Arrays.toString(candidates)
        );
        return existing.get(0);
    }

    public static void assertContains(Path path, String expectedSnippet) throws IOException {
        Assertions.assertTrue(Files.exists(path), () -> "Expected file to exist: " + path);
        String content = readUtf8(path);
        Assertions.assertTrue(content.contains(expectedSnippet),
                () -> "Expected " + path + " to contain: " + expectedSnippet);
    }

    public static void assertDoesNotContain(Path path, String unexpectedSnippet) throws IOException {
        Assertions.assertTrue(Files.exists(path), () -> "Expected file to exist: " + path);
        String content = readUtf8(path);
        Assertions.assertFalse(content.contains(unexpectedSnippet),
                () -> "Expected " + path + " to not contain: " + unexpectedSnippet);
    }

    private static Path[] resolveCandidates(Path root, String... relativeCandidates) {
        return Arrays.stream(relativeCandidates)
                .map(candidate -> root.resolve(Path.of(candidate)))
                .toArray(Path[]::new);
    }
}
