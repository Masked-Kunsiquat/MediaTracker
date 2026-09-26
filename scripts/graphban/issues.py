"""Mirror this repository's GitHub issues into Graphban, one way.

Usage (from the repo root, any shell):

    python scripts/graphban/issues.py            # create/update items
    python scripts/graphban/issues.py --dry-run  # show what would change

GitHub stays the source of truth: nothing written in Graphban flows back. Each open issue becomes
an item titled `#N <issue title>` and tagged `gh-N`, which is how later runs find it again.

Managed on every run: title, status, the `gh-N` / `priority-*` / `kind-*` / `label-*` /
`not-on-board` tags, and the blocker text from the project board's "Blocked by" field. Any other
tag is left alone.

Written once, at creation: the description. After that it belongs to whoever curates the item —
the point of the mirror is the context written there (why the issue is still open, what is
decided, what is left), and a re-sync must never overwrite it.

Status: an open PR referencing `#N` -> review; board "In Progress" -> in_progress; board
priority Now -> next; otherwise backlog. A mirrored issue that has since closed -> done.

Needs the `gh` CLI (with the `project` scope, for the board) and the `graphban` entry in
.mcp.json, the same as code_graph.py.
"""

import argparse
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from code_graph import ROOT, Mcp  # noqa: E402

OWNER = "Masked-Kunsiquat"
BOARD = "12"
MANAGED = re.compile(r"^(gh-\d+|priority-\w+|kind-\w+|label-[\w-]+|not-on-board)$")


def gh(*args):
    out = subprocess.run(["gh", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if out.returncode:
        sys.exit(f"gh {' '.join(args[:3])} failed: {out.stderr.strip()[:400]}")
    return json.loads(out.stdout)


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def fetch():
    issues = gh("issue", "list", "--state", "open", "--limit", "500",
                "--json", "number,title,url,labels")
    board = {}
    for it in gh("project", "item-list", BOARD, "--owner", OWNER, "--limit", "500", "--format", "json")["items"]:
        c = it.get("content", {})
        if c.get("type") == "Issue":
            board[c["number"]] = it
    prs = gh("pr", "list", "--state", "open", "--limit", "200", "--json", "number,title,body,headRefName")
    in_review = {}
    for pr in prs:
        # A PR is *for* an issue only when it says so: a closing keyword, the title, or the branch name.
        # A body merely mentioning `#N` in passing is not enough.
        refs = re.findall(r"\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+#(\d+)", pr.get("body") or "", re.I)
        refs += re.findall(r"#(\d+)\b", pr["title"])
        refs += re.findall(r"/(\d+)-", pr["headRefName"])
        for n in refs:
            in_review.setdefault(int(n), pr["number"])
    return issues, board, in_review


def desired(issue, board, in_review):
    n = issue["number"]
    b = board.get(n)
    tags = [f"gh-{n}"] + [f"label-{slug(l['name'])}" for l in issue["labels"]]
    if b:
        tags += [f"priority-{slug(b['priority'])}"] if b.get("priority") else []
        tags += [f"kind-{slug(b['kind'])}"] if b.get("kind") else []
    else:
        tags.append("not-on-board")
    if n in in_review:
        status = "review"
    elif b and b.get("status") == "In Progress":
        status = "in_progress"
    elif b and b.get("priority") == "Now":
        status = "next"
    else:
        status = "backlog"
    blocked = (b or {}).get("blocked by") or ""
    return dict(title=f"#{n} {issue['title']}", status=status, tags=tags, blocker=blocked,
                pr=in_review.get(n), board=b)


def initial_description(issue, want):
    b = want["board"]
    lines = [f"Mirrored from GitHub issue [#{issue['number']}]({issue['url']}). GitHub is the source "
             "of truth; this item is not synced back.", ""]
    if b:
        lines.append(f"Board: priority **{b.get('priority') or 'unset'}**, kind **{b.get('kind') or 'unset'}**, "
                     f"status {b.get('status')}.")
    else:
        lines.append("Not on the project board.")
    if want["blocker"]:
        lines.append(f"Blocked by (board): {want['blocker']}")
    if want["pr"]:
        lines.append(f"Open PR: #{want['pr']}.")
    lines += ["", "_Context not yet written: read the issue and its comments, then replace this line._"]
    return "\n".join(lines)


def mirrored(mcp):
    items, offset = {}, 0
    while True:
        page = mcp.tool("search_items", {"query": "", "fields": "full", "limit": 100, "offset": offset})
        rows = page.get("results", [])
        for row in rows:
            for t in row.get("tags") or []:
                m = re.fullmatch(r"gh-(\d+)", t)
                if m:
                    items[int(m.group(1))] = row
        if not page.get("has_more") or not rows:
            return items
        offset += len(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    issues, board, in_review = fetch()
    print(f"{len(issues)} open issues, {len(board)} on the board, {len(in_review)} referenced by open PRs")
    mcp = Mcp()
    existing = mirrored(mcp)
    open_numbers = {i["number"] for i in issues}

    for issue in sorted(issues, key=lambda i: i["number"]):
        n, want = issue["number"], desired(issue, board, in_review)
        have = existing.get(n)
        if not have:
            print(f"create #{n} [{want['status']}] {' '.join(want['tags'])}")
            if not args.dry_run:
                mcp.tool("create_item", {"title": want["title"], "status": want["status"], "tags": want["tags"],
                                         "description": initial_description(issue, want),
                                         "idempotency_key": f"mt-gh-issue-{n}"})
            continue
        kept = [t for t in (have.get("tags") or []) if not MANAGED.match(t)]
        patch = {}
        if have.get("title") != want["title"]:
            patch["title"] = want["title"]
        if have.get("status") != want["status"]:
            patch["status"] = want["status"]
        if sorted(have.get("tags") or []) != sorted(want["tags"] + kept):
            patch["tags"] = want["tags"] + kept
        if "blocker" not in have:  # search rows omit it; the full record carries it
            have["blocker"] = mcp.tool("get_item_details", {"id": have["id"]}).get("blocker")
        if (have.get("blocker") or "") != want["blocker"]:
            patch["blocker"] = want["blocker"]
        if patch:
            print(f"update #{n} ({have['id']}): {', '.join(patch)}")
            if not args.dry_run:
                mcp.tool("update_item", {"id": have["id"], **patch})

    for n, have in sorted(existing.items()):
        if n not in open_numbers and have.get("status") != "done":
            state = gh("issue", "view", str(n), "--json", "state")["state"]
            if state == "CLOSED":
                print(f"done   #{n} ({have['id']}): closed on GitHub")
                if not args.dry_run:
                    mcp.tool("update_item", {"id": have["id"], "status": "done"})


if __name__ == "__main__":
    main()
