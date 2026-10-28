package git.chexson.chexsonsaeutils.client.gui;

import appeng.client.gui.style.ScreenStyle;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册与配置的行为场景；由类别测试统一调用。 */
public final class RegistrationScenarios {
    private RegistrationScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.client.gui",
                () -> ScreenStyleDocParseCases.verify(temporaryRoot)
        );
    }

    /** ScreenStyleDocParseCases 的独立场景夹具，按类别统一执行。 */
    public static final class ScreenStyleDocParseCases {
        private static final String STYLE_DOC_DIR = "/assets/ae2/screens";

        void everyScreenStyleDocParsesWithAe2StyleGson() throws Exception {
            URL dir = ScreenStyleDocParseCases.class.getResource(STYLE_DOC_DIR);
            assertNotNull(dir, "样式文档目录不在测试类路径上");
            List<Path> docs;
            try (var paths = Files.walk(Paths.get(dir.toURI()))) {
                docs = paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            }
            assertTrue(docs.size() >= 2, "样式文档数量异常：" + docs.size());
            for (Path doc : docs) {
                try (var in = Files.newInputStream(doc)) {
                    ScreenStyle style = ScreenStyle.GSON.fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8),
                            ScreenStyle.class
                    );
                    assertNotNull(style, "样式文档解析失败：" + doc);
                }
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ScreenStyleDocParseCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ScreenStyleDocParseCases.everyScreenStyleDocParsesWithAe2StyleGson", () -> new ScreenStyleDocParseCases().everyScreenStyleDocParsesWithAe2StyleGson())
            );
        }
    }
}
