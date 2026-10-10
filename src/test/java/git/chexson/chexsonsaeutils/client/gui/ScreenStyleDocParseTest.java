package git.chexson.chexsonsaeutils.client.gui;

import appeng.client.gui.style.ScreenStyle;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定每个界面样式文档都能被 AE2 的样式 GSON 解析。
 *
 * <p>样式文档在开屏那一刻才读，文案组件写错一个键名（例如把字面量写成 {@code literal}）
 * 就是整屏打不开，编译与其余单测都拦不住，只能在这里钉住。</p>
 */
class ScreenStyleDocParseTest {

    private static final String STYLE_DOC_DIR = "/assets/ae2/screens";

    @Test
    void everyScreenStyleDocParsesWithAe2StyleGson() throws Exception {
        URL dir = ScreenStyleDocParseTest.class.getResource(STYLE_DOC_DIR);
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
}
