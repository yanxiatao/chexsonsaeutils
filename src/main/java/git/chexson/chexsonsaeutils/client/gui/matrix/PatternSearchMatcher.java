package git.chexson.chexsonsaeutils.client.gui.matrix;

/** 客户端名称匹配契约；实现可使用普通文本或 JECH 拼音上下文。 */
@FunctionalInterface
public interface PatternSearchMatcher {
    /** 判断候选名称是否包含查询词；调用方负责统一大小写和分词。 */
    boolean contains(String candidate, String query);
}
