package git.chexson.chexsonsaeutils.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContinuationConfigTestSupport {

    private static final String CONFIG_DIR_PROPERTY = "chexsonsaeutils.configDir";
    private static final String COMMON_CONFIG_FILE = "chexsonsaeutils-common.toml";

    private ContinuationConfigTestSupport() {
    }

    public static String commonConfigFileName() {
        return COMMON_CONFIG_FILE;
    }

    public static Path writeCommonConfig(String prefix, String configLine) throws IOException {
        Path configDir = Files.createTempDirectory(prefix);
        Path configFile = configDir.resolve(COMMON_CONFIG_FILE);
        Files.writeString(configFile, configLine + System.lineSeparator());
        return configFile;
    }

    public static Path writeCommonConfigDir(String prefix, boolean enabled) throws IOException {
        return writeCommonConfig(prefix, "craftingContinuationEnabled = " + enabled).getParent();
    }

    public static void withStartupConfigDir(Path configDir, ThrowingRunnable action) throws Exception {
        String previousValue = System.getProperty(CONFIG_DIR_PROPERTY);
        try {
            System.setProperty(CONFIG_DIR_PROPERTY, configDir.toString());
            action.run();
        } finally {
            if (previousValue == null) {
                System.clearProperty(CONFIG_DIR_PROPERTY);
            } else {
                System.setProperty(CONFIG_DIR_PROPERTY, previousValue);
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
