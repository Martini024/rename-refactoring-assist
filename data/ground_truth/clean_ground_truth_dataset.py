import json

# --- Configuration ---
# Define the input and output file names
INPUT_FILE = 'ground_truth_dataset.json'
OUTPUT_FILE = 'ground_truth_dataset_cleaned.json'

def clean_duplicate_entries():
    """
    Reads a JSON dataset, counts and removes exact duplicate entries,
    and saves the cleaned data to a new file.
    """
    print(f"Starting the cleaning process for '{INPUT_FILE}'...")

    # --- Step 1: Load the dataset ---
    try:
        with open(INPUT_FILE, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except FileNotFoundError:
        print(f"Error: The input file '{INPUT_FILE}' was not found.")
        return
    except json.JSONDecodeError:
        print(f"Error: The file '{INPUT_FILE}' is not a valid JSON file.")
        return

    original_count = len(data)
    if original_count == 0:
        print("The input file is empty. No action needed.")
        return
        
    print(f"Successfully loaded {original_count} entries from the original file.")

    # --- Step 2: Identify and filter out duplicates ---
    unique_entries = []
    seen_entries_hashes = set()
    duplicates_found = 0

    for entry in data:
        # Create a canonical, hashable representation of the dictionary
        # by converting it to a string with sorted keys.
        canonical_entry_str = json.dumps(entry, sort_keys=True)

        if canonical_entry_str not in seen_entries_hashes:
            # If we haven't seen this exact entry before, add it to our results
            seen_entries_hashes.add(canonical_entry_str)
            unique_entries.append(entry)
        else:
            # If we have seen it, it's a duplicate
            duplicates_found += 1
    
    new_count = len(unique_entries)

    # --- Step 3: Report the results ---
    print("\n--- Cleaning Summary ---")
    print(f"Original entry count: {original_count}")
    print(f"Exact duplicate entries found and removed: {duplicates_found}")
    print(f"New entry count after cleaning: {new_count}")
    print("------------------------\n")

    # --- Step 4: Save the cleaned dataset to a new file ---
    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        # Use indent=2 for a human-readable JSON output
        json.dump(unique_entries, f, indent=2)

    print(f"Successfully saved the clean, deduplicated dataset to '{OUTPUT_FILE}'.")


if __name__ == "__main__":
    clean_duplicate_entries()