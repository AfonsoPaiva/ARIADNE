#!/usr/bin/env python3
"""
Ariadne Benchmark Table Generator
Parses JMH JSON benchmark results (e.g., benchmark-results.json produced with -rf json and -prof gc)
and generates clean Markdown and HTML tables for releases, documentation, and CI summaries.
"""

import json
import sys
import os
import argparse

def parse_benchmark_json(file_path):
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"Benchmark results file not found: {file_path}")

    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    rows = []
    for item in data:
        benchmark_name = item["benchmark"].split(".")[-1]
        params = item.get("params", {}).get("mode", "-")
        unit = item["primaryMetric"].get("scoreUnit", "us/op")
        score = item["primaryMetric"]["score"]
        
        err = item["primaryMetric"].get("scoreError")
        err_val = err if isinstance(err, (int, float)) else None
        
        alloc_val = None
        secondary = item.get("secondaryMetrics", {})
        for key, val in secondary.items():
            if "gc.alloc.rate.norm" in key:
                alloc_val = val["score"]
                break

        rows.append({
            "name": benchmark_name,
            "mode": params,
            "score": score,
            "unit": unit,
            "error": err_val,
            "alloc": alloc_val
        })
    return rows

def generate_markdown_table(rows):
    lines = []
    lines.append("## Ariadne JMH Benchmark Results")
    lines.append("")
    lines.append("| Benchmark | Mode / Param | Score | Error (99.9%) | Memory Alloc |")
    lines.append("| :--- | :--- | :--- | :--- | :--- |")

    for r in rows:
        score_str = f"**{r['score']:.3f} {r['unit']}**"
        err_str = f"± {r['error']:.3f}" if r["error"] is not None else "-"
        alloc_str = f"{r['alloc']:.1f} B/op" if r["alloc"] is not None else "-"
        lines.append(f"| `{r['name']}` | `{r['mode']}` | {score_str} | {err_str} | {alloc_str} |")

    lines.append("")
    lines.append("> *Results measured via OpenJDK 21 HotSpot with integrated GC profiler (`-prof gc`).*")
    return "\n".join(lines)

def generate_html_table(rows):
    lines = []
    lines.append('<div class="table-responsive">')
    lines.append('  <table class="table table-dark table-hover table-striped">')
    lines.append('    <thead>')
    lines.append('      <tr>')
    lines.append('        <th scope="col">Benchmark</th>')
    lines.append('        <th scope="col">Mode / Param</th>')
    lines.append('        <th scope="col">Score</th>')
    lines.append('        <th scope="col">Error (99.9%)</th>')
    lines.append('        <th scope="col">Memory Alloc</th>')
    lines.append('      </tr>')
    lines.append('    </thead>')
    lines.append('    <tbody>')

    for r in rows:
        score_str = f"{r['score']:.3f} {r['unit']}"
        err_str = f"&plusmn; {r['error']:.3f}" if r["error"] is not None else "-"
        alloc_str = f"{r['alloc']:.1f} B/op" if r["alloc"] is not None else "-"
        lines.append('      <tr>')
        lines.append(f'        <td><code>{r["name"]}</code></td>')
        lines.append(f'        <td><span class="badge bg-secondary">{r["mode"]}</span></td>')
        lines.append(f'        <td><strong>{score_str}</strong></td>')
        lines.append(f'        <td>{err_str}</td>')
        lines.append(f'        <td><code>{alloc_str}</code></td>')
        lines.append('      </tr>')

    lines.append('    </tbody>')
    lines.append('  </table>')
    lines.append('</div>')
    return "\n".join(lines)

def main():
    parser = argparse.ArgumentParser(description="Generate Markdown and HTML tables from JMH benchmark-results.json")
    parser.add_argument("input_file", nargs="?", default="benchmark-results.json", help="Path to JMH benchmark-results.json (default: benchmark-results.json)")
    parser.add_argument("--format", choices=["md", "html", "both"], default="md", help="Output format: md, html, or both (default: md)")
    parser.add_argument("--out-md", help="Path to write generated markdown table")
    parser.add_argument("--out-html", help="Path to write generated HTML table")

    args = parser.parse_args()

    rows = parse_benchmark_json(args.input_file)
    md_content = generate_markdown_table(rows)
    html_content = generate_html_table(rows)

    if args.out_md:
        with open(args.out_md, "w", encoding="utf-8") as f:
            f.write(md_content + "\n")
        print(f"[OK] Markdown table saved to {args.out_md}")

    if args.out_html:
        with open(args.out_html, "w", encoding="utf-8") as f:
            f.write(html_content + "\n")
        print(f"[OK] HTML table saved to {args.out_html}")

    if not args.out_md and not args.out_html:
        if args.format == "md":
            print(md_content)
        elif args.format == "html":
            print(html_content)
        else:
            print("=== MARKDOWN ===")
            print(md_content)
            print("\n=== HTML ===")
            print(html_content)

if __name__ == "__main__":
    main()
