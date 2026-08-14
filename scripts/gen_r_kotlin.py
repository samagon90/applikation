#!/usr/bin/env python3
"""Генерирует R-классы (Kotlin с @JvmField -> байткод, совместимый с Java R)
для каждого пакета из R.txt, который выдаёт aapt2 link."""
import os
import re
import sys
from collections import defaultdict

r_txt, out_dir, packages_csv = sys.argv[1:4]
packages = [p for p in packages_csv.split(",") if p]

KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
}


def esc(name: str) -> str:
    if name in KOTLIN_KEYWORDS or not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", name):
        return f"`{name}`"
    return name


entries = defaultdict(list)  # type -> [(name, kotlin_value)]
for line in open(r_txt, encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    java_type, res_type, name, value = line.split(" ", 3)
    if java_type == "int[]":
        ids = value.strip("{ }").replace(",", ", ")
        entries[res_type].append((name, f"intArrayOf({ids})"))
    else:
        entries[res_type].append((name, value))

for pkg in packages:
    lines = [f"package {pkg}", "", "class R {"]
    for res_type in sorted(entries):
        lines.append(f"    class {esc(res_type)} {{")
        lines.append("        companion object {")
        for name, value in entries[res_type]:
            lines.append(f"            @JvmField val {esc(name)} = {value}")
        lines.append("        }")
        lines.append("    }")
    lines.append("}")
    pkg_dir = os.path.join(out_dir, pkg.replace(".", "_"))
    os.makedirs(pkg_dir, exist_ok=True)
    with open(os.path.join(pkg_dir, "R.kt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

print(f"  R.kt generated for {len(packages)} packages")
