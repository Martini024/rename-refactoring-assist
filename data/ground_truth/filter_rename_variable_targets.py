#!/usr/bin/env python3
import json
import sys
from pathlib import Path


def to_java_var_targets(items):
    targets = []
    for it in items:
        if it.get("type") != "Rename Variable":
            continue

        url = it.get("url_before_refactoring")
        insts = it.get("instance_location_before_refactoring") or []
        if not url or not insts:
            continue

        locators = []
        for inst in insts:
            line = inst.get("line")
            col = inst.get("start_column")
            locators.append({
                "line": line,
                "column": col,
            })

        targets.append({
            "path": url,
            "locators": locators,
            "old_name": it.get("old_name"),
            "new_name": it.get("new_name")
        })
    return targets

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {Path(sys.argv[0]).name} <input.json> <output.json>", file=sys.stderr)
        sys.exit(1)

    in_path = Path(sys.argv[1])
    out_path = Path(sys.argv[2])

    with in_path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    # Input can be a list or an object with a top-level list; assume list per your example
    if not isinstance(data, list):
        print("Input JSON must be an array of items.", file=sys.stderr)
        sys.exit(2)

    result = to_java_var_targets(data)

    with out_path.open("w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()
