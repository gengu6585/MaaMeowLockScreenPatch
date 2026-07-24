#!/usr/bin/env python3
"""搜索 / 校验 PRTS Copilot 作业（不下载也可只列出）。

用法:
  python3 search_prts_jobs.py --stage AD-1
  python3 search_prts_jobs.py --keyword 丰川祥子 --activity act43side
  python3 search_prts_jobs.py --id 97725 --validate
  python3 search_prts_jobs.py --stage AD-1 --download --out-dir /tmp/jobs
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://prts.maa.plus"

ACT43 = {
    "AD-1": "act43side_01",
    "AD-2": "act43side_02",
    "AD-3": "act43side_03",
    "AD-4": "act43side_04",
    "AD-5": "act43side_05",
    "AD-6": "act43side_06",
    "AD-7": "act43side_07",
    "AD-8": "act43side_08",
    "AD-EX-1": "act43side_ex01",
    "AD-EX-2": "act43side_ex02",
    "AD-EX-3": "act43side_ex03",
    "AD-EX-4": "act43side_ex04",
    "AD-EX-5": "act43side_ex05",
    "AD-EX-6": "act43side_ex06",
    "AD-EX-7": "act43side_ex07",
    "AD-EX-8": "act43side_ex08",
}


def http_get(path: str, params: dict | None = None, retries: int = 3) -> dict:
    url = f"{API}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "maa-meow-skill/1.1"})
            with urllib.request.urlopen(req, timeout=90) as resp:
                data = json.load(resp)
            if data.get("status_code") != 200:
                raise RuntimeError(f"API {path}: {data.get('message', data)}")
            return data
        except Exception as e:  # noqa: BLE001
            last_err = e
            if attempt + 1 < retries:
                import time
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"GET {url} failed after {retries}: {last_err}") from last_err


def validate_job(content: dict) -> list[str]:
    errs: list[str] = []
    if not content.get("stage_name"):
        errs.append("missing stage_name")
    if not content.get("actions"):
        errs.append("missing actions[] — MAA 无法抄作业")
    if not content.get("opers") and not content.get("groups"):
        errs.append("warning: no opers/groups")
    ver = content.get("minimum_required") or content.get("version")
    if ver is None:
        errs.append("warning: no minimum_required/version")
    return errs


def search(
    *,
    stage_id: str | None,
    keyword: str | None,
    pages: int,
    limit: int,
) -> list[dict]:
    hits: list[dict] = []
    for page in range(1, pages + 1):
        params = {"page": page, "limit": 50, "desc": "true", "orderBy": "hot"}
        if keyword:
            params["document"] = keyword
        data = http_get("/copilot/query", params)
        for item in data["data"]["data"]:
            content = json.loads(item["content"])
            sn = content.get("stage_name", "")
            title = (content.get("doc") or {}).get("title", "")
            if stage_id and sn != stage_id and not sn.startswith(stage_id):
                continue
            if keyword:
                blob = json.dumps(content, ensure_ascii=False)
                if keyword not in blob and keyword not in title:
                    continue
            hits.append(
                {
                    "id": item["id"],
                    "hot_score": item.get("hot_score"),
                    "stage_name": sn,
                    "title": title,
                    "uploader": item.get("uploader", ""),
                    "views": item.get("views"),
                }
            )
            if len(hits) >= limit:
                return hits
        if not data["data"].get("has_next"):
            break
    return hits


def main() -> int:
    p = argparse.ArgumentParser(description="Search PRTS Copilot jobs")
    p.add_argument("--stage", help="显示名如 AD-1，或 stage_name 如 act43side_01")
    p.add_argument("--keyword", help="标题/内容关键词")
    p.add_argument("--activity", default="act43side", help="仅作提示；配合 --stage 映射")
    p.add_argument("--id", type=int, help="直接查作业 ID")
    p.add_argument("--pages", type=int, default=10)
    p.add_argument("--limit", type=int, default=15)
    p.add_argument("--validate", action="store_true", help="拉取并校验 actions")
    p.add_argument("--download", action="store_true")
    p.add_argument("--out-dir", default="/tmp/prts-search-jobs")
    args = p.parse_args()

    if args.id:
        raw = http_get(f"/copilot/get/{args.id}")
        content = json.loads(raw["data"]["content"])
        errs = validate_job(content)
        print(json.dumps({
            "id": args.id,
            "stage_name": content.get("stage_name"),
            "title": (content.get("doc") or {}).get("title"),
            "actions": len(content.get("actions") or []),
            "opers": len(content.get("opers") or []),
            "validation": errs or ["ok"],
        }, ensure_ascii=False, indent=2))
        if args.download:
            out = Path(args.out_dir)
            out.mkdir(parents=True, exist_ok=True)
            label = next((k for k, v in ACT43.items() if v == content.get("stage_name")), content.get("stage_name", "job"))
            path = out / f"{args.id}_{label}.json"
            path.write_text(json.dumps(content, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"saved {path}")
        return 0 if not any(e.startswith("missing") for e in errs) else 1

    stage_id = None
    if args.stage:
        stage_id = ACT43.get(args.stage, args.stage)

    hits = search(stage_id=stage_id, keyword=args.keyword, pages=args.pages, limit=args.limit)
    if not hits:
        print("no hits", file=sys.stderr)
        return 1

    for h in hits:
        print(f"{h['id']:>8}  hot={h.get('hot_score')}  {h['stage_name']:<18}  {h['title'][:60]}")

    if args.validate or args.download:
        out = Path(args.out_dir)
        out.mkdir(parents=True, exist_ok=True)
        for h in hits[:5]:
            content = json.loads(http_get(f"/copilot/get/{h['id']}")["data"]["content"])
            errs = validate_job(content)
            print(f"  validate {h['id']}: {errs or ['ok']}")
            if args.download and not any(e.startswith("missing") for e in errs):
                label = next((k for k, v in ACT43.items() if v == h["stage_name"]), h["stage_name"])
                path = out / f"{h['id']}_{label}.json"
                path.write_text(json.dumps(content, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"  saved {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
