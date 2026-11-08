"""Turn a task log and its JSON record into an honest TSV row (stdlib only)."""
import argparse
import json
import math
from pathlib import Path

COLUMNS = ['task', 'result', 'steps', 'seconds', 'exit', 'benchmark', 'agent_done',
           'failure_category', 'model_calls', 'prompt_tokens', 'run_json',
           'http_attempts', 'retry_attempts', 'completion_tokens',
           'unknown_prompt_attempts', 'unknown_completion_attempts', 'pacing_seconds',
           'checker_seconds']


def model_usage(calls):
    """Known token totals include failed/retried attempts; missing usage stays explicit."""
    def attempts(call):
        for retry in call.get('retried', []):
            yield from attempts(retry)
        yield call

    all_attempts = [attempt for call in calls for attempt in attempts(call)]
    result = {'http_attempts': len(all_attempts),
              'retry_attempts': len(all_attempts) - len(calls)}
    for field, unknown in [('prompt_tokens', 'unknown_prompt_attempts'),
                           ('completion_tokens', 'unknown_completion_attempts')]:
        known = [a[field] for a in all_attempts
                 if type(a.get(field)) is int and a[field] >= 0]
        result[field] = sum(known)
        result[unknown] = len(all_attempts) - len(known)
    # Each logical call already aggregates pacing across its retries; do not double count.
    result['pacing_seconds'] = round(sum(c.get('pacing_seconds', 0) for c in calls
        if type(c.get('pacing_seconds', 0)) in (int, float)
        and math.isfinite(c.get('pacing_seconds', 0)) and c.get('pacing_seconds', 0) >= 0), 3)
    # A call that ended on HTTP 429 stops the suite. A 429 recovered within the call's attempt
    # budget (Anthropic, provider-given retry delay) stays visible in `retried` only.
    result['quota_limited'] = any(c.get('http_status') == 429 for c in calls)
    result['billing_error'] = any(a.get('error_category') == 'billing' for a in all_attempts)
    return result


def summarize(task, log_path, process_exit):
    result = dict.fromkeys(COLUMNS, '')
    result.update(task=task, result='ERROR', exit=process_exit,
                  benchmark='error', failure_category='environment')
    paths = [line[len('written '):] for line in Path(log_path).read_text().splitlines()
             if line.startswith('written ')]
    if not paths:
        return result
    path = paths[-1]
    try:
        record = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return result
    result.update(result='PASS' if record.get('success') else 'FAIL',
                  steps=len(record.get('steps', [])), seconds=record.get('total_seconds', ''),
                  exit=record.get('exit_code', process_exit),
                  benchmark=record.get('benchmark_status', 'legacy'),
                  agent_done=str(bool(record.get('done'))).lower(),
                  failure_category=record.get('failure_category') or '',
                  model_calls=len(record.get('model_calls', [])),
                  run_json=path, checker_seconds=record.get('checker_seconds', ''))
    result.update(model_usage(record.get('model_calls', [])))
    if (result['quota_limited'] or result['billing_error'] or record.get('benchmark_status') == 'error' or process_exit != 0
            or record.get('exit_code', 0) != 0 or record.get('failure_category') == 'environment'):
        result.update(result='ERROR', failure_category='environment')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--task', required=True)
    p.add_argument('--log', required=True)
    p.add_argument('--exit', dest='process_exit', required=True, type=int)
    p.add_argument('--stop-on-quota', action='store_true',
                   help='Print the row, then exit 75 if a call ended on HTTP 429, or 76 on a '
                        'provider billing error (e.g. credit balance too low).')
    a = p.parse_args()
    row = summarize(a.task, a.log, a.process_exit)
    print('\t'.join(str(row[c]).replace('\t', ' ').replace('\n', ' ') for c in COLUMNS))
    if a.stop_on_quota and row.get('billing_error'):
        raise SystemExit(76)
    if a.stop_on_quota and row.get('quota_limited'):
        raise SystemExit(75)


if __name__ == '__main__':
    main()
