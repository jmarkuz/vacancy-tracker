#!/usr/bin/env bash
# Fails when PLAN.md and tasks.csv disagree on task IDs or statuses.
# PLAN.md Done column: "[x] date" = done, "[ ] blocked" = blocked, "[ ]" = todo.
# Usage: scripts/check-plan-sync.sh   (run from anywhere)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

plan=$(awk -F'|' '$3 ~ /^ *T-[0-9]+ *$/ {
  d=$2; id=$3; gsub(/ /,"",id)
  s = (d ~ /\[x\]/) ? "done" : (d ~ /blocked/) ? "blocked" : "todo"
  print id "," s }' "$root/PLAN.md" | sort)
csv=$(awk -F, 'NR>1 && $1 ~ /^T-[0-9]+$/ {print $1 "," $NF}' "$root/tasks.csv" | sort)

if diff <(printf '%s\n' "$plan") <(printf '%s\n' "$csv") >/dev/null; then
  echo "PLAN.md and tasks.csv agree ($(printf '%s\n' "$plan" | wc -l) tasks)."
else
  echo "PLAN.md (<) and tasks.csv (>) disagree:" >&2
  diff <(printf '%s\n' "$plan") <(printf '%s\n' "$csv") >&2 || true
  exit 1
fi
