#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检测仓库内部 Java 包依赖；静态检查不代替产品行为测试。"""

from __future__ import annotations

import argparse
from collections import defaultdict, deque
from dataclasses import dataclass
import io
import itertools
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile

MOD_PACKAGE = "git.chexson.chexsonsaeutils"
IDENT = r"(?:[^\W\d]|[$_])(?:\w|[$])*"
DOTTED = IDENT + r"(?:\s*\.\s*" + IDENT + r")*"
PACKAGE_RE = re.compile(r"\bpackage\s+(" + DOTTED + r")\s*;")
IMPORT_RE = re.compile(r"\bimport\s+(static\s+)?(" + DOTTED + r"(?:\s*\.\s*\*)?)\s*;")
TOKEN_RE = re.compile(IDENT + r"|[^\s\w]")
CHAIN_RE = re.compile(r"(?<![\w$.])" + IDENT + r"(?:\s*\.\s*" + IDENT + r")+")


def decode_unicode_escapes(text: str) -> str:
    """Java 在词法处理前翻译 Unicode 转义；按连续反斜线的奇偶判定资格。"""
    out = []
    index = 0
    slashes = 0
    last_translated = False
    while index < len(text):
        char = text[index]
        if char == "\\" and (slashes % 2 == 0 or last_translated):
            end = index + 1
            while end < len(text) and text[end] == "u":
                end += 1
            if end > index + 1:
                digits = text[end:end + 4]
                if len(digits) != 4 or not re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                    raise ValueError("Java Unicode 转义不完整")
                char = chr(int(digits, 16))
                out.append(char)
                slashes = slashes + 1 if char == "\\" else 0
                last_translated = True
                index = end + 4
                continue
        out.append(char)
        slashes = slashes + 1 if char == "\\" else 0
        last_translated = False
        index += 1
    return "".join(out)


def mask_java(text: str) -> str:
    """复用旧 common.mask 的状态机思路，保留换行，修正转义字符与文本块结束识别。"""
    text = decode_unicode_escapes(text)
    out = list(text)
    state = "code"
    index = 0

    def blank(start: int, end: int) -> None:
        for pos in range(start, end):
            if text[pos] not in "\r\n":
                out[pos] = " "

    while index < len(text):
        char = text[index]
        if state == "code":
            if text.startswith("//", index):
                state, width = "line", 2
            elif text.startswith("/*", index):
                state, width = "block", 2
            elif text.startswith('"""', index):
                state, width = "text", 3
            elif char in "\"'":
                state, width = char, 1
            else:
                index += 1
                continue
            blank(index, index + width)
            index += width
            continue
        if state == "line" and char in "\r\n":
            state = "code"
        elif state == "block" and text.startswith("*/", index):
            blank(index, index + 2)
            index += 2
            state = "code"
            continue
        elif state in ('"', "'", "text"):
            if char == "\\":
                blank(index, min(index + 2, len(text)))
                index += 2
                continue
            if state == "text" and text.startswith('"""', index):
                blank(index, index + 3)
                index += 3
                state = "code"
                continue
            if char == state:
                blank(index, index + 1)
                index += 1
                state = "code"
                continue
            if state != "text" and char in "\r\n":
                raise ValueError("Java 字符串或字符字面量未闭合")
        blank(index, index + 1)
        index += 1
    if state not in ("code", "line"):
        raise ValueError("Java 注释或字面量未闭合")
    return "".join(out)


@dataclass(frozen=True)
class Source:
    path: str
    package: str
    types: tuple[str, ...]
    imports: tuple[tuple[str, int, bool], ...]
    body: str


def normalize_dotted(text: str) -> str:
    return re.sub(r"\s+", "", text)


def parse_source(text: str, path: str = "fixture.java") -> Source:
    masked = mask_java(text)
    packages = list(PACKAGE_RE.finditer(masked))
    if len(packages) != 1:
        raise ValueError(f"{path}: 需要恰好一个 package 声明")
    package = normalize_dotted(packages[0].group(1))
    imports = []
    body = list(masked)
    declarations = [packages[0], *IMPORT_RE.finditer(masked)]
    for match in declarations:
        if match.re is IMPORT_RE:
            imports.append((normalize_dotted(match.group(2)), masked.count("\n", 0, match.start()) + 1,
                            bool(match.group(1))))
        for index in range(match.start(), match.end()):
            if body[index] not in "\r\n":
                body[index] = " "
    body_text = "".join(body)
    tokens = [match.group() for match in TOKEN_RE.finditer(body_text)]
    depth = 0
    types = []
    for index, token in enumerate(tokens):
        if token == "{":
            depth += 1
        elif token == "}":
            depth -= 1
            if depth < 0:
                raise ValueError(f"{path}: 大括号不配对")
        elif depth == 0 and token in ("class", "interface", "enum", "record"):
            if index and tokens[index - 1] == ".":
                continue
            if index + 1 < len(tokens) and re.fullmatch(IDENT, tokens[index + 1]):
                types.append(tokens[index + 1])
    if depth:
        raise ValueError(f"{path}: 大括号不配对")
    return Source(path, package, tuple(types), tuple(imports), body_text)


@dataclass
class DependencyGraph:
    nodes: set[str]
    edges: dict[tuple[str, str], list[dict]]
    files: int
    excluded_mixin_files: int
    excluded_mixin_edges: int

    def adjacency(self, imports_only: bool = False) -> dict[str, set[str]]:
        graph = {node: set() for node in self.nodes}
        for (source, target), evidence in self.edges.items():
            if not imports_only or any(item["kind"] == "import" for item in evidence):
                graph[source].add(target)
        return graph


def is_mixin(package: str) -> bool:
    return "mixin" in package.split(".")


def build_graph(sources: list[Source], include_mixin: bool = False,
                package_prefix: str | None = None) -> DependencyGraph:
    """只认源文件声明出的顶层类型；嵌套类型及静态成员归属其顶层类型所在包。"""
    packages = {source.package for source in sources}
    types = {}
    for source in sources:
        for name in source.types:
            qualified = source.package + "." + name
            if qualified in types:
                raise ValueError(f"重复类型声明：{qualified}")
            types[qualified] = source.package

    def resolve(symbol: str) -> str | None:
        parts = symbol.split(".")
        while parts:
            candidate = ".".join(parts)
            if candidate in types:
                return types[candidate]
            parts.pop()
        return None

    all_edges = defaultdict(list)
    for source in sources:
        for symbol, line, static in source.imports:
            target = resolve(symbol)
            if target is None and not static and symbol.endswith(".*") and symbol[:-2] in packages:
                target = symbol[:-2]
            if target is None and package_prefix and symbol.startswith(package_prefix + "."):
                raise ValueError(f"{source.path}:{line}: 仓库内部 import 找不到声明类型：{symbol}")
            if target is not None and target != source.package:
                all_edges[source.package, target].append({"kind": "import", "file": source.path,
                                                         "line": line, "symbol": symbol})
        for match in CHAIN_RE.finditer(source.body):
            symbol = normalize_dotted(match.group())
            target = resolve(symbol)
            if target is not None and target != source.package:
                all_edges[source.package, target].append({"kind": "fqn", "file": source.path,
                                                         "line": source.body.count("\n", 0, match.start()) + 1,
                                                         "symbol": symbol})
    excluded = {package for package in packages if is_mixin(package)} if not include_mixin else set()
    edges = {key: value for key, value in all_edges.items() if not set(key) & excluded}
    return DependencyGraph(packages - excluded, edges, len(sources),
                           sum(source.package in excluded for source in sources), len(all_edges) - len(edges))


def strongly_connected_components(graph: dict[str, set[str]]) -> list[list[str]]:
    """Tarjan：返回完整强连通分量，覆盖三个及更多包构成的长环。"""
    index = 0
    indices = {}
    low = {}
    stack = []
    on_stack = set()
    components = []

    def visit(node: str) -> None:
        nonlocal index
        indices[node] = low[node] = index
        index += 1
        stack.append(node)
        on_stack.add(node)
        for target in sorted(graph[node]):
            if target not in indices:
                visit(target)
                low[node] = min(low[node], low[target])
            elif target in on_stack:
                low[node] = min(low[node], indices[target])
        if low[node] == indices[node]:
            component = []
            while True:
                target = stack.pop()
                on_stack.remove(target)
                component.append(target)
                if target == node:
                    break
            components.append(sorted(component))

    for node in sorted(graph):
        if node not in indices:
            visit(node)
    return sorted(components, key=lambda component: (-len(component), component))


def cyclic_components(graph: dict[str, set[str]]) -> list[list[str]]:
    return [component for component in strongly_connected_components(graph) if len(component) > 1]


def direct_pairs(graph: dict[str, set[str]]) -> list[list[str]]:
    return sorted([source, target] for source in graph for target in graph[source]
                  if source < target and source in graph[target])


def representative_cycle(graph: dict[str, set[str]], component: list[str]) -> list[str]:
    """确定性广度优先搜索，返回一个实际存在的闭合路径。"""
    start = min(component)
    members = set(component)
    queue = deque([[start]])
    visited = {start}
    while queue:
        path = queue.popleft()
        for target in sorted(graph[path[-1]] & members):
            if target == start and len(path) > 1:
                return path + [start]
            if target not in visited:
                visited.add(target)
                queue.append(path + [target])
    raise ValueError("强连通分量中未找到代表环")


def cyclic_relations(components: list[list[str]]) -> set[tuple[str, str]]:
    return {pair for component in components for pair in itertools.combinations(sorted(component), 2)}


def new_cyclic_relations(graph: dict[str, set[str]], allowed: list[list[str]]) -> list[tuple[str, str]]:
    return sorted(cyclic_relations(cyclic_components(graph)) - cyclic_relations(allowed))


def summarize(graph: dict[str, set[str]]) -> dict:
    components = cyclic_components(graph)
    return {"nodes": len(graph), "edges": sum(map(len, graph.values())),
            "direct_pairs": direct_pairs(graph), "cyclic_components": components,
            "representative_cycles": [representative_cycle(graph, component) for component in components]}


def root_dependencies(graph: dict[str, set[str]], root_package: str) -> list[list[str]]:
    """业务包对入口根包的直接依赖；既有回指只准减少。"""
    return sorted([source, root_package] for source, targets in graph.items()
                  if source != root_package and root_package in targets)


def new_root_dependencies(graph: dict[str, set[str]], root_package: str,
                          allowed: list[list[str]]) -> list[tuple[str, str]]:
    return sorted(set(map(tuple, root_dependencies(graph, root_package))) - set(map(tuple, allowed)))


def make_baseline(graph: DependencyGraph, include_mixin: bool, package_prefix: str, source: dict) -> dict:
    return {"schema": 2, "source": source,
            "scope": {"source_root": "src/main/java", "include_mixin": include_mixin,
                                   "package_prefix": package_prefix, "references": ["import", "fqn"]},
            "allowed_cyclic_components": cyclic_components(graph.adjacency()),
            "allowed_root_dependencies": root_dependencies(graph.adjacency(), package_prefix)}


def load_sources(root: Path, package_prefix: str) -> list[Source]:
    source_root = root / "src" / "main" / "java"
    if not source_root.is_dir():
        raise ValueError(f"源码目录不存在：{source_root}")
    sources = []
    for path in sorted(source_root.rglob("*.java")):
        source = parse_source(path.read_text(encoding="utf-8"), path.relative_to(root).as_posix())
        if source.package == package_prefix or source.package.startswith(package_prefix + "."):
            sources.append(source)
    if not sources:
        raise ValueError(f"未找到包前缀 {package_prefix} 的源码")
    return sources


def git_commit(root: Path, reference: str) -> str:
    result = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "--end-of-options",
                             reference + "^{commit}"], capture_output=True, check=True)
    return result.stdout.decode("ascii").strip()


def load_sources_from_git(root: Path, package_prefix: str, commit: str) -> list[Source]:
    """只读 git archive，直接在内存中解析；不解包到磁盘、不改真实源码。"""
    result = subprocess.run(["git", "-C", str(root), "archive", "--format=tar", commit,
                             "--", "src/main/java"], capture_output=True, check=True)
    sources = []
    with tarfile.open(fileobj=io.BytesIO(result.stdout), mode="r:") as archive:
        members = sorted((member for member in archive.getmembers()
                          if member.isfile() and member.name.startswith("src/main/java/")
                          and member.name.endswith(".java")), key=lambda member: member.name)
        for member in members:
            with archive.extractfile(member) as stream:
                source = parse_source(stream.read().decode("utf-8"), member.name)
            if source.package == package_prefix or source.package.startswith(package_prefix + "."):
                sources.append(source)
    if not sources:
        raise ValueError(f"提交 {commit} 未找到包前缀 {package_prefix} 的源码")
    return sources


def main(argv: list[str] | None = None) -> int:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=script_dir.parent.parent, help="指定仓库路径")
    parser.add_argument("--package-prefix", default=MOD_PACKAGE)
    parser.add_argument("--include-mixin", action="store_true", help="显式纳入 mixin 包及其边")
    parser.add_argument("--baseline", type=Path, default=script_dir / "baseline.json")
    parser.add_argument("--check", action="store_true", help="拒绝新增循环互达包对和入口根包回指")
    parser.add_argument("--refresh-baseline", action="store_true", help="审核后显式刷新基线")
    parser.add_argument("--source-ref", help="从指定 git 提交生成基线，仅可配合 --refresh-baseline")
    parser.add_argument("--json-output", type=Path, help="保存完整报告与逐边证据")
    args = parser.parse_args(argv)
    if args.check and args.refresh_baseline:
        parser.error("--check 与 --refresh-baseline 不能同时使用")
    if args.source_ref and not args.refresh_baseline:
        parser.error("--source-ref 仅可配合 --refresh-baseline；--check 始终检查当前源码")
    try:
        root = args.root.resolve()
        if args.source_ref:
            commit = git_commit(root, args.source_ref)
            source = {"kind": "git-ref", "commit": commit}
            sources = load_sources_from_git(root, args.package_prefix, commit)
        else:
            source = {"kind": "working-tree"}
            sources = load_sources(root, args.package_prefix)
            if args.refresh_baseline:
                source["head_commit"] = git_commit(root, "HEAD")
        graph = build_graph(sources, args.include_mixin, args.package_prefix)
        scope = make_baseline(graph, args.include_mixin, args.package_prefix, source)
        report = {"scope": scope["scope"], "source": source, "files": graph.files,
                  "excluded_mixin_files": graph.excluded_mixin_files,
                  "excluded_mixin_edges": graph.excluded_mixin_edges,
                  "import_only": summarize(graph.adjacency(imports_only=True)),
                  "import_and_fqn": summarize(graph.adjacency()),
                  "root_dependencies": root_dependencies(graph.adjacency(), args.package_prefix),
                  "edge_evidence": [{"source": source, "target": target, "references": evidence}
                                    for (source, target), evidence in sorted(graph.edges.items())]}
        print(f"口径：src/main/java，import＋FQN，{'包含' if args.include_mixin else '排除'} mixin 包及其边")
        print("来源：" + json.dumps(source, ensure_ascii=False))
        print(f"源码文件 {graph.files}；排除 mixin 文件 {graph.excluded_mixin_files}、边 {graph.excluded_mixin_edges}")
        for label, name in (("仅 import", "import_only"), ("import＋FQN", "import_and_fqn")):
            summary = report[name]
            print(f"{label}：包 {summary['nodes']}，边 {summary['edges']}，"
                  f"直接双向对 {len(summary['direct_pairs'])}，循环 SCC 大小 "
                  f"{[len(component) for component in summary['cyclic_components']]}")
            for cycle in summary["representative_cycles"]:
                print("  代表环：" + " → ".join(cycle))
            for source, target in summary["direct_pairs"]:
                print(f"  双向对：{source} ↔ {target}")
        if args.refresh_baseline:
            args.baseline.write_text(json.dumps(scope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"基线已刷新：{args.baseline.resolve()}")
        failures = []
        root_failures = []
        if args.check:
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
            if baseline["schema"] != 2 or baseline["scope"] != scope["scope"]:
                raise ValueError("基线版本或扫描口径不匹配，不允许改变口径后沿用基线")
            failures = new_cyclic_relations(graph.adjacency(), baseline["allowed_cyclic_components"])
            root_failures = new_root_dependencies(graph.adjacency(), args.package_prefix,
                                                  baseline["allowed_root_dependencies"])
            report["new_cyclic_relations"] = failures
            report["new_root_dependencies"] = root_failures
            report["baseline_source"] = baseline["source"]
            for source, target in failures:
                print(f"新增循环互达：{source} ↔ {target}")
            for source, target in root_failures:
                print(f"新增入口根包回指：{source} → {target}")
            print("架构检查失败：新增循环互达或入口回指" if failures or root_failures
                  else "架构检查通过：未新增循环互达或入口回指")
        if args.json_output:
            args.json_output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 1 if failures or root_failures else 0
    except subprocess.CalledProcessError as error:
        print("架构检查错误：git 只读命令失败：" + error.stderr.decode("utf-8", errors="replace").strip(),
              file=sys.stderr)
        return 2
    except (OSError, ValueError, KeyError, tarfile.TarError) as error:
        print(f"架构检查错误：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
