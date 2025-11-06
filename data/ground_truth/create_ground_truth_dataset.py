import csv
import json
import requests
import base64
import os
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple, Any

# ==============================================================================
# 1. CONFIGURATION
# ==============================================================================

@dataclass(frozen=True)
class Config:
    """
    Holds all user-configurable settings for the script.
    A researcher can easily modify these values to adapt the script.
    'frozen=True' makes instances of this class immutable, preventing accidental changes.
    """
    REPO_OWNER: str = 'apache'
    REPO_PROJECT: str = 'flink'
    CSV_INPUT_PATH: str = 'rename_data.csv'
    JSON_OUTPUT_PATH: str = 'ground_truth_dataset.json'
    GITHUB_TOKEN: Optional[str] = os.environ.get('GITHUB_TOKEN')


# ==============================================================================
# 2. GITHUB API CLIENT
# ==============================================================================

class GitHubAPIClient:
    """
    Handles all communication with the GitHub API.

    This class is responsible for making authenticated requests, handling rate-limiting
    errors with a robust retry strategy, and caching results to minimize API calls.
    """

    def __init__(self, owner: str, project: str, token: Optional[str]):
        """
        Initializes the API client.

        Args:
            owner: The owner of the GitHub repository (e.g., 'apache').
            project: The name of the repository (e.g., 'flink').
            token: A GitHub Personal Access Token for authentication.
        """
        self.base_api_url = f"https://api.github.com/repos/{owner}/{project}"
        self.headers = {'Accept': 'application/vnd.github.v3+json'}
        if token:
            self.headers['Authorization'] = f'token {token}'
        self._cache: Dict[str, Any] = {} # Internal cache for API responses

    def _make_request_with_retries(self, url: str) -> Optional[requests.Response]:
        """
        Performs a GET request with a robust retry strategy for rate limiting (403 errors).

        Args:
            url: The full URL for the API endpoint.

        Returns:
            A requests.Response object on success, or None if all retries fail.
        """
        max_retries = 3
        for attempt in range(max_retries):
            try:
                response = requests.get(url, headers=self.headers)
                if response.status_code == 200:
                    return response
                
                if response.status_code == 403:
                    print(f"    - Received 403 Forbidden (Attempt {attempt + 1}/{max_retries})")
                    if attempt < max_retries - 1:
                        self._handle_rate_limit_wait(response, is_final_attempt=(attempt == max_retries - 2))
                else:
                    # For non-retriable errors like 404 Not Found, fail immediately.
                    print(f"    - Received non-retriable status {response.status_code} for URL: {url.split('?')[0]}")
                    return response
            except requests.exceptions.RequestException as e:
                print(f"    - Network error on attempt {attempt + 1}: {e}")
                if attempt < max_retries - 1:
                    time.sleep(15) # Wait for transient network issues
        
        print(f"  - Request failed after {max_retries} attempts for URL: {url.split('?')[0]}")
        return None

    def _handle_rate_limit_wait(self, response: requests.Response, is_final_attempt: bool):
        """Determines how long to wait based on the API's rate limit headers."""
        if is_final_attempt:
            reset_time_str = response.headers.get('x-ratelimit-reset')
            wait_seconds = 3605 # Default to 1 hour + 5s
            if reset_time_str:
                reset_timestamp = int(reset_time_str)
                # Add a 5-second buffer to be safe
                wait_seconds = max(1, reset_timestamp - int(time.time()) + 5)
            
            print(f"      - Rate limit still active. Waiting for {wait_seconds} seconds until reset...")
            time.sleep(wait_seconds)
        else:
            print("      - Rate limit likely hit. Waiting 61 seconds...")
            time.sleep(61)

    def get_commit_details(self, commit_sha: str) -> Optional[Dict[str, Any]]:
        """
        Fetches and caches the detailed information for a single commit.

        This includes the list of files changed, which is essential for detecting renames.
        """
        cache_key = f"commit_{commit_sha}"
        if cache_key in self._cache:
            return self._cache[cache_key]

        url = f"{self.base_api_url}/commits/{commit_sha}"
        response = self._make_request_with_retries(url)
        
        if response and response.status_code == 200:
            commit_data = response.json()
            self._cache[cache_key] = commit_data
            return commit_data
        return None

    def get_pull_request_info(self, commit_sha: str) -> Optional[Dict[str, Any]]:
        """
        Fetches and caches the pull request associated with a commit.

        A commit may be part of a squash merge, and the PR provides critical context.
        """
        cache_key = f"pr_{commit_sha}"
        if cache_key in self._cache:
            return self._cache[cache_key]

        url = f"{self.base_api_url}/commits/{commit_sha}/pulls"
        response = self._make_request_with_retries(url)
        
        pr_info = None
        if response and response.status_code == 200:
            prs = response.json()
            if prs:
                pr = prs[0] # Assume the first PR is the most relevant
                pr_info = {"number": pr['number'], "title": pr['title'], "url": pr['html_url']}
        
        self._cache[cache_key] = pr_info # Cache even if None to avoid re-fetching
        return pr_info

    def get_file_content(self, commit_sha: str, file_path: str) -> Optional[str]:
        """
        Fetches the text content of a file at a specific commit.
        """
        if not file_path:
            return None
        
        url = f"{self.base_api_url}/contents/{file_path}?ref={commit_sha}"
        response = self._make_request_with_retries(url)
        
        if response and response.status_code == 200:
            content_base64 = response.json().get('content', '')
            return base64.b64decode(content_base64).decode('utf-8')
        return None

# ==============================================================================
# 3. UTILITY CLASSES
# ==============================================================================

class IdentifierLocator:
    """
    A utility class for finding all occurrences of an identifier in text content.
    """
    @staticmethod
    def find_all(content: str, identifier: str) -> List[Dict[str, int]]:
        """
        Finds all line/column locations of an identifier within a string.

        Args:
            content: The text content of a file.
            identifier: The string identifier to search for.

        Returns:
            A list of dictionaries, each representing a found instance.
        """
        if not content or not identifier:
            return []
            
        locations = []
        lines = content.splitlines()
        for i, line in enumerate(lines):
            start_index = 0
            while True:
                # Find the next occurrence in the line
                start_column_zero_based = line.find(identifier, start_index)
                if start_column_zero_based == -1:
                    break
                
                start_column = start_column_zero_based + 1  # Convert to 1-indexed
                end_column = start_column + len(identifier) - 1
                
                locations.append({
                    "instance_id": len(locations) + 1,
                    "line": i + 1,  # 1-indexed line number
                    "start_column": start_column,
                    "end_column": end_column
                })
                # Continue searching from the end of the last found instance
                start_index = start_column_zero_based + len(identifier)
        return locations

# ==============================================================================
# 4. DATA REPRESENTATION
# ==============================================================================

class RefactoringData:
    """
    Represents and processes a single refactoring instance (one row from the CSV).
    """
    def __init__(self, commit_sha: str, old_name: str, new_name: str, file_path: str, refactoring_type: str):
        # Data from CSV
        self.commit_sha = commit_sha
        self.old_name = old_name
        self.new_name = new_name
        self.file_path_from_csv = file_path
        self.type = refactoring_type
        
        # Data to be populated from the API
        self.pull_request_info: Optional[Dict[str, Any]] = None
        self.path_before: Optional[str] = None
        self.path_after: Optional[str] = None
        self.locations_before: List[Dict[str, int]] = []
        self.locations_after: List[Dict[str, int]] = []

    def populate_from_api(self, client: GitHubAPIClient) -> bool:
        """
        Orchestrates fetching all necessary API data for this refactoring instance.

        Args:
            client: The configured GitHubAPIClient instance.
        
        Returns:
            True if population was successful, False otherwise.
        """
        print(f"  - Refactoring: '{self.old_name}' -> '{self.new_name}'")

        # 1. Fetch commit and PR details
        commit_details = client.get_commit_details(self.commit_sha)
        if not commit_details:
            print(f"  - Skipping: Could not fetch commit details for {self.commit_sha}.")
            return False
        
        self.pull_request_info = client.get_pull_request_info(self.commit_sha)
        if self.pull_request_info:
            print(f"  - Associated PR #{self.pull_request_info['number']}: {self.pull_request_info['title']}")
        else:
            print("  - No associated PR found.")

        # 2. Determine correct file paths before and after the commit
        self._determine_file_paths(commit_details)

        # 3. Fetch file contents and find identifier locations
        self._find_identifier_locations(client)
        
        print(f"  - Found {len(self.locations_before)} instances before, {len(self.locations_after)} after.")
        return True

    def _determine_file_paths(self, commit_details: Dict[str, Any]):
        """Infers before/after file paths by analyzing commit details."""
        for file_info in commit_details.get('files', []):
            is_match = (file_info['filename'] == self.file_path_from_csv or
                        file_info.get('previous_filename') == self.file_path_from_csv)
            if is_match:
                if file_info['status'] == 'renamed':
                    self.path_before = file_info['previous_filename']
                    self.path_after = file_info['filename']
                    print(f"  - File renamed: '{self.path_before}' -> '{self.path_after}'")
                else:
                    self.path_before = self.path_after = file_info['filename']
                return
        
        # Fallback if the file path wasn't found in the commit details
        print(f"    - Warning: Could not find '{self.file_path_from_csv}' in commit details. Using path from CSV as fallback.")
        self.path_before = self.path_after = self.file_path_from_csv

    def _find_identifier_locations(self, client: GitHubAPIClient):
        """Fetches file contents and uses IdentifierLocator to find instances."""
        # "Before" state is at the parent commit
        commit_before_sha = f"{self.commit_sha}^1"
        content_before = client.get_file_content(commit_before_sha, self.path_before)
        self.locations_before = IdentifierLocator.find_all(content_before, self.old_name)

        # "After" state is at the commit itself
        content_after = client.get_file_content(self.commit_sha, self.path_after)
        self.locations_after = IdentifierLocator.find_all(content_after, self.new_name)
    
    def to_dict(self, config: Config) -> Dict[str, Any]:
        """Serializes the object's data into a dictionary for JSON output."""
        base_url = f"https://github.com/{config.REPO_OWNER}/{config.REPO_PROJECT}/blob"
        commit_before_sha = f"{self.commit_sha}^1"

        return {
            "commit_sha1": self.commit_sha,
            "pull_request_info": self.pull_request_info,
            "old_name": self.old_name,
            "new_name": self.new_name,
            "file_changed_in_csv": self.file_path_from_csv,
            "type": self.type,
            "url_before_refactoring": f"{base_url}/{commit_before_sha}/{self.path_before}" if self.path_before else None,
            "file_path_before_refactoring": self.path_before,
            "instance_location_before_refactoring": self.locations_before,
            "url_after_refactoring": f"{base_url}/{self.commit_sha}/{self.path_after}" if self.path_after else None,
            "file_path_after_refactoring": self.path_after,
            "instance_location_after_refactoring": self.locations_after
        }

# ==============================================================================
# 5. MAIN ORCHESTRATOR
# ==============================================================================

class GroundTruthGenerator:
    """
    Orchestrates the entire process of generating the ground truth dataset.
    
    It reads the input CSV, processes each refactoring instance, and writes the
    final JSON output.
    """
    def __init__(self, config: Config):
        self.config = config
        self.api_client = GitHubAPIClient(config.REPO_OWNER, config.REPO_PROJECT, config.GITHUB_TOKEN)
        self.refactoring_tasks: List[RefactoringData] = []

    def _load_tasks_from_csv(self):
        """Loads refactoring tasks from the input CSV file and sorts them by commit SHA."""
        try:
            tasks = []
            with open(self.config.CSV_INPUT_PATH, mode='r', encoding='utf-8') as infile:
                reader = csv.DictReader(infile)
                for row in reader:
                    commit_sha = row.get('sha1')
                    old_name = row.get('old_name')
                    new_name = row.get('new_name')
                    file_path = row.get('file_changed')
                    ref_type = row.get('type')
                    
                    if not all([commit_sha, old_name, new_name, file_path]):
                        print(f"Skipping invalid row in CSV: {row}")
                        continue
                    
                    tasks.append(
                        RefactoringData(commit_sha, old_name, new_name, file_path, ref_type)
                    )

            # Sort the tasks in-place based on the commit_sha attribute of each object.
            tasks.sort(key=lambda task: task.commit_sha)
            
            self.refactoring_tasks = tasks
            print(f"Loaded and sorted {len(self.refactoring_tasks)} tasks from {self.config.CSV_INPUT_PATH}.")
            
        except FileNotFoundError:
            print(f"Error: The input file '{self.config.CSV_INPUT_PATH}' was not found.")
            self.refactoring_tasks = []

    def run(self):
        """
        Executes the full ground truth generation pipeline.
        """
        self._load_tasks_from_csv()
        if not self.refactoring_tasks:
            return

        final_results = []
        for task in self.refactoring_tasks:
            print(f"\nProcessing commit {task.commit_sha}...")
            print(f"  - File from CSV: {task.file_path_from_csv}")
            
            success = task.populate_from_api(self.api_client)
            if success:
                final_results.append(task.to_dict(self.config))

        # Write the final JSON file
        with open(self.config.JSON_OUTPUT_PATH, 'w', encoding='utf-8') as outfile:
            json.dump(final_results, outfile, indent=2)

        print(f"\nSuccessfully created '{self.config.JSON_OUTPUT_PATH}' with {len(final_results)} entries.")

# ==============================================================================
# 6. SCRIPT EXECUTION
# ==============================================================================

if __name__ == "__main__":
    # 1. Initialize configuration
    script_config = Config()
    
    # 2. Check for GitHub Token
    if not script_config.GITHUB_TOKEN:
        print("Warning: GITHUB_TOKEN environment variable not set.")
        print("API requests will be unauthenticated and subject to a very low rate limit.")

    # 3. Create and run the generator
    generator = GroundTruthGenerator(script_config)
    generator.run()