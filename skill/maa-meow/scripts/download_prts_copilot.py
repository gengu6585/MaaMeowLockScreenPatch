#!/usr/bin/env python3
"""从 prts.maa.plus (prts.plus 后端) 下载 MAA Copilot 作业 JSON。

API:
  - 列表: GET https://prts.maa.plus/copilot/query?page=1&limit=50&orderBy=hot&desc=true
  - 单条: GET https://prts.maa.plus/copilot/get/{id}
  - 关卡: GET https://prts.maa.plus/arknights/level
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://prts.maa.plus"

# 红丝绒 act43side：stage_name → 显示名
ACT43SIDE_STAGES = {
    "act43side_01": "AD-1",
    "act43side_02": "AD-2",
    "act43side_03": "AD-3",
    "act43side_04": "AD-4",
    "act43side_05": "AD-5",
    "act43side_06": "AD-6",
    "act43side_07": "AD-7",
    "act43side_08": "AD-8",
    "act43side_ex01": "AD-EX-1",
    "act43side_ex02": "AD-EX-2",
    "act43side_ex03": "AD-EX-3",
    "act43side_ex04": "AD-EX-4",
    "act43side_ex05": "AD-EX-5",
    "act43side_ex06": "AD-EX-6",
    "act43side_ex07": "AD-EX-7",
    "act43side_ex08": "AD-EX-8",
}

# 可选：固定 ID（同一作者系列，挂机更一致）
TRYUHARK_IDS = {
    "act43side_01": 97725,
    "act43side_02": 97726,
    "act43side_03": 97727,
    "act43side_04": 97728,
    "act43side_05": 97729,
    "act43side_06": 97731,
    "act43side_07": 97732,
    "act43side_08": 97733,
    "act43side_ex01": 97927,
    "act43side_ex02": 97735,
    "act43side_ex03": 97736,
    "act43side_ex04": 97738,
    "act43side_ex05": 97739,
    "act43side_ex06": 97740,
    "act43side_ex07": 97741,
    "act43side_ex08": 97951,
}


def http_get(path: str, params: dict | None = None, retries: int = 3) -> dict:
    url = f"{API}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "maa-meow-skill/1.0"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.load(resp)
            if data.get("status_code") != 200:
                raise RuntimeError(f"API error {path}: {data.get('message', data)}")
            return data
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            last_err = e
            if attempt + 1 < retries:
                import time

                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"GET {url} failed after {retries} tries: {last_err}") from last_err


def discover_jobs(stage_prefix: str, max_pages: int = 50) -> dict[str, tuple[int, float, str]]:
    """按热度扫描列表，每个 stage_name 取 hot_score 最高的一条。"""
    best: dict[str, tuple[int, float, str]] = {}
    page = 1
    while page <= max_pages:
        data = http_get(
            "/copilot/query",
            {"page": page, "limit": 50, "desc": "true", "orderBy": "hot"},
        )
        for item in data["data"]["data"]:
            content = json.loads(item["content"])
            sn = content.get("stage_name", "")
            if not sn.startswith(stage_prefix):
                continue
            score = float(item.get("hot_score", 0))
            title = content.get("doc", {}).get("title", "")
            prev = best.get(sn)
            if prev is None or score > prev[1]:
                best[sn] = (item["id"], score, title)
        if not data["data"].get("has_next"):
            break
        page += 1
    return best


def fetch_job(job_id: int) -> dict:
    data = http_get(f"/copilot/get/{job_id}")
    content = json.loads(data["data"]["content"])
    if not content.get("actions"):
        raise RuntimeError(f"job {job_id} has no actions — unusable for MAA")
    return content


def main() -> int:
    parser = argparse.ArgumentParser(description="Download Copilot jobs from prts.maa.plus")
    parser.add_argument(
        "--activity",
        default="act43side",
        help="stage_name 前缀，默认红丝绒 act43side",
    )
    parser.add_argument(
        "--stages",
        help="逗号分隔关卡名，如 AD-1,AD-EX-8；默认该 activity 全部 16 关",
    )
    parser.add_argument(
        "--out-dir",
        default="/tmp/arknights-ad-jobs",
        help="输出目录（JSON + manifest.json）",
    )
    parser.add_argument(
        "--preset",
        choices=("hot", "tryuhark"),
        default="hot",
        help="hot=按热度自动选；tryuhark=固定 Tryuhark 系列 ID",
    )
    parser.add_argument(
        "--id",
        action="append",
        default=[],
        metavar="STAGE=JOB_ID",
        help="覆盖某关作业 ID，如 AD-5=96235",
    )
    args = parser.parse_args()

    stage_map = ACT43SIDE_STAGES if args.activity == "act43side" else {}
    if not stage_map:
        # 通用：从 level API 构建 cat_three → stage_id
        levels = http_get("/arknights/level")["data"]
        stage_map = {}
        for lv in levels:
            sid = lv.get("stage_id", "")
            cat = lv.get("cat_three", "")
            if sid.startswith(args.activity) and cat and "#" not in cat:
                stage_map[sid] = cat

    label_to_stage = {v: k for k, v in stage_map.items()}
    if args.stages:
        wanted_labels = [s.strip() for s in args.stages.split(",") if s.strip()]
    else:
        wanted_labels = list(stage_map.values())

    overrides: dict[str, int] = {}
    for spec in args.id:
        label, jid = spec.split("=", 1)
        overrides[label.strip()] = int(jid.strip())

    discovered = discover_jobs(args.activity) if args.preset == "hot" else {}

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest: dict[str, dict] = {}

    for label in wanted_labels:
        stage_name = label_to_stage.get(label)
        if not stage_name:
            print(f"WARN: unknown stage label {label}", file=sys.stderr)
            continue

        if label in overrides:
            job_id = overrides[label]
            title = f"override id={job_id}"
        elif args.preset == "tryuhark" and stage_name in TRYUHARK_IDS:
            job_id = TRYUHARK_IDS[stage_name]
            title = f"preset tryuhark id={job_id}"
        elif stage_name in discovered:
            job_id, _score, title = discovered[stage_name]
        else:
            print(f"WARN: no job found for {label} ({stage_name})", file=sys.stderr)
            continue

        content = fetch_job(job_id)
        fname = f"{job_id}_{label}.json"
        out_path = out_dir / fname
        out_path.write_text(json.dumps(content, ensure_ascii=False, indent=2), encoding="utf-8")
        manifest[label] = {
            "id": job_id,
            "file": fname,
            "stage_name": stage_name,
            "title": content.get("doc", {}).get("title", title),
        }
        print(f"OK {label} id={job_id} -> {fname}")

    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"manifest: {manifest_path} ({len(manifest)} jobs)")
    return 0 if manifest else 1


if __name__ == "__main__":
    raise SystemExit(main())
