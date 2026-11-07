"""Offline supplied-reply comparison; no host, HTTP, model or device access.

Run with --baseline OLD_BIN --candidate NEW_BIN --output DIR. Original records are
hash-checked against the frozen extracts. Only the specified rejection feedback
may differ. Finite scripted equality is not source equivalence or planner quality.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT / 'core/tests/cases/reader_authority_scripts.json'
RECORDS = ROOT / 'core/tests/cases/reader_authority_recorded.json'


def reason(target):
    return (f'e{target} has a current reader pick authorizing tap only; long_press remains '
            'unsupported for this target. Use current permitted controls')


def old_reason(target):
    return (f'e{target} has no identified control or matched item; hidden content and '
            'generic resource ids cannot authorize a guessed target')


def correction(target, new):
    return f'Invalid action: {(reason if new else old_reason)(target)}. Return a corrected JSON action.'


def clean(value):
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items() if k not in ('dir', 'seconds')}
    if isinstance(value, list):
        return [clean(v) for v in value]
    return value


def run_script(binary, case):
    replies = [dict(id=i + 1, ok=True, data=d) for i, d in enumerate(case['replies'])]
    proc = subprocess.run([str(binary), *case['args']],
                          input=''.join(json.dumps(r) + '\n' for r in replies),
                          text=True, capture_output=True, timeout=15, check=True)
    calls = [json.loads(line) for line in proc.stdout.splitlines()]
    assert len([c for c in calls if c['ask'] != 'log']) == len(replies), case['name']
    assert calls[-1]['event']['finished'] is True
    assert calls[-1]['event']['report']['done'] is False
    assert len([c for c in calls if c['ask'] == 'act']) == case['acts'], case['name']
    for call in calls:
        if call['ask'] == 'complete':
            assert call['max_tokens'] == (64 if call['role'] == 'reader' else 800)
        if call.get('role') != 'reader':
            serialized = json.dumps(call)
            assert 'PRIVATE_' not in serialized and 'UNTRUSTED_READER_PROSE' not in serialized
    if case.get('category'):
        assert calls[-1]['event']['report']['failure_category'] == case['category']
    return calls


def check_feedback(calls, case, new):
    messages = [c['messages'][-1]['content'] for c in calls
                if c.get('role') == 'planner' and len(c['messages']) == 4]
    for target in set(case['feedback_ids']):
        assert messages.count(correction(target, new)) == case['feedback_ids'].count(target), case['name']
    assert sum('current reader pick authorizing tap only' in m for m in messages) == (
        len(case['feedback_ids']) if new else 0), case['name']


def compare(before, after, case):
    assert len(before) == len(after), case['name']
    changed = []
    for i, (a, b) in enumerate(zip(clean(before), clean(after))):
        if a == b:
            continue
        expected = json.loads(json.dumps(a))
        if a.get('role') == 'planner' and len(a['messages']) == 4:
            target = next(t for t in case['feedback_ids']
                          if a['messages'][-1]['content'] == correction(t, False))
            expected['messages'][-1]['content'] = correction(target, True)
            changed.append({'ask_index': i, 'target': target,
                            'before': correction(target, False), 'after': correction(target, True),
                            'character_delta': len(correction(target, True)) - len(correction(target, False))})
        elif a.get('event', {}).get('finished'):
            target = next(t for t in case['feedback_ids']
                          if a['event']['report']['reason'] == f'invalid action: {old_reason(t)}')
            expected['event']['report']['reason'] = f'invalid action: {reason(target)}'
        assert expected == b, (case['name'], i, a, b)
    return changed


def recorded_cases():
    for frozen in json.loads(RECORDS.read_text()):
        path = Path(frozen['source'])
        assert hashlib.sha256(path.read_bytes()).hexdigest() == frozen['sha256']
        run = json.loads(path.read_text())
        asks = [clean(l) for l in run['lines'] if l['dir'] == 'ask']
        replies = [l['data'] for l in run['lines'] if l['dir'] == 'reply']
        assert frozen['screen'] in replies
        yield ({'name': path.parent.parent.name, 'args': run['core_args'], 'replies': replies,
                'acts': 1, 'feedback_ids': [t for t in frozen['rejected_targets'] if t == 13],
                'category': 'target_resolution'}, asks, frozen['sha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--expect-baseline', action='store_true', help='pre-edit capture only')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    cases = [(c, None, None) for c in json.loads(CASES.read_text())] + list(recorded_cases())
    for case, recorded, source_hash in cases:
        before = run_script(args.baseline, case)
        after = run_script(args.candidate, case)
        check_feedback(before, case, False)
        check_feedback(after, case, not args.expect_baseline)
        if recorded is not None:
            assert clean(before) == recorded, case['name']
        changes = compare(before, after, case)
        for arm, calls in [('baseline', before), ('candidate', after)]:
            (args.output / f'{case["name"]}.{arm}.json').write_text(json.dumps(calls, indent=2) + '\n')
        results.append({'case': case['name'], 'source_sha256': source_hash,
                        'asks': len(before), 'dispatches': case['acts'],
                        'planner_calls': sum(c.get('role') == 'planner' for c in before),
                        'reader_calls': sum(c.get('role') == 'reader' for c in before),
                        'feedback_changes': changes, 'final_report': after[-1]['event']['report']})
    summary = {'baseline_sha256': hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
               'candidate_sha256': hashlib.sha256(args.candidate.read_bytes()).hexdigest(),
               'cases': results}
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'{len(results)} scripted cases; identical dispatches, call counts/caps, and events '
          '(except the specified terminal rejection reason); only specified feedback changed')


if __name__ == '__main__':
    main()
