#!/usr/bin/env bash

# Usage:
#   ./run_metrics.sh /path/to/experiments
#
# The directory structure is assumed like:
#   experiments/
#       run1/
#           strategyA/
#               results_strategyA.json
#           strategyB/
#               results_strategyB.json
#       run2/
#           strategyA/
#               results_strategyA.json

ROOT_DIR="$1"

if [ -z "$ROOT_DIR" ]; then
    echo "Usage: $0 <experiments_root_folder>"
    exit 1
fi

echo "Scanning: $ROOT_DIR"

# Find all results_[strategy].json files inside two-level deep subfolders
find "$ROOT_DIR" -type f -name 'results_*.json' | while read -r jsonfile; do
    echo "------------------------------------------------------------"
    echo "Processing: $jsonfile"
    python data/calculate_performance_metrics.py "$jsonfile"
done
