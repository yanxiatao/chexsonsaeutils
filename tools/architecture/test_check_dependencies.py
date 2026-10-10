# -*- coding: utf-8 -*-
"""直接验证词法解析、依赖图与防回潮规则，不依赖产品源码布局。"""

import unittest

from check_dependencies import (
    build_graph, cyclic_components, decode_unicode_escapes, direct_pairs,
    mask_java, new_cyclic_relations, parse_source, representative_cycle,
    make_baseline, new_root_dependencies,
)


class JavaDependencyTests(unittest.TestCase):
    def test_three_package_cycle_without_direct_pair(self):
        graph = {"a": {"b"}, "b": {"c"}, "c": {"a"}, "leaf": set()}
        self.assertEqual([["a", "b", "c"]], cyclic_components(graph))
        self.assertEqual([], direct_pairs(graph))
        self.assertEqual(["a", "b", "c", "a"], representative_cycle(graph, ["a", "b", "c"]))

    def test_fqn_nested_type_and_static_import(self):
        sources = [
            parse_source("package app.a; import static app.b.B.VALUE; class A { app.b.B.Nested n; }"),
            parse_source("package app.b; class B { static class Nested {} }"),
        ]
        graph = build_graph(sources)
        self.assertEqual({"app.b"}, graph.adjacency()["app.a"])
        self.assertEqual({"import", "fqn"}, {item["kind"] for item in graph.edges["app.a", "app.b"]})

    def test_fqn_without_import_builds_dependency(self):
        graph = build_graph([
            parse_source("package app.a; class A { app.b.B target; }"),
            parse_source("package app.b; class B {}"),
        ])
        self.assertEqual({"app.b"}, graph.adjacency()["app.a"])
        self.assertEqual(set(), graph.adjacency(imports_only=True)["app.a"])

    def test_mixin_is_explicitly_excluded_or_included(self):
        sources = [
            parse_source("package app.a; import app.mixin.Hook; class A {}"),
            parse_source("package app.mixin; import app.a.A; class Hook {}"),
        ]
        excluded = build_graph(sources)
        self.assertEqual({"app.a"}, excluded.nodes)
        self.assertEqual({}, excluded.edges)
        self.assertEqual(1, excluded.excluded_mixin_files)
        self.assertEqual(2, excluded.excluded_mixin_edges)
        self.assertEqual([["app.a", "app.mixin"]], cyclic_components(build_graph(sources, True).adjacency()))

    def test_comments_literals_and_escaped_quotes_do_not_build_edges(self):
        source = r'''package app.a;
        // import app.b.B;
        /** app.b.B and import app.b.B; */
        class A {
            String escaped = "a\" app.b.B \"b";
            char quote = '\'';
            String block = """
                app.b.B
                \""" app.b.B
                """;
        }'''
        graph = build_graph([parse_source(source), parse_source("package app.b; class B {}")])
        self.assertEqual({}, graph.edges)

    def test_comments_inside_dotted_reference_preserve_the_edge(self):
        graph = build_graph([
            parse_source("package app.a; class A { app /* comment */ . b . B target; }"),
            parse_source("package app.b; class B {}"),
        ])
        self.assertEqual({"app.b"}, graph.adjacency()["app.a"])

    def test_unicode_escapes_are_translated_before_comments(self):
        source = r"package app.a; \u002f\u002f import app.b.B;" + "\nclass A {}"
        self.assertEqual((), parse_source(source).imports)
        self.assertEqual(r"\\u002f", decode_unicode_escapes(r"\\u002f"))
        self.assertEqual("\\/", decode_unicode_escapes(r"\u005c\u002f"))

    def test_top_level_types_exclude_nested_and_class_literals(self):
        source = parse_source("package app.a; @Marker(value = Other.class) class A { class Inner {} } "
                              "interface Contract {} record Data(int value) {} enum Choice { ONE } "
                              "@interface Marker {}")
        self.assertEqual(("A", "Contract", "Data", "Choice", "Marker"), source.types)

    def test_wildcard_and_nested_imports(self):
        sources = [
            parse_source("package app.a; import app.b.*; import app.c.C.Nested; class A {}"),
            parse_source("package app.b; class B {}"),
            parse_source("package app.c; class C { interface Nested {} }"),
        ]
        self.assertEqual({"app.b", "app.c"}, build_graph(sources).adjacency()["app.a"])

    def test_unknown_external_types_do_not_create_internal_edges(self):
        graph = build_graph([parse_source("package app.a; import java.util.List; class A { external.B b; }")])
        self.assertEqual({}, graph.edges)

    def test_unterminated_literal_and_duplicate_type_fail_fast(self):
        with self.assertRaises(ValueError):
            mask_java('class A { String bad = "unfinished; }')
        with self.assertRaises(ValueError):
            build_graph([parse_source("package app.a; class A {}"), parse_source("package app.a; class A {}")])

    def test_unresolved_internal_import_fails_fast(self):
        with self.assertRaises(ValueError):
            build_graph([parse_source("package app.a; import app.b.Missing; class A {}")],
                        package_prefix="app")

    def test_new_cycle_is_rejected(self):
        graph = {"a": {"b"}, "b": {"a", "c"}, "c": {"a"}}
        self.assertEqual([("a", "c"), ("b", "c")], new_cyclic_relations(graph, [["a", "b"]]))

    def test_cycle_shrink_and_split_are_allowed(self):
        graph = {"a": {"b"}, "b": {"a", "c"}, "c": {"d"}, "d": {"c"}}
        self.assertEqual([], new_cyclic_relations(graph, [["a", "b", "c", "d"]]))
        self.assertEqual([], new_cyclic_relations({"a": {"b"}, "b": set()}, [["a", "b"]]))

    def test_merging_existing_cycles_is_rejected(self):
        graph = {"a": {"b"}, "b": {"a", "c"}, "c": {"d"}, "d": {"c", "a"}}
        self.assertEqual([("a", "c"), ("a", "d"), ("b", "c"), ("b", "d")],
                         new_cyclic_relations(graph, [["a", "b"], ["c", "d"]]))

    def test_root_dependency_inside_existing_scc_is_rejected(self):
        graph = {"app": {"app.a"}, "app.a": {"app.b"}, "app.b": {"app", "app.a"}}
        allowed_cycles = [["app", "app.a", "app.b"]]
        self.assertEqual([], new_cyclic_relations(graph, allowed_cycles))
        self.assertEqual([("app.b", "app")], new_root_dependencies(graph, "app", []))

    def test_existing_root_dependency_can_shrink(self):
        allowed = [["app.a", "app"], ["app.b", "app"]]
        graph = {"app": set(), "app.a": {"app"}, "app.b": set()}
        self.assertEqual([], new_root_dependencies(graph, "app", allowed))
        graph["app.c"] = {"app"}
        self.assertEqual([("app.c", "app")], new_root_dependencies(graph, "app", allowed))

    def test_baseline_records_origin_and_actual_root_edges(self):
        graph = build_graph([
            parse_source("package app; class Entry {}"),
            parse_source("package app.a; class A { app.Entry entry; }"),
        ])
        origin = {"kind": "git-ref", "commit": "reviewed-commit"}
        baseline = make_baseline(graph, False, "app", origin)
        self.assertEqual(origin, baseline["source"])
        self.assertEqual([["app.a", "app"]], baseline["allowed_root_dependencies"])


if __name__ == "__main__":
    unittest.main()
