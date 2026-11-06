"""Compare T3A and core screen text on recorded fixtures (characters, not model tokens)."""
import argparse
import json
from pathlib import Path
import subprocess

from core_bridge import default_core_bin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('screens', type=Path, help='directory of record_screens.py JSON fixtures')
    parser.add_argument('--core_bin', default=None)
    args = parser.parse_args()
    binary = args.core_bin or default_core_bin()
    rows = []
    for path in sorted(args.screens.glob('*.json')):
        record = json.loads(path.read_text())
        result = subprocess.run([binary, 'filter', '--request', record['goal']],
                                input=json.dumps(record['screen']), text=True,
                                capture_output=True, check=True, timeout=30)
        rows.append({'screen': path.name, 't3a_chars': len(record['t3a_text']),
                     'core_chars': len(result.stdout.rstrip('\n'))})
    if not rows:
        parser.error('no screen fixtures found')
    before = sum(row['t3a_chars'] for row in rows)
    after = sum(row['core_chars'] for row in rows)
    print(json.dumps({'unit': 'characters (not tokenizer tokens)', 'screens': rows,
                      't3a_total': before, 'core_total': after,
                      'reduction_percent': round(100 * (1 - after / before), 1) if before else None}, indent=2))


if __name__ == '__main__':
    main()
