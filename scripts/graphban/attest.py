"""Attest Graphban items by hand, as a named human observer.

Graphban refuses `done` until a gate-scoped key writes an `attestation` receipt, and the agent
that did the work may not hold that key. This is the human-side holder, for items no CI run can
attest, such as the GitOps onboarding checklist, which is about the repository rather than a commit.

Usage (from the repo root):

    python scripts/graphban/attest.py --adapter "<you>" --plan plan.json [--done] [--dry-run]
    python scripts/graphban/attest.py --adapter "<you>" --item MT-2 \
        --check "remote=origin is github.com/Masked-Kunsiquat/MediaTracker" [--commit <sha>] [--done]

A plan file is a JSON list of {"item", "detail", "checks": {name: what you observed}}.

Every item is shown with its predicates and needs a `y` before anything is written: the
attestation is your claim that you looked, so look. `--done` also moves each attested item to
done; without it the receipt is written and the item stays where it is.

The gate key comes from GRAPHBAN_GATE_KEY, or a hidden prompt if unset. It is never read from
.mcp.json (that holds the agent's key, which must not be the one attesting its own work) and never
taken as an argument (argv lands in shell history). The server URL is read from .mcp.json.
"""

import argparse
import getpass
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from code_graph import ROOT, Mcp  # noqa: E402


def evidence_kinds(tools):
    tool = next((t for t in tools if t.get("name") == "update_item"), None)
    if tool is None:
        return None
    ev = (tool.get("inputSchema") or {}).get("properties", {}).get("evidence", {})
    return set(ev.get("items", {}).get("properties", {}).get("kind", {}).get("enum") or [])


def load_plan(args):
    if args.plan:
        plan = json.load(open(args.plan, encoding="utf-8"))
    else:
        if not args.item or not args.check:
            sys.exit("give --plan, or --item with at least one --check NAME=OBSERVATION")
        checks = {}
        for raw in args.check:
            name, sep, seen = raw.partition("=")
            if not sep or not name.strip() or not seen.strip():
                sys.exit(f"--check needs NAME=OBSERVATION, got {raw!r}")
            checks[name.strip()] = seen.strip()
        plan = [{"item": args.item, "detail": args.detail, "checks": checks}]
    for entry in plan:
        if not entry.get("item") or not entry.get("checks"):
            sys.exit(f"plan entry needs an item and at least one check: {entry}")
    return plan


def receipt(entry, adapter, commit):
    return {
        "kind": "attestation",
        "adapter": adapter,
        "commit": commit,
        "detail": entry.get("detail") or f"{adapter} observed {entry['item']} by hand",
        "predicates": [{"name": n, "passed": True, "detail": seen} for n, seen in entry["checks"].items()],
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--adapter", required=True, help="who observed this: your name, never 'github-actions'")
    ap.add_argument("--plan", help="JSON file of items to attest")
    ap.add_argument("--item")
    ap.add_argument("--check", action="append", default=[], metavar="NAME=OBSERVATION")
    ap.add_argument("--detail", default="")
    ap.add_argument("--commit", help="commit the observation binds to (default: origin/main)")
    ap.add_argument("--done", action="store_true", help="also move each attested item to done")
    ap.add_argument("--dry-run", action="store_true", help="show the receipts; contact nothing")
    args = ap.parse_args()

    if args.adapter.strip().lower() in {"github-actions", "ci"}:
        sys.exit("--adapter is who looked. A hand-run attestation attributed to CI misstates its provenance.")
    plan = load_plan(args)
    commit = args.commit or subprocess.run(["git", "-C", ROOT, "rev-parse", "origin/main"],
                                           capture_output=True, text=True, check=True).stdout.strip()

    if args.dry_run:
        for entry in plan:
            print(json.dumps({"id": entry["item"], "evidence": [receipt(entry, args.adapter, commit)],
                              **({"status": "done"} if args.done else {})}, indent=2))
        return

    key = os.environ.get("GRAPHBAN_GATE_KEY", "").strip() or getpass.getpass("Graphban gate key (hidden): ").strip()
    if not key:
        sys.exit("no gate key given")
    mcp = Mcp(api_key=key)
    kinds = evidence_kinds(mcp.call("tools/list").get("tools", []))
    if kinds is None:
        sys.exit("this key cannot call update_item")
    if kinds and "attestation" not in kinds:
        sys.exit(f"update_item here accepts {sorted(kinds)}, not 'attestation': this key is not gate-scoped")

    for entry in plan:
        print(f"\n{entry['item']} at {commit[:9]}, observed by {args.adapter}:")
        for name, seen in entry["checks"].items():
            print(f"  - {name}: {seen}")
        if input("Attest this? [y/N] ").strip().lower() != "y":
            print("  skipped")
            continue
        mcp.tool("update_item", {"id": entry["item"], "evidence": [receipt(entry, args.adapter, commit)]})
        print("  attested")
        if args.done:
            out = mcp.tool("update_item", {"id": entry["item"], "status": "done"})
            print(f"  status: {out.get('status')}")


if __name__ == "__main__":
    main()
