#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
软著「程序鉴别材料」节选生成器。

- 页眉 / 页脚：写入 Pandoc YAML（LaTeX fancyhdr），导出 PDF 时位于版心外的页眉页脚区，
  而非正文段落里的「表格」。
- 正文：仅连续代码块；页与页之间用 raw LaTeX \\newpage，便于一页 50 行与 PDF 页对齐。
- 统计说明：只写入「生成说明」txt，避免多出封面/说明页。

输出：
- docs/软著_源程序代码_MyShell_V1.0.md
- docs/软著_源程序材料_生成说明.txt
"""
from __future__ import annotations

import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC_MAIN = ROOT / "app" / "src" / "main" / "java"

# 须与《计算机软件著作权登记申请表》完全一致
APP_FULL_NAME = "MyShell 移动终端 SSH 客户端"
VERSION = "V1.0"
COPYRIGHT_OWNER = " 北京大夏智汇科技有限公司 "

LINES_PER_PAGE = 50
FIRST_PAGES = 30
LAST_PAGES = 30


def strip_block_comments(text: str) -> str:
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        if i < n - 1 and text[i : i + 2] == "/*":
            j = text.find("*/", i + 2)
            if j == -1:
                break
            skipped = text[i : j + 2]
            out.append("\n" * skipped.count("\n"))
            i = j + 2
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def strip_trailing_line_comment(line: str) -> str:
    in_str = False
    quote: str | None = None
    esc = False
    i = 0
    while i < len(line):
        c = line[i]
        if esc:
            esc = False
            i += 1
            continue
        if not in_str:
            if c in "\"'":
                in_str = True
                quote = c
            elif i < len(line) - 1 and line[i : i + 2] == "//":
                return line[:i].rstrip()
            i += 1
            continue
        assert quote is not None
        if c == "\\":
            esc = True
        elif c == quote:
            in_str = False
            quote = None
        i += 1
    return line.rstrip()


def is_substantive_physical_line(physical_line: str) -> bool:
    s = strip_trailing_line_comment(physical_line).strip()
    return bool(s)


def collect_substantive() -> list[tuple[str, str]]:
    kt_files = sorted(SRC_MAIN.rglob("*.kt"))
    substantive: list[tuple[str, str]] = []
    for fp in kt_files:
        rel = str(fp.relative_to(SRC_MAIN)).replace("\\", "/")
        raw = fp.read_text(encoding="utf-8")
        no_block = strip_block_comments(raw)
        for line in no_block.splitlines():
            if not is_substantive_physical_line(line):
                continue
            show = strip_trailing_line_comment(line)
            show = redact_line(rel, show)
            substantive.append((rel, show.rstrip()))
    return substantive


def redact_line(rel_path: str, line: str) -> str:
    if "CryptoManager.kt" in rel_path and line.strip().startswith("private const val KEY_ALIAS"):
        return '    private const val KEY_ALIAS = "████████████████"'
    return line


def pick_segments(sub: list[tuple[str, str]]) -> tuple[list[tuple[str, str]], str]:
    n = len(sub)
    if n == 0:
        return [], "无源代码。"
    total_pages = max(1, math.ceil(n / LINES_PER_PAGE))
    note_parts = [
        f"有效代码行总计（不含空行与注释）：{n} 行。",
        f"按每页 {LINES_PER_PAGE} 行有效代码计，约 {total_pages} 页。",
    ]
    max_combo = FIRST_PAGES + LAST_PAGES
    if total_pages <= max_combo:
        note_parts.append(f"不足 {max_combo} 页时须提交全部源代码（当前规则：已全部节选）。")
        return sub, "\n".join(note_parts)

    first_lines = FIRST_PAGES * LINES_PER_PAGE
    last_lines = LAST_PAGES * LINES_PER_PAGE
    head = sub[:first_lines]
    tail = sub[-last_lines:]
    note_parts.append(
        f"超过 {max_combo} 页时提交前 {FIRST_PAGES} 页 + 后 {LAST_PAGES} 页（共 {max_combo} 页），中间不重复列出。"
    )
    note_parts.append(f"节选：前 {len(head)} 行 + 后 {len(tail)} 行有效代码。")
    merged = head + tail
    return merged, "\n".join(note_parts)


def paginate(rows: list[tuple[str, str]]) -> list[list[str]]:
    pages: list[list[str]] = []
    i, n = 0, len(rows)
    while i < n:
        chunk: list[str] = []
        for _ in range(LINES_PER_PAGE):
            if i >= n:
                break
            _, line = rows[i]
            chunk.append(line)
            i += 1
        if chunk:
            pages.append(chunk)
    return pages


def _fence_escape_code(code: str) -> str:
    if "```" not in code:
        return code
    return code.replace("```", "``\u200b`")


def latex_escape_simple(s: str) -> str:
    """用于 \\fancyhead/\\fancyfoot 正文的 LaTeX 特殊字符转义。"""
    return (
        s.replace("\\", r"\textbackslash{}")
        .replace("{", r"\{")
        .replace("}", r"\}")
        .replace("&", r"\&")
        .replace("%", r"\%")
        .replace("$", r"\$")
        .replace("#", r"\#")
        .replace("_", r"\_")
    )


def build_pandoc_yaml() -> str:
    """页眉页脚由 fancyhdr 输出到 PDF 页边距区域（非正文段落）。"""
    head_left = latex_escape_simple(f"{APP_FULL_NAME} {VERSION}")
    foot = latex_escape_simple(f"著作权人：{COPYRIGHT_OWNER}")
    # 页码用 \\thepage，与 PDF 物理页一致；正文勿再写「第 n 页」。
    return f"""---
pdf-engine: xelatex
documentclass: article
fontsize: 10pt
geometry: margin=2.5cm
header-includes: |
  \\usepackage{{fancyhdr}}
  \\setlength{{\\headheight}}{{16pt}}
  \\pagestyle{{fancy}}
  \\fancyhf{{}}
  \\fancyhead[L]{{{head_left}}}
  \\fancyhead[R]{{第~\\thepage~页}}
  \\fancyfoot[C]{{{foot}}}
  \\renewcommand{{\\headrulewidth}}{{0.4pt}}
  \\renewcommand{{\\footrulewidth}}{{0pt}}
---

"""


def render_body_pandoc(page_chunks: list[list[str]]) -> str:
    """正文仅为代码；页间 \\newpage，便于一页对应一段代码（导出 PDF 时用 XeLaTeX）。"""
    parts: list[str] = []
    for i, chunk in enumerate(page_chunks):
        if i > 0:
            parts.append("```{=latex}\n\\newpage\n```\n\n")
        code = _fence_escape_code("\n".join(chunk))
        parts.append(f"```kotlin\n{code}\n```\n")
    return "".join(parts)


def main() -> None:
    sub = collect_substantive()
    rows, stat_note = pick_segments(sub)
    page_chunks = paginate(rows)
    total_pages = len(page_chunks)

    md = build_pandoc_yaml() + render_body_pandoc(page_chunks)

    out_md = ROOT / "docs" / "软著_源程序代码_MyShell_V1.0.md"
    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_md.write_text(md, encoding="utf-8")

    readme = ROOT / "docs" / "软著_源程序材料_生成说明.txt"
    readme.write_text(
        "\n".join(
            [
                "软著程序鉴别材料（源代码节选）— 生成说明",
                "",
                stat_note,
                "",
                f"软件全称：{APP_FULL_NAME}",
                f"版本号：{VERSION}",
                f"著作权人（请在 scripts/generate_soft_copyright_source.py 中修改 COPYRIGHT_OWNER）：{COPYRIGHT_OWNER}",
                f"逻辑页数：{total_pages} 页（每页 {LINES_PER_PAGE} 行有效代码）。",
                "",
                "关于页眉页脚：",
                "- 生成的 .md 带有 YAML，其中使用 LaTeX fancyhdr 定义页眉（左：软件全称+版本号；右：第 x 页）、页脚（著作权人）。",
                "- 这些内容在「用 Pandoc 导出为 PDF」时会出现在版式页眉/页脚区，而不是正文里的表格。",
                "- 若页数多于逻辑页数，多为代码行过长自动折行、或字号过大；可在 pandoc 中加小字号 / 调整 listings；或减少 YAML 外多余正文。",
                "",
                "Pandoc 生成 PDF 示例（WSL / Linux，按本机字体改 Noto 名称）：",
                '  pandoc "软著_源程序代码_MyShell_V1.0.md" -o "软著_程序源代码.pdf" \\',
                "    --pdf-engine=xelatex \\",
                '    -V CJKmainfont="Noto Serif CJK SC" \\',
                '    -V mainfont="Noto Serif CJK SC" \\',
                '    -V monofont="Noto Sans Mono CJK SC"',
                "",
                "导出 Word（无 LaTeX 页眉；需在 Word 里自行插入页眉页脚）：",
                '  pandoc "软著_源程序代码_MyShell_V1.0.md" -o "软著_程序源代码.docx"',
                "",
                "重新生成命令：",
                f'  python "{ROOT / "scripts" / "generate_soft_copyright_source.py"}"',
                "",
            ]
        ),
        encoding="utf-8",
    )

    print(f"Wrote {out_md}")
    print(f"Wrote {readme}")
    print(f"Pages: {total_pages}")


if __name__ == "__main__":
    main()
