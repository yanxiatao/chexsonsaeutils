package git.chexson.chexsonsaeutils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-2 构建指纹（启动期一行）：给定 CodeSource 那个 jar 的路径/字节数/lastModified/内容摘要。
 * 摘要<b>自己读字节做 SHA-256、取前 12 位十六进制</b>——不是编出来的，也不是 git commit
 * （拿 commit 要改 build.gradle，未授权）。
 */
final class BuildFingerprintTest {

    /** "abc" 的 SHA-256 前 12 位是公开常量：证明摘要是真算出来的，不是永真断言。 */
    private static final String ABC_SHA256_PREFIX = "ba7816bf8f01";

    @Test
    void sha256First12MatchesKnownDigestOfAbc(@TempDir Path dir) throws Exception {
        File file = dir.resolve("abc.bin").toFile();
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(ABC_SHA256_PREFIX, Chexsonsaeutils.sha256First12(file),
                "SHA-256(\"abc\") 前 12 位必须是 ba7816bf8f01；对不上就是摘要算错了");
    }

    @Test
    void fingerprintLineReportsVersionJarSizeAndDigest(@TempDir Path dir) throws Exception {
        File file = dir.resolve("chexsonsaeutils.jar").toFile();
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        String line = Chexsonsaeutils.buildFingerprintLine(
                "chexsonsaeutils", "1.21.1-0.3.0", file.toURI().toURL());

        assertTrue(line.contains("mod=chexsonsaeutils"), line);
        assertTrue(line.contains(" version=1.21.1-0.3.0"), line);
        assertTrue(line.contains(" bytes=3"), "字节数要如实报（" + line + "）");
        assertTrue(line.contains(" sha256=" + ABC_SHA256_PREFIX),
                "指纹行必须带上真算出来的内容摘要：" + line);
        // 一格一 token：按空格切出的每一格都是 key=value
        for (var cell : line.split(" ")) {
            assertTrue(cell.contains("="), "指纹行每一格都得是 key=value，实得：" + cell);
        }
    }

    /** 位置读不到（null）时如实报 sha256=NA，绝不编一个摘要冒充证据。 */
    @Test
    void missingCodeSourceReportsNaNotAFabricatedDigest() {
        String line = Chexsonsaeutils.buildFingerprintLine("chexsonsaeutils", "v", null);
        assertTrue(line.endsWith("sha256=NA"), "读不到 jar 摘要必须如实 NA，实得：" + line);
    }

    /**
     * 台账 #225 读数 8：NeoForge <b>产线</b>给的 CodeSource 是 {@code union:} 嵌套位置
     * （实测那一跑 {@code union:/…/chexsonsaeutils-1.21.1-0.3.0.jar%23504!/}，改前整行
     * {@code bytes=-1 sha256=NA}，产线上永远比不出装的是哪一个构建）。
     *
     * <p>夹具自己造一条 union 形状的位置、指向<b>本用例真写出来的那个文件</b>：
     * 背后是真文件 ⇒ 还原那一口<b>只有"成"这一条出路</b>，本条断言因此<b>不再放行 {@code NA}</b>——
     * 印出来的摘要必须与<b>独立算出的那一份</b>逐字相等（期望数出自 {@code MessageDigest}
     * 直接对文件字节算，不抄被测实现），且形状必须是 12 位小写十六进制。
     *
     * <p><b>这一档改前是恒绿臂</b>（台账 #226 笔 3c）：这里写过
     * {@code if ("NA".equals(printed)) { assert jar=…; return; }}，于是"还原成功"与"退化成 {@code NA}"
     * 两档都放行——把还原那一段整段禁掉也打不出红（上一台代理自己报的）。
     * "还原不到 ⇒ 如实 {@code NA}"现在由<b>独立</b>那条臂判
     * （{@link #unionLocationWithoutARealJarStillReportsNa()}，位置指向真拿不到文件的地方）。
     */
    @Test
    void unionNestedLocationRecoversTheRealJarAndItsDigest(@TempDir Path dir) throws Exception {
        var jar = dir.resolve("chexsonsaeutils-1.21.1-0.3.0.jar");
        Files.write(jar, "abc".getBytes(StandardCharsets.UTF_8));
        var location = nestedUrl("union", toUnionPath(jar) + "%23504!/");

        String line = Chexsonsaeutils.buildFingerprintLine(
                "chexsonsaeutils", "1.21.1-0.3.0", location);

        var printed = digestCell(line);
        // 不许 NA、不许编：形状先钉死成 12 位小写十六进制（与 sha256First12 的截位口径同尺）。
        assertTrue(printed.matches("[0-9a-f]{12}"),
                "背后是真文件时 sha256= 必须是 12 位小写十六进制摘要，不许退化成 NA：" + line);
        // 期望摘要<b>独立算</b>：MessageDigest 直接对文件字节算 SHA-256，不抄被测实现。
        // 截位口径与主代码同一把尺（sha256First12：十六进制取小写、截前 12 位）——
        // HexFormat.formatHex 交回的本来就是小写 String，所以这里只要 substring(0, 12)。
        var expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(jar)))
                .substring(0, 12);
        assertEquals(expected, printed,
                "还原到真 jar 就必须印它自己的摘要（独立算的 SHA-256，不是实现里的算法）：" + line);
        assertTrue(line.contains(" bytes=3"), "字节数要跟着还原成功一起拿到：" + line);
        assertTrue(line.contains(" jarUrl=union:"),
                "原始 union 位置要另存一格，自报这一格是怎么来的：" + line);
        assertTrue(line.contains(" jar=" + jar.toFile().getAbsolutePath().replace(' ', '_')),
                "jar= 那一格交的是还原出来的真实路径：" + line);
        // 一格一 token：按空格切出的每一格仍是 key=value
        for (var cell : line.split(" ")) {
            assertTrue(cell.contains("="), "指纹行每一格都得是 key=value，实得：" + cell);
        }
    }

    /**
     * union 位置指向<b>不存在</b>的 jar：还原不到就照旧 {@code NA}，<b>绝不</b>印一个固定摘要冒充指纹。
     *
     * <p>这一条是 {@code union:} 那一档的<b>独立臂</b>（台账 #226 笔 3c）：
     * "还原不到 ⇒ 如实 NA"只在<b>真拿不到文件</b>的位置上判，不再混进上面那条背后有真文件的用例里
     * ——混着写就等于两档都放行，上面那条因此才是恒绿装饰。
     */
    @Test
    void unionLocationWithoutARealJarStillReportsNa() throws Exception {
        var location = nestedUrl("union", "/nonexistent/z:/no-such-dir/chexsonsaeutils.jar%237!/");

        var line = Chexsonsaeutils.buildFingerprintLine("chexsonsaeutils", "v", location);

        assertEquals("NA", digestCell(line), "还原不出文件就只能如实 NA：" + line);
        assertTrue(line.endsWith("sha256=NA"), line);
        // 如实 NA 时 jar= 必须仍是那条原始位置，不许半截还原：
        assertTrue(line.contains(" jar=union:") || line.contains(" jar=" + location.getFile()),
                "如实 NA 时 jar= 必须仍是那条原始位置，不许半截还原：" + line);
    }

    /** 取 {@code sha256=} 那一格的值。 */
    private static String digestCell(String line) {
        for (var cell : line.split(" ")) {
            if (cell.startsWith("sha256=")) {
                return cell.substring("sha256=".length());
            }
        }
        throw new AssertionError("指纹行里没有 sha256= 那一格：" + line);
    }

    /** 把真实文件路径写成 union 位置里那一段（POSIX 直接给绝对路径，Windows 补一个前导斜杠）。 */
    private static String toUnionPath(Path file) {
        var path = file.toAbsolutePath().toString().replace('\\', '/');
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * 造一条 {@code union:}/{@code jar:} 那种未知协议的位置：JDK 没注册这些 handler，
     * 只能自带一个空壳 handler（产线上那是 NeoForge 的 {@code UnionURLStreamHandler}）。
     *
     * <p><b>这个 handler 只为让 {@code URL} 成形</b>——{@code URLStreamHandler#openConnection}
     * 是抽象方法，不覆盖就造不出实例；本用例从不打开它，被测那一口只读
     * {@code getProtocol()}／{@code getFile()} 做路径还原。真被打开会当场抛
     * {@link UnsupportedOperationException}（宁可炸在夹具前提上，也不许静默去连一条假地址）。
     *
     * <p>{@code URL} 的 5 参合法构造是 {@code (protocol, host, port, file, handler)}：
     * {@code host} 这一参不许给 {@code null}（JDK 拿它拼 {@code toString()}），给一个占位主机名。
     * 还原那一口只吃 {@code getFile()} 的那一段，host 进不了路径口径。
     */
    private static java.net.URL nestedUrl(String protocol, String file) throws Exception {
        var handler = new java.net.URLStreamHandler() {
            @Override
            protected java.net.URLConnection openConnection(java.net.URL url)
                    throws java.io.IOException {
                throw new UnsupportedOperationException(
                        "夹具里的 union: 位置只为让 URL 成形，不该被打开：" + url);
            }
        };
        return new java.net.URL(protocol, "localhost", -1, file, handler);
    }

    /** 打印者真会打印它：静态出口把同一行喂给给定 logger（与启动期是同一条打点口）。 */
    @Test
    void printerEmitsTheFingerprintLine(@TempDir Path dir) throws Exception {
        File file = dir.resolve("mod.jar").toFile();
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        var appender = new CapturingAppender();
        var context = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
        var configuration = context.getConfiguration();
        try {
            appender.start();
            configuration.addAppender(appender);
            configuration.getRootLogger()
                    .addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
            context.updateLoggers();
            Chexsonsaeutils.logBuildFingerprint(
                    org.slf4j.LoggerFactory.getLogger(Chexsonsaeutils.class),
                    "chexsonsaeutils", "1.21.1-0.3.0", file.toURI().toURL());
        } finally {
            configuration.getRootLogger().removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        }

        var line = appender.lastLine("chexsonsaeutils build fingerprint:");
        assertTrue(line.contains("sha256=" + ABC_SHA256_PREFIX), line);
        assertTrue(line.contains(" version=1.21.1-0.3.0"), line);
    }

    /** 只收 INFO 及以上的原文行（构建指纹那行走 INFO；名单那行是 DEBUG，见 PlanRosterReadoutTest）。 */
    private static final class CapturingAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {
        private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("build-fingerprint-capture", null,
                    org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            var message = event.getMessage();
            if (message != null) {
                this.lines.add(message.getFormattedMessage());
            }
        }

        String lastLine(String prefix) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).startsWith(prefix)) {
                    return lines.get(i);
                }
            }
            throw new AssertionError("没抓到前缀为 " + prefix + " 的行；所有行=" + lines);
        }
    }
}
