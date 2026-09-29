#!/usr/bin/env python3
"""
Utility script to parse JMH benchmark results JSON and generate a GitHub Step Summary in Markdown.
"""

import json
import sys
import os

def format_summary(file_path):
    if not os.path.exists(file_path):
        return " No benchmark results found."

    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    lines = []
    lines.append("##  Ariadne JMH Benchmark Results")
    lines.append("")
    lines.append("| Benchmark | Mode / Param | Score | Error (99.9%) | Memory Alloc |")
    lines.append("| :--- | :--- | :--- | :--- | :--- |")

    for item in data:
        benchmark_name = item["benchmark"].split(".")[-1]
        params = item.get("params", {}).get("mode", "-")
        unit = item["primaryMetric"].get("scoreUnit", "us/op")
        score = f"{item['primaryMetric']['score']:.3f} {unit}"
        
        err = item["primaryMetric"].get("scoreError")
        err_str = f"± {err:.3f}" if isinstance(err, (int, float)) else "-"
        
        alloc_str = "-"
        secondary = item.get("secondaryMetrics", {})
        for key, val in secondary.items():
            if "gc.alloc.rate.norm" in key:
                alloc_str = f"{val['score']:.1f} B/op"
                break

        lines.append(f"| `{benchmark_name}` | `{params}` | **{score}** | {err_str} | {alloc_str} |")

    lines.append("")
    lines.append(">  *Results measured via OpenJDK 21 HotSpot with integrated GC profiler (`-prof gc`).*")
    return "\n".join(lines)

if __name__ == "__main__":
    target_file = sys.argv[1] if len(sys.argv) > 1 else "benchmark-results.json"
    print(format_summary(target_file))
