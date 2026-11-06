"""Independent benchmark grading; never exposed to the production planner."""
from __future__ import annotations

import time


def evaluate_benchmark(task, env, agent_done: bool) -> dict:
    result = {'agent_reported_done': bool(agent_done), 'benchmark_success': None,
              'benchmark_status': 'not_applicable', 'benchmark_error': None,
              'combined_success': None, 'checker_seconds': 0.0}
    if task is None:
        return result
    start = time.monotonic()
    try:
        success = bool(task.is_successful(env) == 1)
        result.update(benchmark_success=success,
                      benchmark_status='passed' if success else 'failed',
                      combined_success=bool(agent_done and success))
    except Exception as exc:
        # Checker faults are infrastructure failures, not model failures.
        result.update(benchmark_status='error',
                      benchmark_error=f'{type(exc).__name__}: {exc}')
    result['checker_seconds'] = round(time.monotonic() - start, 3)
    return result


def combined_verdict(outcome: dict) -> str:
    if outcome['benchmark_status'] == 'not_applicable':
        return 'DONE'
    if outcome['benchmark_status'] == 'error':
        return 'ERROR'
    return 'PASS' if outcome['combined_success'] else 'FAIL'


def failure_category(run: dict, outcome: dict, model_calls: list[dict]) -> str | None:
    if outcome.get('benchmark_status') == 'error':
        return 'environment'
    if any(call.get('error_type') for call in model_calls):
        return 'environment'
    finished = run.get('finished') or {}
    if finished.get('error') or run.get('exit_code', 0):
        return 'environment'
    report = finished.get('report') or {}
    if report.get('failure_category'):
        return report['failure_category']
    if outcome.get('benchmark_success') is False and run.get('done'):
        return 'verification'
    if outcome.get('benchmark_success') is True and not run.get('done'):
        return 'verification'
    return None
