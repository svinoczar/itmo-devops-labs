#!/usr/bin/env python3

import csv
import pathlib
import sys


source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = []

with source.open(encoding="utf-8") as stream:
    for row in csv.reader(stream):
        if len(row) != 3 or not row[0].isdigit():
            continue
        rows.append(tuple(map(int, row)))

if not rows:
    raise SystemExit("continuity log does not contain measurements")

width, height = 1200, 360
left, top, right, bottom = 70, 35, 25, 55
plot_width = width - left - right
plot_height = height - top - bottom
maximum = max(max(ok, failed, 1) for _, ok, failed in rows)


def points(column: int) -> str:
    result = []
    for index, row in enumerate(rows):
        x = left + index * plot_width / max(len(rows) - 1, 1)
        y = top + plot_height - row[column] * plot_height / maximum
        result.append(f"{x:.1f},{y:.1f}")
    return " ".join(result)


svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
<rect width="100%" height="100%" fill="#111827"/>
<text x="{left}" y="22" fill="#f9fafb" font-family="sans-serif" font-size="16">Shop continuity while API/worker Pods are deleted</text>
<line x1="{left}" y1="{top + plot_height}" x2="{left + plot_width}" y2="{top + plot_height}" stroke="#6b7280"/>
<line x1="{left}" y1="{top}" x2="{left}" y2="{top + plot_height}" stroke="#6b7280"/>
<polyline points="{points(1)}" fill="none" stroke="#22c55e" stroke-width="2"/>
<polyline points="{points(2)}" fill="none" stroke="#ef4444" stroke-width="2"/>
<text x="{left}" y="{height - 18}" fill="#9ca3af" font-family="sans-serif" font-size="12">measurements: {len(rows)}</text>
<text x="{width - 210}" y="22" fill="#22c55e" font-family="sans-serif" font-size="12">success</text>
<text x="{width - 125}" y="22" fill="#ef4444" font-family="sans-serif" font-size="12">failed</text>
</svg>'''

target.write_text(svg, encoding="utf-8")
print(target)
