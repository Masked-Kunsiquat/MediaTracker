#!/usr/bin/env python3
"""Record Graphban lesson outcomes in bulk, as the human operator.

Graphban's outcome route (POST /api/memory/lessons/{shard}/outcomes) is
"JWT only" by design: an outcome is a human's judgement of whether a lesson
worked, so agent API keys are refused. This script does not change that. It
reads the outcome tables agents write at the end of a slice (AGENTS.md,
"Where memory lives"), shows you what would be recorded, and posts them with
YOUR login token only when you pass --apply.

Input: any text or markdown file containing table rows like

    | m_5e22090c85 | catch | reason ... |
    | `m_a35a17be8e` | miss | reason ... |

Outcome words accepted: catch/caught, miss/missed,
contradiction/contradicted/contradict. Rows with anything else (such as a
header row or a "-" outcome) are listed as skipped, never guessed.

The login token (JWT), first match wins:
  1. the GRAPHBAN_JWT environment variable;
  2. a 1Password reference read with `op read`: --op-ref, else the
     GRAPHBAN_JWT_REF environment variable, else OP_REF below;
  3. a hidden prompt.
There is deliberately no .env file: this repository is public and does not
gitignore `.env`, so a pasted token would be one `git add` from being
published. Login tokens expire: if the token is refused (HTTP 401) you are
prompted once for a fresh one; update the 1Password item if you keep it
there. The token is never printed, logged or written by this script.

To find the token: open the Graphban UI, DevTools -> Network, click any /api/
request, and copy the Authorization header value after "Bearer ".

The server URL is taken from the `graphban` entry in .mcp.json (as the other
scripts here do), or --url.

Usage:
    python scripts/graphban/record_outcomes.py outcomes.md            # dry run (default)
    python scripts/graphban/record_outcomes.py outcomes.md --apply    # actually record

Re-running is safe: before posting, it fetches the lesson and skips any
outcome whose kind and detail are already recorded.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
FALLBACK_URL = "http://10.0.0.27:8080"
# The lesson lookup requires ?project_id=; the outcome POST does not.
DEFAULT_PROJECT = "mediatracker"
# A pointer, not a secret: `op read` needs the owner to unlock 1Password before it yields anything.
OP_REF = "op://Private/graphban/JWT Token"

KINDS = {
    "catch": "caught", "caught": "caught",
    "miss": "missed", "missed": "missed",
    "contradiction": "contradicted", "contradicted": "contradicted",
    "contradict": "contradicted",
}

# | shard | outcome | reason |   (backticks around the shard id are allowed)
ROW = re.compile(r"^\s*\|\s*`?(m_[0-9a-f]{6,})`?\s*\|\s*([^|]+?)\s*\|\s*(.+?)\s*\|\s*$")


def default_url() -> str:
    """The base of the MCP URL in .mcp.json, which serves the REST API too."""
    try:
        cfg = json.loads((ROOT / ".mcp.json").read_text(encoding="utf-8"))
        return cfg["mcpServers"]["graphban"]["url"].rsplit("/api/", 1)[0]
    except (OSError, KeyError, ValueError):
        return FALLBACK_URL


def parse(text: str) -> tuple[list[dict], list[str]]:
    rows, skipped = [], []
    for line in text.splitlines():
        m = ROW.match(line)
        if not m:
            continue
        shard, word, detail = m.group(1), m.group(2).strip().lower(), m.group(3).strip()
        kind = KINDS.get(word)
        if kind is None:
            skipped.append(f"{shard}: outcome {word!r} is not catch/miss/contradiction")
            continue
        rows.append({"shard": shard, "kind": kind, "detail": detail})
    return rows, skipped


def call(url: str, token: str, method: str, path: str, body: dict | None = None):
    req = urllib.request.Request(
        url.rstrip("/") + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", "replace").replace(token, "***")
        return exc.code, text
    except (urllib.error.URLError, OSError) as exc:
        return 0, str(exc).replace(token, "***")


def existing_outcomes(lesson) -> set[tuple[str, str]]:
    """(kind, detail) pairs already recorded on a lesson."""
    results = lesson.get("results") if isinstance(lesson, dict) else None
    record = results[0] if isinstance(results, list) and results else lesson
    outcomes = record.get("outcomes", []) if isinstance(record, dict) else []
    return {(o.get("kind"), (o.get("detail") or "").strip()) for o in outcomes if isinstance(o, dict)}


def interactive() -> bool:
    """True only when a person can answer a prompt.

    isatty() alone is not enough on Windows: stdin redirected from NUL reports
    itself as a terminal, and getpass then reads the console directly and
    waits forever. GetConsoleMode succeeds only on a real console handle.
    """
    if sys.stdin is None or not sys.stdin.isatty():
        return False
    if os.name != "nt":
        return True
    try:
        import ctypes
        import msvcrt

        handle = msvcrt.get_osfhandle(sys.stdin.fileno())
        mode = ctypes.c_uint32()
        return bool(ctypes.windll.kernel32.GetConsoleMode(handle, ctypes.byref(mode)))
    except (OSError, ValueError, AttributeError):
        return False


def prompt_token(reason: str) -> str:
    if not interactive():
        return ""
    return getpass.getpass(f"{reason} Graphban JWT (input hidden): ").strip()


def op_token(ref: str) -> str:
    """Read the token from 1Password. Any failure falls through to the prompt."""
    if not ref:
        return ""
    try:
        out = subprocess.run(["op", "read", ref], capture_output=True, text=True)
    except FileNotFoundError:
        print("1Password CLI (`op`) not found; falling back to a prompt.")
        return ""
    if out.returncode != 0 or not out.stdout.strip():
        print(f"`op read` failed ({out.stderr.strip()[:200] or 'no output'}); falling back to a prompt.")
        return ""
    return out.stdout.strip()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", help="markdown/text file containing outcome table rows")
    ap.add_argument("--apply", action="store_true", help="actually record (default is a dry run)")
    ap.add_argument("--op-ref", help=f"1Password reference for the login token (default: GRAPHBAN_JWT_REF, else {OP_REF})")
    ap.add_argument("--url", help="Graphban base URL (default: from .mcp.json)")
    ap.add_argument("--project", default=DEFAULT_PROJECT, help="project id the lessons belong to (default: %(default)s)")
    args = ap.parse_args(argv)

    url = args.url or default_url()
    project = args.project

    with open(args.file, encoding="utf-8") as fh:
        rows, skipped = parse(fh.read())

    for s in skipped:
        print(f"skip   {s}")
    if not rows:
        print("no outcome rows found")
        return 1

    # A dry run never needs the token: without one it only lists rows, and nothing is read from
    # 1Password or asked for. With --apply the token is resolved env -> 1Password -> prompt.
    token, token_source = os.environ.get("GRAPHBAN_JWT", "").strip(), "environment"
    if args.apply and not token:
        token, token_source = op_token(args.op_ref or os.environ.get("GRAPHBAN_JWT_REF", "").strip() or OP_REF), "1Password"
    if args.apply and not token:
        token, token_source = prompt_token("No token from the environment or 1Password."), "prompt"
    if args.apply and not token:
        print("error: a JWT is required for --apply", file=sys.stderr)
        return 2
    if token and token_source != "prompt":
        print(f"using token from {token_source}")

    posted = failed = dupes = 0
    cache: dict[str, set | None] = {}
    reprompted = False
    for r in rows:
        label = f"{r['shard']} {r['kind']:<12} {r['detail'][:70]}"
        if token:
            if r["shard"] not in cache:
                query = urllib.parse.urlencode({"project_id": project})
                path = f"/api/memory/lessons/{r['shard']}?{query}"
                status, lesson = call(url, token, "GET", path)
                # A kept token may simply have expired: ask once, then retry.
                if status == 401 and token_source != "prompt" and not reprompted:
                    reprompted = True
                    fresh = prompt_token(f"The token from {token_source} was refused (401).")
                    if fresh:
                        token, token_source = fresh, "prompt"
                        status, lesson = call(url, token, "GET", path)
                if status != 200:
                    print(f"FAIL   {label}\n       lookup HTTP {status}: {str(lesson)[:200]}")
                    failed += 1
                    cache[r["shard"]] = None
                    continue
                cache[r["shard"]] = existing_outcomes(lesson)
            if cache[r["shard"]] is None:
                failed += 1
                continue
            if (r["kind"], r["detail"]) in cache[r["shard"]]:
                print(f"dupe   {label}")
                dupes += 1
                continue

        if not args.apply:
            print(f"would  {label}")
            continue

        status, body = call(url, token, "POST", f"/api/memory/lessons/{r['shard']}/outcomes",
                            {"kind": r["kind"], "detail": r["detail"]})
        if status == 200:
            print(f"posted {label}")
            posted += 1
            cache[r["shard"]].add((r["kind"], r["detail"]))
        else:
            print(f"FAIL   {label}\n       HTTP {status}: {str(body)[:300]}")
            failed += 1

    mode = "applied" if args.apply else "dry run"
    print(f"\n{mode}: {len(rows)} rows, {posted} posted, {dupes} already recorded, "
          f"{failed} failed, {len(skipped)} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
