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

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path JAVA_MAIN = PROJECT_ROOT.resolve(Path.of("src", "main", "java"));
    private static final Path RESOURCES_MAIN = PROJECT_ROOT.resolve(Path.of("src", "main", "resources"));

    private SourceLayoutTestSupport() {
    }

    public static String readUtf8(Path path) throws IOException {
        return Files.readString(resolve(path), StandardCharsets.UTF_8);
    }

    public static Path projectPath(String... segments) {
        return PROJECT_ROOT.resolve(Path.of("", segments));
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
        Path resolved = resolve(path);
        Assertions.assertTrue(Files.exists(resolved), () -> "Expected file to exist: " + resolved);
        String content = readUtf8(resolved);
        Assertions.assertTrue(content.contains(expectedSnippet),
                () -> "Expected " + resolved + " to contain: " + expectedSnippet);
    }

    public static void assertDoesNotContain(Path path, String unexpectedSnippet) throws IOException {
        Path resolved = resolve(path);
        Assertions.assertTrue(Files.exists(resolved), () -> "Expected file to exist: " + resolved);
        String content = readUtf8(resolved);
        Assertions.assertFalse(content.contains(unexpectedSnippet),
                () -> "Expected " + resolved + " to not contain: " + unexpectedSnippet);
    }

    private static Path[] resolveCandidates(Path root, String... relativeCandidates) {
        return Arrays.stream(relativeCandidates)
                .map(candidate -> root.resolve(Path.of(candidate)))
                .toArray(Path[]::new);
    }

    private static Path resolve(Path path) {
        if (path.isAbsolute()) {
            return path;
        }
        return PROJECT_ROOT.resolve(path).normalize();
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("build.gradle")) && Files.exists(current.resolve("src"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate project root from current working directory");
    }
}
