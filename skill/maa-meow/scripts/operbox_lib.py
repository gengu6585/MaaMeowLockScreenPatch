#!/usr/bin/env python3
"""OperBox 干员库：解析 MaaCore 日志、持久化、作业干员匹配。"""
from __future__ import annotations

import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_OPERBOX = Path(__file__).resolve().parent.parent / "data" / "operbox.json"


def _parse_json_from_log_line(line: str) -> dict | None:
    """从 asst.log 单行（SubTaskExtraInfo {...}）解析 JSON 对象。"""
    start = line.find("{")
    if start < 0:
        return None
    depth = 0
    end = -1
    for i, ch in enumerate(line[start:], start):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end < 0:
        return None
    try:
        return json.loads(line[start:end])
    except json.JSONDecodeError:
        return None


def extract_operbox_callbacks(text: str) -> list[dict]:
    """从 asst.log 文本中提取 OperBoxInfo 回调 details。"""
    out: list[dict] = []
    for line in text.splitlines():
        if "OperBoxRecognitionTask" not in line and "OperBoxInfo" not in line:
            continue
        if "SubTaskExtraInfo" not in line and "OperBoxInfo" not in line:
            continue
        obj = _parse_json_from_log_line(line)
        if not obj:
            continue
        if obj.get("what") != "OperBoxInfo":
            continue
        details = obj.get("details")
        if isinstance(details, dict) and ("own_opers" in details or "all_opers" in details):
            out.append(details)
    return out


def parse_operbox_from_log(text: str) -> dict[str, Any] | None:
    """解析 asst.log，返回标准化 operbox 记录；优先 done=true 的最后一条。"""
    cbs = extract_operbox_callbacks(text)
    if not cbs:
        return None

    chosen = None
    for cb in reversed(cbs):
        if cb.get("done"):
            chosen = cb
            break
    if chosen is None:
        chosen = cbs[-1]

    owned: list[dict[str, Any]] = []
    seen: set[str] = set()

    for op in chosen.get("own_opers") or []:
        name = (op.get("name") or "").strip()
        if not name or name in seen:
            continue
        seen.add(name)
        owned.append(
            {
                "id": op.get("id", ""),
                "name": name,
                "elite": op.get("elite", 0),
                "level": op.get("level", 0),
                "potential": op.get("potential", 0),
                "rarity": op.get("rarity", 0),
            }
        )

    # done=true 时 all_opers 含完整 own 标记，可补全
    if chosen.get("done") and chosen.get("all_opers"):
        owned = []
        seen = set()
        for op in chosen["all_opers"]:
            if not op.get("own"):
                continue
            name = (op.get("name") or "").strip()
            if not name or name in seen:
                continue
            seen.add(name)
            owned.append(
                {
                    "id": op.get("id", ""),
                    "name": name,
                    "elite": op.get("elite", 0),
                    "level": op.get("level", 0),
                    "potential": op.get("potential", 0),
                    "rarity": op.get("rarity", 0),
                }
            )

    if not owned:
        return None

    owned.sort(key=lambda x: (-int(x.get("rarity") or 0), x["name"]))
    return {
        "sync_time": datetime.now(timezone.utc).isoformat(),
        "done": bool(chosen.get("done")),
        "owned_count": len(owned),
        "owned_names": [o["name"] for o in owned],
        "owned": owned,
    }


def load_operbox(path: Path | str | None = None) -> dict[str, Any] | None:
    p = Path(path) if path else DEFAULT_OPERBOX
    if not p.is_file():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None


def save_operbox(data: dict[str, Any], path: Path | str | None = None) -> Path:
    p = Path(path) if path else DEFAULT_OPERBOX
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    return p


def job_required_groups(content: dict) -> tuple[list[str], list[list[str]]]:
    """返回 (固定干员名列表, 干员组列表)。每组至少持有其一。"""
    fixed: list[str] = []
    for op in content.get("opers") or []:
        name = (op.get("name") or "").strip()
        if name:
            fixed.append(name)
    groups: list[list[str]] = []
    for grp in content.get("groups") or []:
        names = [(o.get("name") or "").strip() for o in grp.get("opers") or []]
        names = [n for n in names if n]
        if names:
            groups.append(names)
    return fixed, groups


def job_missing_operators(content: dict, owned_names: set[str]) -> list[str]:
    """缺哪些干员无法编队；干员组缺整组时列出组内全部候选。"""
    missing: list[str] = []
    fixed, groups = job_required_groups(content)
    for name in fixed:
        if name not in owned_names:
            missing.append(name)
    for grp in groups:
        if not any(n in owned_names for n in grp):
            missing.extend(grp)
    # 去重保序
    seen: set[str] = set()
    out: list[str] = []
    for n in missing:
        if n not in seen:
            seen.add(n)
            out.append(n)
    return out


def job_is_runnable(content: dict, owned_names: set[str]) -> bool:
    return len(job_missing_operators(content, owned_names)) == 0


def owned_name_set(data: dict[str, Any] | None) -> set[str]:
    if not data:
        return set()
    names = data.get("owned_names")
    if isinstance(names, list) and names:
        return set(names)
    return {o.get("name", "") for o in data.get("owned") or [] if o.get("name")}


def main() -> int:
    import argparse

    p = argparse.ArgumentParser(description="OperBox 干员库工具")
    sub = p.add_subparsers(dest="cmd")

    p_parse = sub.add_parser("parse", help="从 asst.log 解析并保存")
    p_parse.add_argument("log_file", help="asst.log 路径")
    p_parse.add_argument("--out", default=str(DEFAULT_OPERBOX))

    p_check = sub.add_parser("check-job", help="检查作业是否缺干员")
    p_check.add_argument("job_json")
    p_check.add_argument("--operbox", default=str(DEFAULT_OPERBOX))

    p_show = sub.add_parser("show", help="显示已存干员库摘要")
    p_show.add_argument("--operbox", default=str(DEFAULT_OPERBOX))
    p_show.add_argument("--limit", type=int, default=20)

    args = p.parse_args()
    if args.cmd == "parse":
        text = Path(args.log_file).read_text(encoding="utf-8", errors="replace")
        data = parse_operbox_from_log(text)
        if not data:
            print("no OperBoxInfo in log", file=sys.stderr)
            return 1
        out = save_operbox(data, args.out)
        print(f"saved {out} owned={data['owned_count']} done={data['done']}")
        return 0

    if args.cmd == "check-job":
        ob = load_operbox(args.operbox)
        if not ob:
            print(f"operbox not found: {args.operbox}", file=sys.stderr)
            return 1
        content = json.loads(Path(args.job_json).read_text(encoding="utf-8"))
        owned = owned_name_set(ob)
        miss = job_missing_operators(content, owned)
        if miss:
            print("missing:", ", ".join(miss))
            return 1
        print("ok — all operators available")
        return 0

    if args.cmd == "show":
        ob = load_operbox(args.operbox)
        if not ob:
            print(f"no operbox at {args.operbox}", file=sys.stderr)
            return 1
        print(f"sync_time={ob.get('sync_time')} owned={ob.get('owned_count')} done={ob.get('done')}")
        for name in (ob.get("owned_names") or [])[: args.limit]:
            print(f"  {name}")
        rest = (ob.get("owned_count") or 0) - args.limit
        if rest > 0:
            print(f"  ... +{rest} more")
        return 0

    p.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
