import argparse
import json
import re
import sys
import time
from pathlib import Path
from typing import List, Dict, Any, Tuple, Optional, Union
from dataclasses import dataclass, asdict
from collections import Counter

# ---------------------------------------------------------
# Domain Objects & Data Structures
# ---------------------------------------------------------

@dataclass
class MetricResult:
    """Holds the calculated metrics for a single prediction entry."""
    exact_match: int
    edit_similarity: float
    token_precision: float
    token_recall: float
    token_f1: float

    @classmethod
    def empty(cls):
        """Returns a zeroed-out metric result for failures/empty predictions."""
        return cls(0, 0.0, 0.0, 0.0, 0.0)

# ---------------------------------------------------------
# Utility Classes
# ---------------------------------------------------------

class ConsoleProgressBar:
    """Manages a terminal progress bar to visualize processing speed."""

    def __init__(self, total_items: int, bar_length: int = 50):
        self.total_items = total_items
        self.bar_length = bar_length
        self.start_time = time.time()

    def update(self, current_item_index: int):
        if self.total_items == 0:
            return

        percent_complete = float(current_item_index) / self.total_items
        arrow = '#' * int(round(percent_complete * self.bar_length))
        spaces = '-' * (self.bar_length - len(arrow))
        elapsed_time = time.time() - self.start_time
        
        sys.stdout.write(f"\rProgress: [{arrow + spaces}] {int(percent_complete * 100)}% "
                         f"({current_item_index}/{self.total_items}) "
                         f"- Time Elapsed: {elapsed_time:.2f}s")
        sys.stdout.flush()

    def finish(self):
        sys.stdout.write("\n")

class IdentifierTokenizer:
    """Responsible for splitting code identifiers into semantic sub-tokens."""

    @staticmethod
    def tokenize(identifier: str) -> List[str]:
        """
        Splits identifier into tokens using CamelCase, SnakeCase, and delimiter rules.
        Supports Unicode identifiers.
        """
        if not identifier:
            return []
        
        # 1. Replace non-word characters (anything not letter, number, or underscore) with space
        # This keeps letters intact while removing punctuation like $, -, .
        clean_identifier = re.sub(r'[^\w]', ' ', identifier)
        
        # 2. Replace snake_case underscores with spaces
        clean_identifier = clean_identifier.replace("_", " ")
        
        # 3. Handle Acronyms in PascalCase (e.g., XMLParser -> XML Parser)
        clean_identifier = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1 \2', clean_identifier)
        
        # 4. Handle standard CamelCase (e.g., myVar -> my Var)
        clean_identifier = re.sub(r'([a-z0-9])([A-Z])', r'\1 \2', clean_identifier)
        
        # 5. Handle Number/Letter transitions (e.g., file123 -> file 123)
        clean_identifier = re.sub(r'([0-9])([a-zA-Z])', r'\1 \2', clean_identifier)
        clean_identifier = re.sub(r'([a-zA-Z])([0-9])', r'\1 \2', clean_identifier)
        
        # Return list of lowercased tokens (splitting by whitespace)
        return [token.lower() for token in clean_identifier.split()]

class StringDistanceCalculator:
    """Utility class for calculating string edit distances and similarities."""

    @staticmethod
    def calculate_levenshtein_distance(s1: str, s2: str) -> int:
        if len(s1) < len(s2):
            return StringDistanceCalculator.calculate_levenshtein_distance(s2, s1)

        if len(s2) == 0:
            return len(s1)

        previous_row = range(len(s2) + 1)
        for i, c1 in enumerate(s1):
            current_row = [i + 1]
            for j, c2 in enumerate(s2):
                insertions = previous_row[j + 1] + 1
                deletions = current_row[j] + 1
                substitutions = previous_row[j] + (c1 != c2)
                current_row.append(min(insertions, deletions, substitutions))
            previous_row = current_row
        return previous_row[-1]

    @staticmethod
    def calculate_similarity(s1: str, s2: str) -> float:
        """Returns a normalized similarity score between 0.0 and 1.0."""
        if s1 == s2: return 1.0
        max_length = max(len(s1), len(s2))
        if max_length == 0: return 1.0 
        distance = StringDistanceCalculator.calculate_levenshtein_distance(s1, s2)
        return 1.0 - (distance / max_length)

# ---------------------------------------------------------
# Core Evaluation Logic
# ---------------------------------------------------------

class RenamePredictionEvaluator:
    """Evaluates a single renaming prediction against the ground truth."""

    def __init__(self, ground_truth: str, prediction: str):
        self.ground_truth = ground_truth
        self.prediction = prediction
        
        gt_tokens_list = IdentifierTokenizer.tokenize(self.ground_truth)
        pred_tokens_list = IdentifierTokenizer.tokenize(self.prediction)

        self._gt_counts = Counter(gt_tokens_list)
        self._pred_counts = Counter(pred_tokens_list)
        
        self._intersection = self._gt_counts & self._pred_counts
        
        self._intersection_total = sum(self._intersection.values())
        self._pred_total = sum(self._pred_counts.values())
        self._gt_total = sum(self._gt_counts.values())

    def evaluate(self) -> MetricResult:
        return MetricResult(
            exact_match=self._calculate_exact_match(),
            edit_similarity=self._calculate_edit_similarity(),
            token_precision=self._calculate_token_precision(),
            token_recall=self._calculate_token_recall(),
            token_f1=self._calculate_token_f1()
        )

    def _calculate_exact_match(self) -> int:
        return 1 if self.ground_truth == self.prediction else 0

    def _calculate_edit_similarity(self) -> float:
        return StringDistanceCalculator.calculate_similarity(self.ground_truth, self.prediction)

    def _calculate_token_precision(self) -> float:
        if self._pred_total == 0: return 0.0
        return self._intersection_total / self._pred_total

    def _calculate_token_recall(self) -> float:
        if self._gt_total == 0: return 0.0
        return self._intersection_total / self._gt_total

    def _calculate_token_f1(self) -> float:
        # If both strings are empty, they are semantically identical
        if self._gt_total == 0 and self._pred_total == 0:
            return 1.0
            
        p = self._calculate_token_precision()
        r = self._calculate_token_recall()
        if (p + r) == 0: return 0.0
        return 2 * (p * r) / (p + r)

class BatchResultProcessor:
    """Processes JSON entries, injects metrics, and calculates aggregates."""

    def __init__(self, json_data: Dict[str, Any]):
        self.full_json_data = json_data
        self.results = json_data.get("results", [])
        self.total_items = len(self.results)
        
        self.accumulators = {
            "top_1": self._init_zero_metrics(),
            "top_5": self._init_zero_metrics()
        }

    def _init_zero_metrics(self) -> Dict[str, Union[int, float]]:
        return {
            "exact_match": 0,
            "edit_similarity": 0.0,
            "token_precision": 0.0,
            "token_recall": 0.0,
            "token_f1": 0.0
        }

    def process(self) -> Tuple[Dict[str, Any], Dict[str, Any]]:
        print(f"Starting analysis of {self.total_items} items...")
        progress_bar = ConsoleProgressBar(self.total_items)
        
        for index, item in enumerate(self.results):
            # Handles explicitly null values and prevents coercion of 0 to empty string
            raw_gt_val = item.get("new_name")
            ground_truth = str(raw_gt_val) if raw_gt_val is not None else ""
            
            raw_suggestions = item.get("suggestions", [])
            
            # --- 1. Top-1 Calculation (Strict) ---
            top_1_metric = MetricResult.empty()
            if isinstance(raw_suggestions, list) and len(raw_suggestions) > 0:
                first_suggestion = raw_suggestions[0]
                if self._is_valid_suggestion(first_suggestion, index, 0, warn=True):
                    pred_name = str(first_suggestion["name"])
                    top_1_metric = RenamePredictionEvaluator(ground_truth, pred_name).evaluate()

            # --- 2. Top-5 Calculation (Oracle/Best-of-N) ---
            valid_candidates = self._extract_valid_candidates(raw_suggestions, item_index=index, limit=5)
            
            top_5_results: List[MetricResult] = []
            for pred in valid_candidates:
                evaluator = RenamePredictionEvaluator(ground_truth, pred)
                top_5_results.append(evaluator.evaluate())

            top_5_metric = self._determine_best_candidate(top_5_results)

            # --- 3. Update & Store ---
            item["performance_metrics"] = {
                "top_1_performance_metrics": asdict(top_1_metric),
                "top_5_performance_metrics": asdict(top_5_metric)
            }

            self._update_accumulator("top_1", top_1_metric)
            self._update_accumulator("top_5", top_5_metric)
            
            progress_bar.update(index + 1)

        progress_bar.finish()
        
        aggregates = self._calculate_final_averages()
        final_output = {"aggregate_performance_metrics": aggregates}

        return final_output, aggregates

    def _is_valid_suggestion(self, suggestion: Any, item_index: int, sugg_index: int, warn: bool = False) -> bool:
        """Helper to validate the structure of a suggestion dictionary."""
        if isinstance(suggestion, dict) and "name" in suggestion:
            if suggestion["name"] is not None:
                return True
            if warn:
                print(f"\n[Warning] Null name found in suggestions at item {item_index}, suggestion {sugg_index}.")
        elif warn:
            print(f"\n[Warning] Malformed suggestion found at item {item_index}, suggestion {sugg_index}.")
        return False

    def _extract_valid_candidates(self, suggestions: Any, item_index: int, limit: int = 5) -> List[str]:
        """Safely extracts valid name strings from the raw suggestion list."""
        valid_names = []
        if not isinstance(suggestions, list):
            return valid_names

        for i, sugg in enumerate(suggestions[:limit]):
            if self._is_valid_suggestion(sugg, item_index, i, warn=False):
                valid_names.append(str(sugg["name"]))
        
        return valid_names

    def _determine_best_candidate(self, results: List[MetricResult]) -> MetricResult:
        """
        Finds the best result from candidates (Oracle selection).
        Priority: Token F1 > Exact Match > Edit Similarity > Token Precision.
        """
        if not results:
            return MetricResult.empty()
        
        return max(
            results,
            key=lambda res: (
                res.token_f1, 
                res.exact_match, 
                res.edit_similarity, 
                res.token_precision
            )
        )

    def _update_accumulator(self, key: str, metric: MetricResult):
        acc = self.accumulators[key]
        acc["exact_match"] += metric.exact_match
        acc["edit_similarity"] += metric.edit_similarity
        acc["token_precision"] += metric.token_precision
        acc["token_recall"] += metric.token_recall
        acc["token_f1"] += metric.token_f1

    def _calculate_final_averages(self) -> Dict[str, Any]:
        """Calculates averages, handling division by zero for empty datasets."""
        if self.total_items == 0:
            zero_metrics = self._init_zero_metrics()
            return {
                "top_1_performance_metrics": zero_metrics,
                "top_5_performance_metrics": zero_metrics
            }
        
        def get_avg_dict(key: str) -> Dict[str, float]:
            return {k: v / self.total_items for k, v in self.accumulators[key].items()}

        return {
            "top_1_performance_metrics": get_avg_dict("top_1"),
            "top_5_performance_metrics": get_avg_dict("top_5")
        }

# ---------------------------------------------------------
# Application Entry Point
# ---------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Calculate metrics and inject them into JSON.")
    parser.add_argument("file_path", type=str, help="Path to the JSON results file.")
    args = parser.parse_args()

    json_file_path = Path(args.file_path).resolve()

    if not json_file_path.exists():
        print(f"Error: The file '{json_file_path}' does not exist.")
        sys.exit(1)

    try:
        with open(json_file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            
            if "results" not in data:
                raise ValueError("JSON file must contain a root 'results' array.")

            processor = BatchResultProcessor(data)
            final_json_structure, averages = processor.process()

            print("\n--- Aggregate Performance Metrics ---")
            print(json.dumps(averages, indent=4))

            output_file_name = f"{json_file_path.stem}_with_performance_metrics.json"
            output_file_path = json_file_path.with_name(output_file_name)
            
            with open(output_file_path, 'w', encoding='utf-8') as out_f:
                json.dump(final_json_structure, out_f, indent=4)

            print(f"\nDetailed results saved to: {output_file_path}")

    except json.JSONDecodeError:
        print("Error: Failed to decode JSON.")
    except Exception as e:
        import traceback
        traceback.print_exc()
        print(f"An unexpected error occurred: {e}")

if __name__ == "__main__":
    main()