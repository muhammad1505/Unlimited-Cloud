import os
import re
import sys

FORBIDDEN_COMMON_IMPORTS = [
    r"^import\s+android\.",
    r"^import\s+java\.io\.File",
    r"^import\s+javax\.",
    r"^import\s+org\.drinkless\.tdlib",
    r"^import\s+it\.tdlight",
    r"^import\s+com\.sun\.jna",
]

def check_brackets(content, filepath):
    stack = []
    pairs = {')': '(', '}': '{', ']': '['}
    in_string = False
    escape = False
    
    for line_num, line in enumerate(content.splitlines(), 1):
        # Skip single line comments
        stripped = line.strip()
        if stripped.startswith("//"):
            continue
        for char in line:
            if escape:
                escape = False
                continue
            if char == '\\':
                escape = True
                continue
            if char == '"':
                in_string = not in_string
                continue
            if in_string:
                continue
            if char in '({[':
                stack.append((char, line_num))
            elif char in ')}]':
                if not stack or stack[-1][0] != pairs[char]:
                    return f"{filepath}:{line_num} -> Unmatched closing '{char}'"
                stack.pop()
    if stack:
        char, line_num = stack[-1]
        return f"{filepath}:{line_num} -> Unclosed '{char}'"
    return None

def scan_repo():
    errors = []
    for root, _, files in os.walk("."):
        if any(skip in root for skip in [".git", ".gradle", "build"]):
            continue
        for file in files:
            filepath = os.path.join(root, file)
            if file.endswith((".kt", ".kts")):
                with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                
                if "<<<<<<<" in content or ">>>>>>>" in content:
                    errors.append(f"{filepath} -> Unresolved Git merge conflict markers found.")
                
                bracket_err = check_brackets(content, filepath)
                if bracket_err:
                    errors.append(bracket_err)
                
                if "commonMain" in filepath and file.endswith(".kt"):
                    for idx, line in enumerate(content.splitlines(), 1):
                        for pattern in FORBIDDEN_COMMON_IMPORTS:
                            if re.match(pattern, line.strip()):
                                errors.append(f"{filepath}:{idx} -> Forbidden platform import in commonMain: '{line.strip()}'")
    return errors

if __name__ == "__main__":
    print("Running KMP Preflight Checks...")
    issues = scan_repo()
    if issues:
        print("\n[FAILED] Preflight check found issues:")
        for issue in issues:
            print(f" - {issue}")
        sys.exit(1)
    print("[PASSED] All preflight checks succeeded! Ready to commit & push.")
