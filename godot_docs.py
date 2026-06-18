import re
import sys
import html
import argparse
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

@dataclass
class DocParam:
    name: str
    type_hint: str = ""
    description: str = ""
    default: str = ""


@dataclass
class DocMember:
    kind: str
    name: str
    type_hint: str = ""
    default: str = ""
    doc: str = ""
    deprecated: bool = False
    experimental: bool = False


@dataclass
class DocEnum:
    name: str
    doc: str = ""
    values: list = field(default_factory=list)


@dataclass
class DocFunction:
    name: str
    params: list = field(default_factory=list)
    return_type: str = ""
    doc: str = ""
    is_static: bool = False
    deprecated: bool = False
    experimental: bool = False


@dataclass
class DocClass:
    name: str
    file: str
    extends: str = ""
    doc: str = ""
    members: list = field(default_factory=list)
    functions: list = field(default_factory=list)
    enums: list = field(default_factory=list)
    deprecated: bool = False
    experimental: bool = False

_BBCODE = [
    (r'\[b\](.*?)\[/b\]',                        r'<strong>\1</strong>'),
    (r'\[i\](.*?)\[/i\]',                        r'<em>\1</em>'),
    (r'\[code\](.*?)\[/code\]',                  r'<code>\1</code>'),
    (r'\[param\s+(\w+)\]',                       r'<code class="sig-param">\1</code>'),
    (r'\[codeblock\](.*?)\[/codeblock\]',        r'<pre><code>\1</code></pre>'),
    (r'\[br\]',                                  r'<br>'),
    (r'\[url=(.*?)\](.*?)\[/url\]',              r'<a href="\1">\2</a>'),
    (r'\[url\](.*?)\[/url\]',                    r'<a href="\1">\1</a>'),
    (r'\[method\s+(\w+)\]',                      r'<a href="#fn-\1">\1</a>'),
    (r'\[member\s+(\w+)\]',                      r'<a href="#var-\1">\1</a>'),
    (r'\[constant\s+(\w+)\]',                    r'<a href="#const-\1">\1</a>'),
    (r'\[signal\s+(\w+)\]',                      r'<a href="#sig-\1">\1</a>'),
    (r'\[enum\s+(\w+)\]',                        r'<a href="#enum-\1">\1</a>'),
]

_known_classes: set[str] = set()

def bbcode_to_html(text: str) -> str:
    text = html.escape(text)
    for pattern, repl in _BBCODE:
        text = re.sub(pattern, repl, text, flags=re.DOTALL)
    def _class_link(m: re.Match) -> str:
        name = m.group(1)
        if name in _known_classes:
            return f'<a href="{name}.html">{name}</a>'
        return m.group(0)
    text = re.sub(r'\[([A-Z]\w+)\]', _class_link, text)
    return text


def format_doc(raw: str) -> str:
    if not raw:
        return ""
    raw = raw.replace('@deprecated', '').replace('@experimental', '').strip()
    paragraphs = []
    current: list[str] = []
    for line in raw.split("\n"):
        stripped = line.strip()
        if stripped == "":
            if current:
                paragraphs.append("<p>" + bbcode_to_html(" ".join(current)) + "</p>")
                current = []
        else:
            current.append(stripped)
    if current:
        paragraphs.append("<p>" + bbcode_to_html(" ".join(current)) + "</p>")
    return "\n".join(paragraphs)

_DOC_LINE    = re.compile(r'^##(.*)$')
_CLASS_NAME  = re.compile(r'^class_name\s+(\w+)')
_EXTENDS     = re.compile(r'^extends\s+(\S+)')
_VAR_DECL    = re.compile(
    r'^(?:static\s+)?var\s+(?P<name>\w+)'
    r'(?:\s*:\s*(?P<type>[^=\n]+?))?'
    r'(?:\s*:?=\s*(?P<default>[^\n]+?))?'
    r'\s*(?::|$)'
)
_CONST_DECL  = re.compile(
    r'^const\s+(?P<name>\w+)'
    r'(?:\s*:\s*(?P<type>[^=\n]+?))?'
    r'\s*:?=\s*(?P<default>[^\n]+)'
)
_FUNC_DECL   = re.compile(
    r'^(?P<static>static\s+)?func\s+(?P<name>\w+)\s*\((?P<params>[^)]*)\)'
    r'(?:\s*->\s*(?P<ret>[^:]+))?'
)
_SIGNAL_DECL = re.compile(r'^signal\s+(\w+)(?:\s*\(([^)]*)\))?')
_ENUM_DECL   = re.compile(r'^enum\s+(\w+)\s*\{([^}]*)\}')
_ENUM_OPEN   = re.compile(r'^enum\s+(\w+)\s*\{')

def _parse_params(raw: str) -> list[DocParam]:
    params = []
    raw = raw.strip()
    if not raw:
        return params
    depth = 0
    parts = []
    buf: list[str] = []
    for ch in raw:
        if ch in "([{":
            depth += 1; buf.append(ch)
        elif ch in ")]}":
            depth -= 1; buf.append(ch)
        elif ch == ',' and depth == 0:
            parts.append(''.join(buf).strip()); buf = []
        else:
            buf.append(ch)
    if buf:
        parts.append(''.join(buf).strip())
    for part in parts:
        part = part.strip()
        if not part:
            continue
        default = ""
        if '=' in part:
            part, default = part.split('=', 1)
            default = default.strip(); part = part.strip()
        if ':' in part:
            name, type_hint = part.split(':', 1)
        else:
            name, type_hint = part, ""
        name = name.strip(); type_hint = type_hint.strip()
        if name and name != 'self':
            params.append(DocParam(name=name, type_hint=type_hint, default=default))
    return params

def _extract_flags(doc: str) -> tuple[str, bool, bool]:
    dep = '@deprecated' in doc
    exp = '@experimental' in doc
    return doc, dep, exp

def parse_file(path: Path) -> Optional[DocClass]:
    lines = path.read_text(encoding='utf-8').splitlines()

    class_name = path.stem
    extends = ""
    for line in lines:
        m = _CLASS_NAME.match(line.strip())
        if m: class_name = m.group(1)
        m = _EXTENDS.match(line.strip())
        if m: extends = m.group(1)

    dc = DocClass(name=class_name, file=path.name, extends=extends)
    pending_doc: list[str] = []
    class_doc_consumed = False
    i = 0

    while i < len(lines):
        stripped = lines[i].strip()
        m = _DOC_LINE.match(stripped)
        if m:
            content = m.group(1)
            pending_doc.append(content[1:] if content.startswith(' ') else content)
            i += 1
            continue

        doc_text = "\n".join(pending_doc).strip()
        pending_doc = []

        if _CLASS_NAME.match(stripped) or _EXTENDS.match(stripped):
            if not class_doc_consumed and doc_text:
                raw_doc, dep, exp = _extract_flags(doc_text)
                dc.doc = raw_doc; dc.deprecated = dep; dc.experimental = exp
                class_doc_consumed = True
            i += 1; continue

        m = _ENUM_DECL.match(stripped)
        if m:
            raw_doc, dep, exp = _extract_flags(doc_text)
            de = DocEnum(name=m.group(1), doc=raw_doc)
            for val in m.group(2).split(','):
                val = val.strip()
                if val:
                    vn = val.split('=')[0].strip()
                    vd = val.split('=')[1].strip() if '=' in val else ""
                    de.values.append(DocMember(kind="enum_val", name=vn, default=vd))
            dc.enums.append(de)
            i += 1; continue

        m = _ENUM_OPEN.match(stripped)
        if m:
            raw_doc, dep, exp = _extract_flags(doc_text)
            de = DocEnum(name=m.group(1), doc=raw_doc)
            i += 1
            while i < len(lines):
                vline = lines[i].strip()
                closing = '}' in vline
                if closing:
                    vline = vline[:vline.index('}')]
                for val in vline.split(','):
                    val = val.strip().split('#')[0].strip()
                    if val:
                        vn = val.split('=')[0].strip()
                        vd = val.split('=')[1].strip() if '=' in val else ""
                        if vn:
                            de.values.append(DocMember(kind="enum_val", name=vn, default=vd))
                if closing:
                    break
                i += 1
            dc.enums.append(de)
            i += 1; continue

        m = _CONST_DECL.match(stripped)
        if m:
            raw_doc, dep, exp = _extract_flags(doc_text)
            dc.members.append(DocMember(
                kind="const", name=m.group('name'),
                type_hint=m.group('type') or "",
                default=(m.group('default') or "").strip(),
                doc=raw_doc, deprecated=dep, experimental=exp,
            ))
            i += 1; continue

        m = _VAR_DECL.match(stripped)
        if m and not stripped.startswith('func') and not stripped.startswith('#'):
            raw_doc, dep, exp = _extract_flags(doc_text)
            dc.members.append(DocMember(
                kind="var", name=m.group('name'),
                type_hint=(m.group('type') or "").strip().rstrip(':'),
                default=(m.group('default') or "").strip(),
                doc=raw_doc, deprecated=dep, experimental=exp,
            ))
            i += 1; continue

        m = _SIGNAL_DECL.match(stripped)
        if m:
            raw_doc, dep, exp = _extract_flags(doc_text)
            dc.members.append(DocMember(
                kind="signal", name=m.group(1),
                type_hint=m.group(2) or "",
                doc=raw_doc, deprecated=dep, experimental=exp,
            ))
            i += 1; continue

        m = _FUNC_DECL.match(stripped)
        if m:
            is_private = m.group('name').startswith('_')
            if is_private and not doc_text:
                i += 1; continue
            raw_doc, dep, exp = _extract_flags(doc_text)
            params = _parse_params(m.group('params'))
            for pm in re.finditer(r'\[param\s+(\w+)\]\s*([^\[]*)', raw_doc):
                pname, pdesc = pm.group(1), pm.group(2).strip()
                for p in params:
                    if p.name == pname:
                        p.description = pdesc
            dc.functions.append(DocFunction(
                name=m.group('name'), params=params,
                return_type=(m.group('ret') or "void").strip(),
                doc=raw_doc, is_static=bool(m.group('static')),
                deprecated=dep, experimental=exp,
            ))
            i += 1; continue

        if doc_text and not class_doc_consumed:
            raw_doc, dep, exp = _extract_flags(doc_text)
            dc.doc = raw_doc; dc.deprecated = dep; dc.experimental = exp
            class_doc_consumed = True

        i += 1

    return dc

CSS = """
:root {
    --bg: #f2f4f8;
    --bg2: #fff;
    --border: #e0e0e0;
    --text: #222;
    --text-aside: #6e6e6e;
    --link: #1f70c2;
    --kw: #056bd6;
    --type: #9f5f30;
    --fn: #be3989;
    --param: #4760ec;
    --sig-bg: #f9f9fb;
}

*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 14px;
    line-height: 1.6;
    color: var(--text);
    background: var(--bg);
    display: flex;
    flex-direction: column;
    min-height: 100vh;
}

header {
    background: var(--bg2);
    border-bottom: 1px solid var(--border);
    padding: 0 24px;
    height: 44px;
    display: flex;
    align-items: center;
    gap: 16px;
    position: sticky;
    top: 0;
    z-index: 10;
}

header .title {
    font-weight: 700;
    font-size: 15px;
    color: var(--text);
    text-decoration: none;
}

header .breadcrumb {
    font-size: 13px;
    color: var(--text-aside);
    display: flex;
    align-items: center;
    gap: 6px;
}

header .breadcrumb a { color: var(--link); text-decoration: none; }
header .breadcrumb a:hover { text-decoration: underline; }
header .breadcrumb .sep { color: var(--text-aside); }

.layout {
    display: flex;
    flex: 1;
    max-width: 1200px;
    margin: 0 auto;
    width: 100%;
    padding: 0 16px;
    gap: 0;
}

nav#sidebar {
    width: 220px;
    min-width: 220px;
    padding: 20px 0 40px;
    position: sticky;
    top: 44px;
    height: calc(100vh - 44px);
    overflow-y: auto;
    flex-shrink: 0;
}

nav#sidebar .nav-section {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: .08em;
    text-transform: uppercase;
    color: var(--text-aside);
    padding: 16px 8px 4px;
}

nav#sidebar a {
    display: block;
    padding: 3px 8px;
    color: var(--text);
    text-decoration: none;
    font-size: 13px;
    border-radius: 3px;
}

nav#sidebar a:hover { background: var(--border); }
nav#sidebar a.active { background: #dbe8f6; color: var(--link); font-weight: 600; }

main {
    flex: 1;
    min-width: 0;
    padding: 24px 0 80px 32px;
}

.index-header {
    margin-bottom: 24px;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--border);
}

.index-header h1 { font-size: 22px; font-weight: 700; }
.index-header p { color: var(--text-aside); margin-top: 4px; font-size: 13px; }

.class-index-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
}

.class-index-table th {
    text-align: left;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: .06em;
    color: var(--text-aside);
    padding: 4px 8px;
    border-bottom: 1px solid var(--border);
}

.class-index-table td {
    padding: 6px 8px;
    border-bottom: 1px solid #f0f0f0;
    vertical-align: top;
}

.class-index-table tr:last-child td { border-bottom: none; }
.class-index-table .class-link { font-weight: 600; color: var(--link); text-decoration: none; }
.class-index-table .class-link:hover { text-decoration: underline; }
.class-index-table .extends-col { color: var(--text-aside); font-family: monospace; font-size: 12px; }
.class-index-table .doc-col { color: var(--text-aside); }

.page-title {
    margin-bottom: 20px;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--border);
}

.page-title h1 {
    font-size: 22px;
    font-weight: 700;
    display: flex;
    align-items: baseline;
    gap: 10px;
    flex-wrap: wrap;
}

.page-title .file-tag {
    font-size: 12px;
    font-weight: 400;
    color: var(--text-aside);
    font-family: monospace;
}

.page-title .extends-line {
    font-size: 13px;
    color: var(--text-aside);
    margin-top: 4px;
    font-family: monospace;
}

.page-title .extends-line .ext-name { color: var(--link); }

.page-doc { font-size: 14px; margin-top: 12px; }

.index-panel {
    background: var(--bg2);
    border: 1px solid var(--border);
    border-radius: 4px;
    margin-bottom: 24px;
    overflow: hidden;
}

.index-panel summary {
    padding: 10px 14px;
    cursor: pointer;
    font-weight: 600;
    font-size: 13px;
    list-style: none;
    display: flex;
    align-items: center;
    gap: 6px;
    user-select: none;
}

.index-panel summary::-webkit-details-marker { display: none; }
.index-panel summary::before { content: "▸"; font-size: 10px; color: var(--text-aside); }
details[open] .index-panel summary::before { content: "▾"; }
details[open] .index-panel summary { border-bottom: 1px solid var(--border); }

.index-panel .index-body {
    padding: 10px 14px;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 2px 16px;
}

.index-panel .index-section-label {
    grid-column: 1 / -1;
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: .06em;
    color: var(--text-aside);
    padding: 8px 0 2px;
}

.index-panel a {
    font-size: 13px;
    color: var(--link);
    text-decoration: none;
    padding: 1px 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.index-panel a:hover { text-decoration: underline; }

.section {
    margin-top: 32px;
}

.section > h2 {
    font-size: 16px;
    font-weight: 700;
    margin-bottom: 12px;
    padding-bottom: 6px;
    border-bottom: 1px solid var(--border);
}

.member-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    background: var(--bg2);
    border: 1px solid var(--border);
    border-radius: 4px;
    overflow: hidden;
}

.member-table th {
    text-align: left;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: .06em;
    color: var(--text-aside);
    padding: 6px 12px;
    background: var(--sig-bg);
    border-bottom: 1px solid var(--border);
    font-weight: 600;
}

.member-table td {
    padding: 8px 12px;
    border-bottom: 1px solid #f0f0f0;
    vertical-align: top;
}

.member-table tr:last-child td { border-bottom: none; }
.member-table .name-col { font-family: monospace; font-weight: 600; }
.member-table .name-col a { color: var(--text); text-decoration: none; }
.member-table .type-col { font-family: monospace; color: var(--type); white-space: nowrap; }
.member-table .default-col { font-family: monospace; color: var(--text-aside); font-size: 12px; }
.member-table .doc-col { color: var(--text-aside); }

.fn-entry {
    background: var(--bg2);
    border: 1px solid var(--border);
    border-radius: 4px;
    margin-bottom: 12px;
    overflow: hidden;
}

.fn-entry .fn-sig {
    padding: 10px 14px;
    background: var(--sig-bg);
    border-bottom: 1px solid var(--border);
    font-family: monospace;
    font-size: 13px;
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 0;
}

.fn-sig .kw   { color: var(--kw); font-weight: 700; margin-right: 5px; }
.fn-sig .fn   { color: var(--fn); font-weight: 700; }
.fn-sig .paren{ color: var(--text-aside); }
.fn-sig .pn   { color: var(--param); }
.fn-sig .pt   { color: var(--type); }
.fn-sig .pd   { color: var(--text-aside); }
.fn-sig .ret  { color: var(--type); }
.fn-sig .sep  { color: var(--text-aside); margin: 0 2px; }

.fn-entry .fn-body {
    padding: 12px 14px;
}

.fn-body .fn-doc { font-size: 13px; color: var(--text); margin-bottom: 10px; }
.fn-body .fn-doc p { margin-bottom: 4px; }

.fn-body .param-list {
    font-size: 12px;
    border-top: 1px solid #f0f0f0;
    padding-top: 8px;
    margin-top: 4px;
}

.fn-body .param-list table {
    border-collapse: collapse;
    width: 100%;
}

.fn-body .param-list th {
    text-align: left;
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: .06em;
    color: var(--text-aside);
    padding: 2px 8px 2px 0;
    font-weight: 600;
}

.fn-body .param-list td {
    padding: 2px 8px 2px 0;
    vertical-align: top;
}

.fn-body .param-list .p-name { font-family: monospace; font-weight: 700; color: var(--param); }
.fn-body .param-list .p-type { font-family: monospace; color: var(--type); }
.fn-body .param-list .p-default { font-family: monospace; color: var(--text-aside); }
.fn-body .param-list .p-desc { color: var(--text-aside); }

.enum-entry {
    background: var(--bg2);
    border: 1px solid var(--border);
    border-radius: 4px;
    margin-bottom: 12px;
    overflow: hidden;
}

.enum-entry .enum-sig {
    padding: 10px 14px;
    background: var(--sig-bg);
    border-bottom: 1px solid var(--border);
    font-family: monospace;
    font-size: 13px;
}

.enum-entry .enum-sig .kw { color: var(--kw); font-weight: 700; margin-right: 5px; }
.enum-entry .enum-body { padding: 10px 14px; }
.enum-entry .enum-doc { font-size: 13px; color: var(--text-aside); margin-bottom: 8px; }

.enum-val-table { font-family: monospace; font-size: 12px; border-collapse: collapse; }
.enum-val-table td { padding: 1px 12px 1px 0; }
.enum-val-table .v-name { font-weight: 700; }
.enum-val-table .v-val  { color: var(--text-aside); }

.tag {
    display: inline-block;
    font-size: 10px;
    font-weight: 700;
    padding: 1px 5px;
    border-radius: 3px;
    margin-left: 6px;
    vertical-align: middle;
}
.tag-dep { background: #fee2e2; color: #991b1b; }
.tag-exp { background: #fef3c7; color: #92400e; }

code { font-family: monospace; background: #eef0f4; padding: 1px 4px; border-radius: 3px; font-size: .9em; }
.sig-param { font-family: monospace; color: var(--param); }
pre { background: #1e1e2e; color: #cdd6f4; padding: 12px 14px; border-radius: 4px; overflow-x: auto; font-size: 12px; }
pre code { background: none; padding: 0; color: inherit; }
p { margin-bottom: .5em; }
p:last-child { margin-bottom: 0; }
a { color: var(--link); }

@media (max-width: 720px) {
    .layout { padding: 0 8px; }
    nav#sidebar { display: none; }
    main { padding: 16px 0 40px; }
}
"""


def _tags(dep: bool, exp: bool) -> str:
    s = ""
    if dep: s += '<span class="tag tag-dep">deprecated</span>'
    if exp: s += '<span class="tag tag-exp">experimental</span>'
    return s


def _fn_sig_html(fn: DocFunction) -> str:
    kw = "static func" if fn.is_static else "func"
    parts = [f'<span class="kw">{kw}</span><span class="fn">{html.escape(fn.name)}</span><span class="paren">(</span>']
    psigs = []
    for p in fn.params:
        s = f'<span class="pn">{html.escape(p.name)}</span>'
        if p.type_hint:
            s += f'<span class="sep">:</span><span class="pt">{html.escape(p.type_hint)}</span>'
        if p.default:
            s += f'<span class="sep">=</span><span class="pd">{html.escape(p.default)}</span>'
        psigs.append(s)
    parts.append('<span class="sep">, </span>'.join(psigs))
    parts.append('<span class="paren">)</span>')
    if fn.return_type and fn.return_type != "void":
        parts.append(f'<span class="sep"> -&gt; </span><span class="ret">{html.escape(fn.return_type)}</span>')
    return "".join(parts)


def _sidebar(classes: list[DocClass], active: str) -> str:
    lines = ['<div class="nav-section">Classes</div>']
    for dc in sorted(classes, key=lambda c: c.name):
        cls = ' class="active"' if dc.name == active else ''
        lines.append(f'<a href="{html.escape(dc.name)}.html"{cls}>{html.escape(dc.name)}</a>')
    return "\n".join(lines)


def _page(title: str, breadcrumb: str, sidebar_html: str, content: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>{CSS}</style>
</head>
<body>
<header>
  <a class="title" href="index.html">qti-neon</a>
  <span class="sep" style="color:#ccc">|</span>
  <nav class="breadcrumb">{breadcrumb}</nav>
</header>
<div class="layout">
  <nav id="sidebar">
    <a href="index.html">Overview</a>
    {sidebar_html}
  </nav>
  <main>{content}</main>
</div>
</body>
</html>"""

def render_index(classes: list[DocClass]) -> str:
    sb = _sidebar(classes, "index")
    rows = []
    for dc in sorted(classes, key=lambda c: c.name):
        ext = f'<span class="extends-col">extends {html.escape(dc.extends)}</span>' if dc.extends else ''
        first = ""
        if dc.doc:
            first = re.sub(r'<[^>]+>', '', format_doc(dc.doc.split('\n')[0]))
            if len(first) > 100:
                first = first[:100] + '…'
        rows.append(
            f'<tr>'
            f'<td><a class="class-link" href="{html.escape(dc.name)}.html">{html.escape(dc.name)}</a>{_tags(dc.deprecated, dc.experimental)}</td>'
            f'<td>{ext}</td>'
            f'<td class="doc-col">{html.escape(first)}</td>'
            f'</tr>'
        )
    content = f"""
<div class="index-header">
  <h1>qti-neon</h1>
  <p>Godot addon documentation</p>
</div>
<table class="class-index-table">
<thead><tr><th>Class</th><th>Extends</th><th>Description</th></tr></thead>
<tbody>{''.join(rows)}</tbody>
</table>"""
    return _page("qti-neon", "Overview", sb, content)

def render_class_page(dc: DocClass, all_classes: list[DocClass]) -> str:
    sb = _sidebar(all_classes, dc.name)

    consts  = [m for m in dc.members if m.kind == "const"]
    vars_   = [m for m in dc.members if m.kind == "var"]
    signals = [m for m in dc.members if m.kind == "signal"]
    pub_fns = [f for f in dc.functions if not f.name.startswith('_') or f.doc]

    title_html = f"""
<div class="page-title">
  <h1>{html.escape(dc.name)}{_tags(dc.deprecated, dc.experimental)} <span class="file-tag">{html.escape(dc.file)}</span></h1>"""
    if dc.extends:
        title_html += f'\n  <div class="extends-line">extends <span class="ext-name">{html.escape(dc.extends)}</span></div>'
    if dc.doc:
        title_html += f'\n  <div class="page-doc">{format_doc(dc.doc)}</div>'
    title_html += "\n</div>"

    index_sections: list[str] = []
    if dc.enums:
        index_sections.append('<div class="index-section-label">Enumerations</div>')
        for en in dc.enums:
            index_sections.append(f'<a href="#enum-{html.escape(en.name)}">{html.escape(en.name)}</a>')
    if consts:
        index_sections.append('<div class="index-section-label">Constants</div>')
        for m in consts:
            index_sections.append(f'<a href="#const-{html.escape(m.name)}">{html.escape(m.name)}</a>')
    if vars_:
        index_sections.append('<div class="index-section-label">Properties</div>')
        for m in vars_:
            index_sections.append(f'<a href="#var-{html.escape(m.name)}">{html.escape(m.name)}</a>')
    if signals:
        index_sections.append('<div class="index-section-label">Signals</div>')
        for m in signals:
            index_sections.append(f'<a href="#sig-{html.escape(m.name)}">{html.escape(m.name)}</a>')
    if pub_fns:
        index_sections.append('<div class="index-section-label">Methods</div>')
        for fn in pub_fns:
            index_sections.append(f'<a href="#fn-{html.escape(fn.name)}">{html.escape(fn.name)}</a>')

    index_panel = ""
    if index_sections:
        index_panel = f"""
<details open>
  <div class="index-panel">
    <summary>Index</summary>
    <div class="index-body">
      {''.join(index_sections)}
    </div>
  </div>
</details>"""

    parts = [title_html, index_panel]

    if dc.enums:
        parts.append('<div class="section"><h2>Enumerations</h2>')
        for en in dc.enums:
            vals = "".join(
                f'<tr><td class="v-name">{html.escape(v.name)}</td>'
                f'<td class="v-val">{("= " + html.escape(v.default)) if v.default else ""}</td></tr>'
                for v in en.values
            )
            parts.append(
                f'<div class="enum-entry" id="enum-{html.escape(en.name)}">'
                f'<div class="enum-sig"><span class="kw">enum</span>{html.escape(en.name)}</div>'
                f'<div class="enum-body">'
                + (f'<div class="enum-doc">{format_doc(en.doc)}</div>' if en.doc else '')
                + f'<table class="enum-val-table"><tbody>{vals}</tbody></table>'
                + '</div></div>'
            )
        parts.append('</div>')

    if consts:
        rows = ""
        for m in consts:
            rows += (
                f'<tr id="const-{html.escape(m.name)}">'
                f'<td class="name-col">{html.escape(m.name)}{_tags(m.deprecated, m.experimental)}</td>'
                f'<td class="default-col">{html.escape(m.default)}</td>'
                f'<td class="doc-col">{format_doc(m.doc) if m.doc else ""}</td>'
                f'</tr>'
            )
        parts.append(
            '<div class="section"><h2>Constants</h2>'
            '<table class="member-table"><thead><tr><th>Name</th><th>Value</th><th>Description</th></tr></thead>'
            f'<tbody>{rows}</tbody></table></div>'
        )

    if vars_:
        rows = ""
        for m in vars_:
            type_cell = f'<span class="type-col">{html.escape(m.type_hint)}</span>' if m.type_hint else '<span style="color:#ccc">Variant</span>'
            rows += (
                f'<tr id="var-{html.escape(m.name)}">'
                f'<td class="type-col">{type_cell}</td>'
                f'<td class="name-col">{html.escape(m.name)}{_tags(m.deprecated, m.experimental)}</td>'
                f'<td class="default-col">{html.escape(m.default) if m.default else ""}</td>'
                f'<td class="doc-col">{format_doc(m.doc) if m.doc else ""}</td>'
                f'</tr>'
            )
        parts.append(
            '<div class="section"><h2>Properties</h2>'
            '<table class="member-table"><thead><tr><th>Type</th><th>Name</th><th>Default</th><th>Description</th></tr></thead>'
            f'<tbody>{rows}</tbody></table></div>'
        )

    if signals:
        rows = ""
        for m in signals:
            rows += (
                f'<tr id="sig-{html.escape(m.name)}">'
                f'<td class="name-col">{html.escape(m.name)}</td>'
                f'<td class="type-col">{html.escape(m.type_hint)}</td>'
                f'<td class="doc-col">{format_doc(m.doc) if m.doc else ""}</td>'
                f'</tr>'
            )
        parts.append(
            '<div class="section"><h2>Signals</h2>'
            '<table class="member-table"><thead><tr><th>Name</th><th>Parameters</th><th>Description</th></tr></thead>'
            f'<tbody>{rows}</tbody></table></div>'
        )

    if pub_fns:
        parts.append('<div class="section"><h2>Methods</h2>')
        for fn in pub_fns:
            doc_html = f'<div class="fn-doc">{format_doc(fn.doc)}</div>' if fn.doc else ''

            param_table = ""
            if fn.params:
                rows_p = ""
                for p in fn.params:
                    rows_p += (
                        f'<tr>'
                        f'<td class="p-name">{html.escape(p.name)}</td>'
                        f'<td class="p-type">{html.escape(p.type_hint) if p.type_hint else ""}</td>'
                        f'<td class="p-default">{html.escape(p.default) if p.default else ""}</td>'
                        f'<td class="p-desc">{html.escape(p.description) if p.description else ""}</td>'
                        f'</tr>'
                    )
                param_table = (
                    '<div class="param-list">'
                    '<table><thead><tr>'
                    '<th>Parameter</th><th>Type</th><th>Default</th><th>Description</th>'
                    f'</tr></thead><tbody>{rows_p}</tbody></table></div>'
                )

            parts.append(
                f'<div class="fn-entry" id="fn-{html.escape(fn.name)}">'
                f'<div class="fn-sig">{_fn_sig_html(fn)}</div>'
                f'<div class="fn-body">{_tags(fn.deprecated, fn.experimental)}{doc_html}{param_table}</div>'
                f'</div>'
            )
        parts.append('</div>')

    breadcrumb = f'<a href="index.html">qti-neon</a> <span class="sep">/</span> {html.escape(dc.name)}'
    return _page(f"{dc.name} | qti-neon", breadcrumb, sb, "\n".join(parts))

def main():
    ap = argparse.ArgumentParser(description="Generate HTML docs for GDScript files.")
    ap.add_argument("input", nargs="?", default="godot",
                    help="Root directory to search for .gd files (default: godot/)")
    ap.add_argument("-o", "--output", default="docs/godot",
                    help="Output directory (default: docs/godot/)")
    ap.add_argument("--include-private", action="store_true",
                    help="Include private (_-prefixed) classes")
    args = ap.parse_args()

    input_dir = Path(args.input)
    out_dir = Path(args.output)

    if not input_dir.exists():
        print(f"Error: '{input_dir}' not found.", file=sys.stderr)
        sys.exit(1)

    gd_files = sorted(input_dir.rglob("*.gd"))
    if not gd_files:
        print("No .gd files found.", file=sys.stderr)
        sys.exit(1)

    classes: list[DocClass] = []
    for gd in gd_files:
        dc = parse_file(gd)
        if dc is None:
            continue
        if not args.include_private and dc.name.startswith('_'):
            continue
        classes.append(dc)

    if not classes:
        print("No documentable classes found.")
        sys.exit(0)

    out_dir.mkdir(parents=True, exist_ok=True)

    _known_classes.update(dc.name for dc in classes)

    (out_dir / "index.html").write_text(render_index(classes), encoding='utf-8')
    print("  index.html")

    for dc in classes:
        page = render_class_page(dc, classes)
        out_path = out_dir / f"{dc.name}.html"
        out_path.write_text(page, encoding='utf-8')
        print(f"  {out_path.name}")

    print(f"\nDone. {len(classes)} pages written to {out_dir}/")


if __name__ == "__main__":
    main()
