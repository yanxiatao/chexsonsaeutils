# -*- coding: utf-8 -*-
"""直接验证词法解析、依赖图与防回潮规则，不依赖产品源码布局。"""

import unittest

from check_dependencies import (
    build_graph, cyclic_components, decode_unicode_escapes, direct_pairs,
    mask_java, new_cyclic_relations, parse_source, representative_cycle,
    make_baseline, new_root_dependencies,
)


class JavaDependencyTests(unittest.TestCase):
    def test_dependency_analysis(self):
        """统一验证解析、依赖图及循环约束，失败仍显示具体场景。"""
        with self.subTest(scenario="three_package_cycle_without_direct_pair"):
            self.check_three_package_cycle_without_direct_pair()
        with self.subTest(scenario="fqn_nested_type_and_static_import"):
            self.check_fqn_nested_type_and_static_import()
        with self.subTest(scenario="fqn_without_import_builds_dependency"):
            self.check_fqn_without_import_builds_dependency()
        with self.subTest(scenario="mixin_is_explicitly_excluded_or_included"):
            self.check_mixin_is_explicitly_excluded_or_included()
        with self.subTest(scenario="comments_literals_and_escaped_quotes_do_not_build_edges"):
            self.check_comments_literals_and_escaped_quotes_do_not_build_edges()
        with self.subTest(scenario="comments_inside_dotted_reference_preserve_the_edge"):
            self.check_comments_inside_dotted_reference_preserve_the_edge()
        with self.subTest(scenario="unicode_escapes_are_translated_before_comments"):
            self.check_unicode_escapes_are_translated_before_comments()
        with self.subTest(scenario="top_level_types_exclude_nested_and_class_literals"):
            self.check_top_level_types_exclude_nested_and_class_literals()
        with self.subTest(scenario="wildcard_and_nested_imports"):
            self.check_wildcard_and_nested_imports()
        with self.subTest(scenario="unknown_external_types_do_not_create_internal_edges"):
            self.check_unknown_external_types_do_not_create_internal_edges()
        with self.subTest(scenario="unterminated_literal_and_duplicate_type_fail_fast"):
            self.check_unterminated_literal_and_duplicate_type_fail_fast()
        with self.subTest(scenario="unresolved_internal_import_fails_fast"):
            self.check_unresolved_internal_import_fails_fast()
        with self.subTest(scenario="new_cycle_is_rejected"):
            self.check_new_cycle_is_rejected()
        with self.subTest(scenario="cycle_shrink_and_split_are_allowed"):
            self.check_cycle_shrink_and_split_are_allowed()
        with self.subTest(scenario="merging_existing_cycles_is_rejected"):
            self.check_merging_existing_cycles_is_rejected()
        with self.subTest(scenario="root_dependency_inside_existing_scc_is_rejected"):
            self.check_root_dependency_inside_existing_scc_is_rejected()
        with self.subTest(scenario="existing_root_dependency_can_shrink"):
            self.check_existing_root_dependency_can_shrink()
        with self.subTest(scenario="baseline_records_origin_and_actual_root_edges"):
            self.check_baseline_records_origin_and_actual_root_edges()

    def check_three_package_cycle_without_direct_pair(self):
        graph = {"a": {"b"}, "b": {"c"}, "c": {"a"}, "leaf": set()}
        self.assertEqual([["a", "b", "c"]], cyclic_components(graph))
        self.assertEqual([], direct_pairs(graph))
        self.assertEqual(["a", "b", "c", "a"], representative_cycle(graph, ["a", "b", "c"]))

    def check_fqn_nested_type_and_static_import(self):
        sources = [
            parse_source("package app.a; import static app.b.B.VALUE; class A { app.b.B.Nested n; }"),
            parse_source("package app.b; class B { static class Nested {} }"),
        ]
        graph = build_graph(sources)
        self.assertEqual({"app.b"}, graph.adjacency()["app.a"])
        self.assertEqual({"import", "fqn"}, {item["kind"] for item in graph.edges["app.a", "app.b"]})

    def check_fqn_without_import_builds_dependency(self):
        graph = build_graph([
            parse_source("package app.a; class A { app.b.B target; }"),
            parse_source("package app.b; class B {}"),
        ])
        self.assertEqual({"app.b"}, graph.adjacency()["app.a"])
        self.assertEqual(set(), graph.adjacency(imports_only=True)["app.a"])

    def check_mixin_is_explicitly_excluded_or_included(self):
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

    def check_comments_literals_and_escaped_quotes_do_not_build_edges(self):
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

    def check_comments_inside_dotted_reference_preserve_the_edge(self):
        graph = build_graph([
            parse_source("package app.a; class A { app /* comment */ . b . B target; }"),
            parse_source("package app.b; class B {}"),
        ])
        self.assertEqual({"app.b"}, graph.adjacency()["app.a"])

    def check_unicode_escapes_are_translated_before_comments(self):
        source = r"package app.a; \u002f\u002f import app.b.B;" + "\nclass A {}"
        self.assertEqual((), parse_source(source).imports)
        self.assertEqual(r"\\u002f", decode_unicode_escapes(r"\\u002f"))
        self.assertEqual("\\/", decode_unicode_escapes(r"\u005c\u002f"))

    def check_top_level_types_exclude_nested_and_class_literals(self):
        source = parse_source("package app.a; @Marker(value = Other.class) class A { class Inner {} } "
                              "interface Contract {} record Data(int value) {} enum Choice { ONE } "
                              "@interface Marker {}")
        self.assertEqual(("A", "Contract", "Data", "Choice", "Marker"), source.types)

    def check_wildcard_and_nested_imports(self):
        sources = [
            parse_source("package app.a; import app.b.*; import app.c.C.Nested; class A {}"),
            parse_source("package app.b; class B {}"),
            parse_source("package app.c; class C { interface Nested {} }"),
        ]
        self.assertEqual({"app.b", "app.c"}, build_graph(sources).adjacency()["app.a"])

    def check_unknown_external_types_do_not_create_internal_edges(self):
        graph = build_graph([parse_source("package app.a; import java.util.List; class A { external.B b; }")])
        self.assertEqual({}, graph.edges)

    def check_unterminated_literal_and_duplicate_type_fail_fast(self):
        with self.assertRaises(ValueError):
            mask_java('class A { String bad = "unfinished; }')
        with self.assertRaises(ValueError):
            build_graph([parse_source("package app.a; class A {}"), parse_source("package app.a; class A {}")])

    def check_unresolved_internal_import_fails_fast(self):
        with self.assertRaises(ValueError):
            build_graph([parse_source("package app.a; import app.b.Missing; class A {}")],
                        package_prefix="app")

    def check_new_cycle_is_rejected(self):
        graph = {"a": {"b"}, "b": {"a", "c"}, "c": {"a"}}
        self.assertEqual([("a", "c"), ("b", "c")], new_cyclic_relations(graph, [["a", "b"]]))

    def check_cycle_shrink_and_split_are_allowed(self):
        graph = {"a": {"b"}, "b": {"a", "c"}, "c": {"d"}, "d": {"c"}}
        self.assertEqual([], new_cyclic_relations(graph, [["a", "b", "c", "d"]]))
        self.assertEqual([], new_cyclic_relations({"a": {"b"}, "b": set()}, [["a", "b"]]))

    def check_merging_existing_cycles_is_rejected(self):
        graph = {"a": {"b"}, "b": {"a", "c"}, "c": {"d"}, "d": {"c", "a"}}
        self.assertEqual([("a", "c"), ("a", "d"), ("b", "c"), ("b", "d")],
                         new_cyclic_relations(graph, [["a", "b"], ["c", "d"]]))

    def check_root_dependency_inside_existing_scc_is_rejected(self):
        graph = {"app": {"app.a"}, "app.a": {"app.b"}, "app.b": {"app", "app.a"}}
        allowed_cycles = [["app", "app.a", "app.b"]]
        self.assertEqual([], new_cyclic_relations(graph, allowed_cycles))
        self.assertEqual([("app.b", "app")], new_root_dependencies(graph, "app", []))

    def check_existing_root_dependency_can_shrink(self):
        allowed = [["app.a", "app"], ["app.b", "app"]]
        graph = {"app": set(), "app.a": {"app"}, "app.b": set()}
        self.assertEqual([], new_root_dependencies(graph, "app", allowed))
        graph["app.c"] = {"app"}
        self.assertEqual([("app.c", "app")], new_root_dependencies(graph, "app", allowed))

    def check_baseline_records_origin_and_actual_root_edges(self):
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
