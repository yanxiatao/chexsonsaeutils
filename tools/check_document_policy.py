#!/usr/bin/env python3
"""检查 Git 暂存区中的文档白名单，阻止强制添加的开发文档进入提交。"""

from fnmatch import fnmatchcase
from pathlib import PurePosixPath
import argparse
import subprocess
import sys


DOCUMENT_SUFFIXES = {".md", ".markdown", ".rst", ".adoc", ".txt", ".pdf", ".doc", ".docx"}


def git(*args: str) -> bytes:
    """读取暂存区，确保检查对象与将要提交的内容一致；Git 错误直接终止。"""
    return subprocess.check_output(["git", *args])


def main() -> int:
    """仅允许玩家指南、发布说明、许可声明与 CI 所需文档。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true", help="同时检查当前分支完整历史")
    args = parser.parse_args()
    patterns = [
        line.strip()
        for line in git("show", ":.document-policy").decode("utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    paths = git("ls-files", "--cached", "-z").split(b"\0")
    if args.history:
        paths += git("log", "--format=", "--name-only", "-z", "HEAD").split(b"\0")
    forbidden = set()
    for raw_path in paths:
        raw_path = raw_path.lstrip(b"\n")
        if not raw_path:
            continue
        path = raw_path.decode("utf-8")
        name = PurePosixPath(path).name.lower()
        is_document = PurePosixPath(path).suffix.lower() in DOCUMENT_SUFFIXES
        is_document |= name.startswith(("license", "copying", "notice"))
        if is_document and not any(fnmatchcase(path, pattern) for pattern in patterns):
            forbidden.add(path)
    if forbidden:
        print("禁止提交开发文档，请从暂存区移除并保留在本地：", file=sys.stderr)
        for path in sorted(forbidden):
            print(f"  {path}", file=sys.stderr)
        return 1
    print("文档提交范围检查通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
