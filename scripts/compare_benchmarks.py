#!/usr/bin/env python3
"""
Ariadne Benchmark Comparator
Compares JMH benchmark results between the current PR/branch and the 'main' branch baseline.
Outputs a detailed Markdown comparison table for GitHub Step Summaries and PR comments.
"""

import json
import sys
import os
import argparse

def parse_jmh_json(file_path):
    if not os.path.exists(file_path):
        return {}

    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    benchmarks = {}
    for item in data:
        name = item["benchmark"].split(".")[-1]
        param = item.get("params", {}).get("mode", "")
        key = f"{name} ({param})" if param and param != "-" else name
        
        score = item["primaryMetric"]["score"]
        unit = item["primaryMetric"].get("scoreUnit", "us/op")
        
        alloc = None
        secondary = item.get("secondaryMetrics", {})
        for skey, sval in secondary.items():
            if "gc.alloc.rate.norm" in skey:
                alloc = sval["score"]
                break

        benchmarks[key] = {
            "score": score,
            "unit": unit,
            "alloc": alloc
        }
    return benchmarks

def compare_benchmarks(current_file, baseline_file, regression_threshold_pct=15.0):
    current = parse_jmh_json(current_file)
    baseline = parse_jmh_json(baseline_file)

    if not current:
        return " Error: Current benchmark results not found.", False

    lines = []
    lines.append("##  Ariadne JMH Benchmark Comparison vs `main`")
    lines.append("")

    if not baseline:
        lines.append(" *No previous baseline from `main` found. Establishing initial baseline:*")
        lines.append("")
        lines.append("| Benchmark | Score | Memory Alloc |")
        lines.append("| :--- | :--- | :--- |")
        for k, v in current.items():
            alloc_str = f"{v['alloc']:.1f} B/op" if v["alloc"] is not None else "-"
            lines.append(f"| `{k}` | **{v['score']:.3f} {v['unit']}** | {alloc_str} |")
        return "\n".join(lines), True

    lines.append("| Benchmark | Baseline (`main`) | Current Branch | Latency Delta | GC Alloc (Base ➔ Curr) | Status |")
    lines.append("| :--- | :--- | :--- | :--- | :--- | :--- |")

    has_regression = False

    for k, curr in current.items():
        base = baseline.get(k)
        if not base:
            alloc_str = f"{curr['alloc']:.1f} B/op" if curr["alloc"] is not None else "-"
            lines.append(f"| `{k}` | *(new)* | **{curr['score']:.3f} {curr['unit']}** | - | {alloc_str} | `[NEW]` |")
            continue

        base_score = base["score"]
        curr_score = curr["score"]
        delta_pct = ((curr_score - base_score) / base_score) * 100.0 if base_score > 0 else 0.0

        base_alloc = base.get("alloc")
        curr_alloc = curr.get("alloc")
        if base_alloc is not None and curr_alloc is not None:
            alloc_diff = f"{base_alloc:.1f} ➔ {curr_alloc:.1f} B"
        elif curr_alloc is not None:
            alloc_diff = f"{curr_alloc:.1f} B"
        else:
            alloc_diff = "-"

        if delta_pct > regression_threshold_pct:
            status = " **REGRESSION**"
            has_regression = True
        elif delta_pct < -5.0:
            status = " **IMPROVED**"
        else:
            status = " **PARITY**"

        delta_sign = "+" if delta_pct > 0 else ""
        delta_str = f"{delta_sign}{delta_pct:.2f}%"

        lines.append(f"| `{k}` | {base_score:.3f} {base['unit']} | {curr_score:.3f} {curr['unit']} | **{delta_str}** | {alloc_diff} | {status} |")

    lines.append("")
    lines.append(f"> *Regression threshold configured at **+{regression_threshold_pct:.1f}%**. Measured with JDK 21 HotSpot (`-prof gc`).*")
    return "\n".join(lines), not has_regression

def main():
    parser = argparse.ArgumentParser(description="Compare current JMH benchmark results against main baseline.")
    parser.add_argument("current", help="Path to current benchmark-results.json")
    parser.add_argument("baseline", nargs="?", default="benchmark-baseline-main.json", help="Path to baseline benchmark JSON from main")
    parser.add_argument("--threshold", type=float, default=15.0, help="Regression threshold percentage (default: 15.0)")
    parser.add_argument("--fail-on-regression", action="store_true", help="Exit with non-zero status if regression is detected")

    args = parser.parse_args()

    report, success = compare_benchmarks(args.current, args.baseline, args.threshold)
    print(report)

    if args.fail_on_regression and not success:
        sys.exit(1)

if __name__ == "__main__":
    main()
