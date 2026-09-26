"""Write a read-only local snapshot of Graphban's memory and items, for when Graphban is unreachable.

Usage (from the repo root):

    python scripts/graphban/snapshot.py

Writes `.graphban-snapshot/` (gitignored): `lessons.md` (every published lesson in this project),
`items.md` (every item that isn't done, with its full description) and `SNAPSHOT.md` (when, from
where, and how to use it). It is a fallback to READ from, never a place to write: Graphban stays
the only store, so a lesson learned while it is down waits in the session summary until it is back.

Only this project's lessons are written. Org-reach lessons from other projects appear in the
catalogue too, and have no business in a MediaTracker snapshot.
"""

import datetime
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from code_graph import ROOT, Mcp  # noqa: E402

OUT = os.path.join(ROOT, ".graphban-snapshot")
PROJECT = "mediatracker"


def paged(mcp, tool, args, key="results"):
    offset = 0
    while True:
        page = mcp.tool(tool, {**args, "limit": 50, "offset": offset})
        rows = page.get(key, [])
        yield from rows
        if not page.get("has_more") or not rows:
            return
        offset += len(rows)


def main():
    mcp = Mcp()
    lessons = [l for l in paged(mcp, "get_lessons", {}) if l.get("project_id") == PROJECT]
    items = [r for r in paged(mcp, "search_items", {"query": "", "fields": "full"}) if r.get("status") != "done"]
    details = [mcp.tool("get_item_details", {"id": r["id"]}) for r in items]
    head = subprocess.run(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"],
                          capture_output=True, text=True).stdout.strip()
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "lessons.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Graphban lessons — {PROJECT}\n\nSnapshot {now}. {len(lessons)} published lessons.\n")
        for l in sorted(lessons, key=lambda l: l["text"]):
            f.write(f"\n## {l['id']}\n\n{l['text'].strip()}\n")
    with open(os.path.join(OUT, "items.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Graphban items — {PROJECT}\n\nSnapshot {now}. {len(details)} items not done.\n")
        for d in sorted(details, key=lambda d: int(d["id"].split("-")[1])):
            f.write(f"\n## {d['id']} — {d['title']}\n\n")
            f.write(f"Status: {d['status']}. Tags: {', '.join(d.get('tags') or []) or 'none'}.")
            if d.get("blocker"):
                f.write(f" Blocker: {d['blocker']}")
            f.write(f"\n\n{(d.get('description') or '').strip()}\n")
    with open(os.path.join(OUT, "SNAPSHOT.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Graphban snapshot\n\nTaken {now} at repo HEAD `{head}` from project `{PROJECT}`.\n\n"
                "**Read-only fallback.** Use it only when the Graphban MCP server is unreachable, and say in "
                "your summary that you worked from a snapshot of this date. Do not edit these files and do "
                "not record new lessons here; they wait for Graphban. Refresh with "
                "`python scripts/graphban/snapshot.py`.\n\n"
                f"- `lessons.md`: {len(lessons)} published lessons\n- `items.md`: {len(details)} open items\n")
    print(f"wrote {OUT}: {len(lessons)} lessons, {len(details)} open items")


if __name__ == "__main__":
    main()
