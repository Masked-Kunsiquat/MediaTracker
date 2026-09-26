"""Describe this repository's code graph to Graphban.

Usage (from the repo root, any shell):

    python scripts/graphban/code_graph.py            # scan, upload, prune stale nodes
    python scripts/graphban/code_graph.py --dry-run  # scan and report counts, upload nothing

Two layers are sent, pinned to the current HEAD commit:

* Structure: modules, source sets, packages, docs and config, with hand-written summaries in
  structure.json beside this script. Edit that file when a package is added or its job changes.
* Files: every tracked Kotlin file under shared/src and app/src. A file's summary is the first
  sentence of the KDoc on the declaration named after the file, else on its first public
  top-level declaration. Edges: package owns file, file imports file, and FooTest tested_by Foo.

Import edges only cover explicit imports, so references within one package are absent.

Credentials come from the `graphban` entry in .mcp.json, which is gitignored because
`claude mcp add --header` writes the key into it in plain text. The key is never printed.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
BATCH = 45

TEST_SET = re.compile(r"/src/(commonTest|jvmTest|androidUnitTest|test|androidTest)/")
DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|internal|private|expect|actual|abstract|open|sealed|data|enum|inline|value|"
    r"suspend|annotation|const|operator|infix)\s+)*"
    r"(class|interface|object|fun|val|typealias)\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(`?\w+`?)",
    re.M,
)


def git(*args):
    return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, text=True, check=True).stdout


def content_hash(text):
    norm = "\n".join(line.rstrip() for line in text.splitlines()).rstrip()
    return "sha256:" + hashlib.sha256(norm.encode()).hexdigest()


def kdoc_lead(text, pos):
    """First sentence of the KDoc block immediately preceding `pos`, or ''.

    Private one-line declarations in between, such as a `private const val TAG` with its own
    KDoc, are skipped, because the file's KDoc usually sits above them.
    """
    head = text[:pos].rstrip()
    while True:
        last = head.rsplit("\n", 1)[-1]
        if not re.match(r"private\s", last):
            break
        head = head[:len(head) - len(last)].rstrip()
        if head.endswith("*/"):
            head = head[:head.rfind("/**")].rstrip()
    head += "\n"
    end = head.rfind("*/")
    if end == -1 or head[end + 2:].strip():
        # Annotations may sit between the KDoc and the declaration.
        between = head[end + 2:].strip() if end != -1 else "x"
        if not all(line.strip().startswith("@") for line in between.splitlines() if line.strip()):
            return ""
    start = head.rfind("/**", 0, end)
    if start == -1:
        return ""
    para = []
    for line in head[start + 3:end].splitlines():
        line = re.sub(r"^\s*\*\s?", "", line).strip()
        if not line:
            if para:
                break
            continue
        if line.startswith("@"):
            break
        para.append(line)
    s = " ".join(para)
    m = re.match(r"(.+?[.!?])(\s|$)", s)
    return (m.group(1) if m else s)[:300]


def fallback_summary(path, decls):
    name = path.rsplit("/", 1)[-1][:-3]
    listed = ", ".join(n for _, n in decls[:4])
    if "/dao/" in path:
        return f"Room DAO `{name}`: type-safe queries and Flow-based reads for its table."
    if "/entities/" in path:
        return f"Room entity `{name}`: one table of the schema (see shared/schemas/)."
    if name.endswith("Previews"):
        return f"Compose @Preview functions for {name[:-8]}, with fabricated state."
    if "DatabaseFileOps" in name:
        return f"`{name.split('.')[-1]}` actual for DatabaseFileOps: platform file operations on the live database file."
    if "/theme/" in path:
        return f"MediaTrackerTheme {name.lower()} definitions ({listed})."
    return f"Declares {listed}." if listed else f"{name}."


def scan():
    paths = [p for p in git("ls-files", "*.kt").split() if p.startswith(("shared/src/", "app/src/"))]
    recs, index = [], {}
    for path in paths:
        raw = open(os.path.join(ROOT, path), encoding="utf-8").read()
        pkg = (re.search(r"^package\s+([\w.]+)", raw, re.M) or [None, ""])[1]
        stem = path.rsplit("/", 1)[-1].split(".")[0]
        decls, first_lead, named_lead = [], "", ""
        for m in DECL.finditer(raw):
            line_start = raw.rfind("\n", 0, m.start()) + 1
            if raw[line_start:m.start()].strip() or raw[line_start:line_start + 1].isspace():
                continue  # not top level
            if re.search(r"\bprivate\b", raw[line_start:m.end()]):
                continue
            decls.append((m.group(1), m.group(2)))
            doc = kdoc_lead(raw, line_start)
            if doc and m.group(2).strip("`") == stem:
                named_lead = doc
            first_lead = first_lead or doc
        lead = named_lead or first_lead
        if TEST_SET.search(path):
            summary = lead or f"Tests: {stem}."
        else:
            summary = lead or fallback_summary(path, decls)
        recs.append(dict(path=path, hash=content_hash(raw), summary=summary,
                         imports=re.findall(r"^import\s+([\w.]+)", raw, re.M)))
        for _, name in decls:
            index.setdefault(f"{pkg}.{name}", path)

    nodes, edges = [], []
    main_by_stem = {}
    for r in recs:
        if not TEST_SET.search(r["path"]):
            main_by_stem.setdefault(r["path"].rsplit("/", 1)[-1][:-3], []).append(r["path"])
    for r in recs:
        path = r["path"]
        nodes.append(dict(path=path, kind="file", lang="kotlin", name=path.rsplit("/", 1)[-1],
                          summary=r["summary"], content_hash=r["hash"]))
        edges.append(dict(src=path.rsplit("/", 1)[0], dst=path, type="owns"))
        for dst in sorted({index[i] for i in r["imports"] if index.get(i, path) != path}):
            edges.append(dict(src=path, dst=dst, type="imports"))
        if TEST_SET.search(path):
            base = path.rsplit("/", 1)[-1][:-3]
            stems = {re.sub(r"(OcclusionTest|GoldenTest|Tests|Test)$", "", base), re.sub(r"Test$", "", base)}
            for tested in sorted({m for s in stems for m in main_by_stem.get(s, [])}):
                edges.append(dict(src=tested, dst=path, type="tested_by"))
    return nodes, edges


def structure():
    data = json.load(open(os.path.join(HERE, "structure.json"), encoding="utf-8"))
    for node in data["nodes"]:
        full = os.path.join(ROOT, node["path"])
        if os.path.isfile(full):
            node["content_hash"] = content_hash(open(full, encoding="utf-8").read())
    return data["nodes"], data["edges"]


class Mcp:
    def __init__(self, api_key=None):
        """`api_key` replaces the key from .mcp.json (the URL is still read from there)."""
        cfg_path = os.path.join(ROOT, ".mcp.json")
        if not os.path.exists(cfg_path):
            sys.exit(".mcp.json not found: add the server with `claude mcp add ... graphban ...` first.")
        cfg = json.load(open(cfg_path, encoding="utf-8"))["mcpServers"]["graphban"]
        self.url, self.headers, self.session, self.next_id = cfg["url"], dict(cfg.get("headers", {})), None, 1
        if api_key:
            self.headers = {k: v for k, v in self.headers.items() if k.lower() != "x-api-key"}
            self.headers["X-API-Key"] = api_key
        init = self.call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                        "clientInfo": {"name": "mediatracker-code-graph", "version": "1"}})
        print("connected:", init["serverInfo"]["name"], init["serverInfo"].get("version", ""))
        self.call("notifications/initialized", notify=True)

    def call(self, method, params=None, notify=False):
        body = {"jsonrpc": "2.0", "method": method, **({"params": params} if params is not None else {})}
        if not notify:
            body["id"] = self.next_id = self.next_id + 1
        headers = {**self.headers, "Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        req = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=120) as resp:
            self.session = resp.headers.get("Mcp-Session-Id") or self.session
            raw = resp.read().decode()
        if notify:
            return None
        msgs = [json.loads(raw)] if raw.lstrip().startswith("{") else [
            json.loads(line[5:]) for line in raw.splitlines() if line.startswith("data:")]
        msg = next(m for m in msgs if m.get("id") == body["id"])
        if "error" in msg:
            sys.exit(f"{method} failed: {json.dumps(msg['error'])[:600]}")
        return msg["result"]

    def tool(self, name, args):
        result = self.call("tools/call", {"name": name, "arguments": args})
        if result.get("isError"):
            sys.exit(f"{name} failed: {result['content'][0]['text'][:600]}")
        return json.loads(result["content"][0]["text"])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dry-run", action="store_true", help="scan and report, upload nothing")
    args = ap.parse_args()

    revision = git("rev-parse", "HEAD").strip()
    if git("status", "--porcelain", "--", "shared/src", "app/src").strip():
        print("warning: uncommitted changes under shared/src or app/src; hashes describe the working tree, "
              f"but the map is pinned to {revision[:7]}")
    s_nodes, s_edges = structure()
    f_nodes, f_edges = scan()
    nodes, edges = s_nodes + f_nodes, s_edges + f_edges
    counts = {t: sum(1 for e in edges if e["type"] == t) for t in sorted({e["type"] for e in edges})}
    print(f"{revision[:7]}: {len(s_nodes)} structure + {len(f_nodes)} file nodes; edges {counts}")
    if args.dry_run:
        return

    mcp = Mcp()
    by_src = {}
    for e in edges:
        by_src.setdefault(e["src"], []).append(e)
    described = {n["path"] for n in nodes}
    loose = [e for e in edges if e["src"] not in described]
    sent_n = sent_e = 0
    for start in range(0, len(nodes), BATCH):
        batch = nodes[start:start + BATCH]
        batch_edges = [e for n in batch for e in by_src.get(n["path"], [])]
        if start + BATCH >= len(nodes):
            batch_edges += loose
        out = mcp.tool("describe_code", {"revision": revision, "nodes": batch, "edges": batch_edges})
        sent_n += out["nodes_upserted"]
        sent_e += out["edges_upserted"]
        for c in out.get("kind_corrections", []):
            print(f"  kind corrected: {c['path']} {c['asked']} -> {c['stored']}")
    # One call naming every node, so prune can tell which ones no longer exist.
    pruned = mcp.tool("describe_code", {"revision": revision, "nodes": nodes, "prune": True})
    print(f"uploaded {sent_n} nodes, {sent_e} edges; marked stale: {pruned['marked_stale']}")
    for p in pruned.get("stale_paths", [])[:20]:
        print("  stale:", p)


if __name__ == "__main__":
    main()
