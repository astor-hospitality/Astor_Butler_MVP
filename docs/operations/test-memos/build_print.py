#!/usr/bin/env python3
"""Собирает печатные версии памяток: docs/operations/test-memos/*.md -> print/*.html.

Только стандартная библиотека. Поддерживается ровно то подмножество Markdown,
которым написаны памятки: заголовки #..###, абзацы, списки «-» и «1.», таблицы,
цитаты «>», горизонтальная линия, **жирный**, *курсив*, `код`, ссылки [текст](url).
Ссылки на соседние .md переписываются на .html, чтобы печатный набор был замкнут.

Запуск из любого каталога: python3 docs/operations/test-memos/build_print.py
"""
from __future__ import annotations

import html
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "print"

CSS = """
@page { size: A4; margin: 14mm 16mm; }
html { font-size: 11.5pt; }
body { font-family: -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
       color: #111; max-width: 180mm; margin: 0 auto; padding: 8mm 6mm; line-height: 1.35; }
h1 { font-size: 1.55em; margin: 0 0 .3em; line-height: 1.2; }
h2 { font-size: 1.15em; margin: 1em 0 .3em; border-bottom: 1px solid #bbb; padding-bottom: .15em; }
h3 { font-size: 1em; margin: .8em 0 .2em; }
p { margin: .35em 0; }
ul, ol { margin: .25em 0 .5em; padding-left: 1.4em; }
li { margin: .12em 0; }
li > ul, li > ol { margin: .1em 0 .1em; }
code { font-family: "SF Mono", Menlo, Consolas, monospace; font-size: .92em;
       background: #f1f1f1; padding: 0 .25em; border-radius: 3px; white-space: nowrap; }
table { border-collapse: collapse; width: 100%; margin: .4em 0 .7em; font-size: .95em; }
th, td { border: 1px solid #999; padding: .25em .45em; vertical-align: top; text-align: left; }
th { background: #eee; }
blockquote { margin: .5em 0; padding: .3em .8em; border-left: 4px solid #999; background: #f7f7f7; }
hr { border: 0; border-top: 1px solid #bbb; margin: .8em 0; }
a { color: #0b4f9c; }
.meta { color: #555; font-size: .9em; margin-bottom: .8em; }
.nav { font-size: .85em; color: #555; margin-bottom: .6em; }
@media print { .nav { display: none; } a { color: inherit; text-decoration: none; } }
"""

INLINE_CODE = re.compile(r"`([^`]+)`")
BOLD = re.compile(r"\*\*(.+?)\*\*")
ITALIC = re.compile(r"(?<![\w*])\*([^*\n]+?)\*(?![\w*])")
LINK = re.compile(r"\[([^\]]+)\]\(([^)\s]+)\)")
TABLE_SEP = re.compile(r"^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$")


def inline(text: str) -> str:
    """Экранирует HTML и раскладывает inline-разметку. Код обрабатывается первым,
    чтобы звёздочки и скобки внутри `…` не считались разметкой."""
    parts: list[str] = []
    pos = 0
    for m in INLINE_CODE.finditer(text):
        parts.append(_inline_plain(text[pos:m.start()]))
        parts.append("<code>" + html.escape(m.group(1)) + "</code>")
        pos = m.end()
    parts.append(_inline_plain(text[pos:]))
    return "".join(parts)


def _inline_plain(text: str) -> str:
    out = html.escape(text, quote=False)

    def link(m: re.Match[str]) -> str:
        label, href = m.group(1), m.group(2)
        if href.endswith(".md") and "/" not in href:
            href = href[:-3] + ".html"
        return f'<a href="{html.escape(href, quote=True)}">{label}</a>'

    out = LINK.sub(link, out)
    out = BOLD.sub(r"<strong>\1</strong>", out)
    out = ITALIC.sub(r"<em>\1</em>", out)
    return out


def split_row(line: str) -> list[str]:
    row = line.strip()
    if row.startswith("|"):
        row = row[1:]
    if row.endswith("|"):
        row = row[:-1]
    return [c.strip() for c in row.split("|")]


def render(md: str) -> tuple[str, str]:
    """Возвращает (заголовок, html-тело)."""
    lines = md.splitlines()
    out: list[str] = []
    title = ""
    i = 0
    para: list[str] = []
    list_stack: list[tuple[int, str]] = []  # (indent, tag)

    def flush_para() -> None:
        if para:
            out.append("<p>" + inline(" ".join(s.strip() for s in para)) + "</p>")
            para.clear()

    def close_lists(to_indent: int = -1) -> None:
        while list_stack and list_stack[-1][0] > to_indent:
            out.append(f"</{list_stack.pop()[1]}>")

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            flush_para()
            close_lists()
            i += 1
            continue

        m = re.match(r"^(#{1,3})\s+(.*)$", stripped)
        if m:
            flush_para()
            close_lists()
            level = len(m.group(1))
            text = m.group(2).strip()
            if level == 1 and not title:
                title = re.sub(r"[*`]", "", text)
            out.append(f"<h{level}>{inline(text)}</h{level}>")
            i += 1
            continue

        if re.match(r"^-{3,}$", stripped):
            flush_para()
            close_lists()
            out.append("<hr>")
            i += 1
            continue

        if stripped.startswith(">"):
            flush_para()
            close_lists()
            quote: list[str] = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                quote.append(lines[i].strip()[1:].strip())
                i += 1
            out.append("<blockquote><p>" + inline(" ".join(quote)) + "</p></blockquote>")
            continue

        if stripped.startswith("|") and i + 1 < len(lines) and TABLE_SEP.match(lines[i + 1]):
            flush_para()
            close_lists()
            header = split_row(stripped)
            i += 2
            rows: list[list[str]] = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            out.append("<table><thead><tr>" + "".join(f"<th>{inline(c)}</th>" for c in header) + "</tr></thead><tbody>")
            for r in rows:
                r = r + [""] * (len(header) - len(r))
                out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r[: len(header)]) + "</tr>")
            out.append("</tbody></table>")
            continue

        m = re.match(r"^(\s*)([-*]|\d+[.)])\s+(.*)$", line)
        if m:
            flush_para()
            indent = len(m.group(1).expandtabs(4))
            tag = "ol" if m.group(2)[0].isdigit() else "ul"
            if list_stack and indent < list_stack[-1][0]:
                close_lists(indent)
            if not list_stack or indent > list_stack[-1][0]:
                out.append(f"<{tag}>")
                list_stack.append((indent, tag))
            elif list_stack[-1][1] != tag:
                out.append(f"</{list_stack.pop()[1]}>")
                out.append(f"<{tag}>")
                list_stack.append((indent, tag))
            item = [m.group(3).strip()]
            # продолжение пункта: следующие строки с большим отступом, не новый пункт
            while (
                i + 1 < len(lines)
                and lines[i + 1].strip()
                and not re.match(r"^\s*([-*]|\d+[.)])\s+", lines[i + 1])
                and (len(lines[i + 1]) - len(lines[i + 1].lstrip())) > indent
            ):
                i += 1
                item.append(lines[i].strip())
            out.append("<li>" + inline(" ".join(item)) + "</li>")
            i += 1
            continue

        para.append(line)
        i += 1

    flush_para()
    close_lists()
    return title, "\n".join(out)


def build(src: Path, nav: str) -> Path:
    title, body = render(src.read_text(encoding="utf-8"))
    title = title or src.stem
    doc = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>{CSS}</style>
</head>
<body>
<div class="nav">{nav}</div>
{body}
</body>
</html>
"""
    dst = OUT / (src.stem + ".html")
    dst.write_text(doc, encoding="utf-8")
    return dst


def main() -> int:
    OUT.mkdir(exist_ok=True)
    sources = sorted(p for p in HERE.glob("*.md"))
    if not sources:
        print("нет .md рядом со скриптом", file=sys.stderr)
        return 1
    nav = " · ".join(
        f'<a href="{p.stem}.html">{html.escape(p.stem.replace("MEMO_", "").replace("_", " "))}</a>' for p in sources
    )
    for src in sources:
        dst = build(src, nav)
        print(f"{src.name} -> {dst.relative_to(HERE)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
