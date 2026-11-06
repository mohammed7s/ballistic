"""Turn a task log and its JSON record into an honest TSV row (stdlib only)."""
import argparse
import json
from pathlib import Path

COLUMNS = ['task', 'result', 'steps', 'seconds', 'exit', 'benchmark', 'agent_done',
           'failure_category', 'model_calls', 'prompt_tokens', 'run_json']


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
                  prompt_tokens=sum(c.get('prompt_tokens', 0) for c in record.get('model_calls', [])),
                  run_json=path)
    if (record.get('benchmark_status') == 'error' or process_exit != 0
            or record.get('exit_code', 0) != 0 or record.get('failure_category') == 'environment'):
        result.update(result='ERROR', failure_category='environment')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--task', required=True)
    p.add_argument('--log', required=True)
    p.add_argument('--exit', dest='process_exit', required=True, type=int)
    a = p.parse_args()
    row = summarize(a.task, a.log, a.process_exit)
    print('\t'.join(str(row[c]).replace('\t', ' ').replace('\n', ' ') for c in COLUMNS))


if __name__ == '__main__':
    main()
