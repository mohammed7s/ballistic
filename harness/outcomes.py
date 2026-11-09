"""Independent benchmark grading; never exposed to the production planner."""
from __future__ import annotations

import time


def task_scores_answer(task) -> bool:
    """True only when AndroidWorld's checker for `task` scores the agent's answer string
    (env.interaction_cache): the information-retrieval tasks. Without AndroidWorld importable,
    nothing is treated as an answer task."""
    if task is None:
        return False
    try:
        from android_world.task_evals.information_retrieval.information_retrieval import InformationRetrieval
    except Exception:  # noqa: BLE001  AndroidWorld absent (unit tests, replay)
        return False
    return isinstance(task, InformationRetrieval)


def evaluate_benchmark(task, env, agent_done: bool, answer_delivered: bool = False,
                       scores_answer=task_scores_answer) -> dict:
    """The checker's verdict, kept apart from the agent's claim. A run counts as agent-complete
    (combined PASS column) when the agent's done was accepted, or, for a task whose checker
    scores an answer string, when a sourced answer was delivered to the env (harness only).
    The checker still decides: an answer it fails is FAIL. `agent_reported_done` stays the
    agent's own flag."""
    answer_scored = bool(answer_delivered and scores_answer(task))
    result = {'agent_reported_done': bool(agent_done), 'benchmark_success': None,
              'benchmark_status': 'not_applicable', 'benchmark_error': None,
              'combined_success': None, 'checker_seconds': 0.0,
              'answer_delivered': bool(answer_delivered), 'answer_scored_by_checker': answer_scored,
              'agent_complete_basis': 'done' if agent_done else ('answer' if answer_scored else None)}
    if task is None:
        return result
    start = time.monotonic()
    try:
        success = bool(task.is_successful(env) == 1)
        result.update(benchmark_success=success,
                      benchmark_status='passed' if success else 'failed',
                      combined_success=bool((agent_done or answer_scored) and success))
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
    # A delivered answer the checker scored and failed: wrong or incomplete answer.
    if outcome.get('benchmark_success') is False and outcome.get('answer_scored_by_checker'):
        return 'answer'
    if (outcome.get('benchmark_success') is True and not run.get('done')
            and outcome.get('agent_complete_basis') != 'answer'):
        return 'verification'
    return None


# ---- answer delivery (harness only) ---------------------------------------------------------

def answer_for_checker(report: dict | None) -> tuple[str | None, str | None]:
    """The text an answer-scoring checker should read, and where it came from.

    Only the raw planner `answer.text` of a sourced answer (core's `answer_values_sourced`), or
    the owner excerpts of an available terminal owner summary joined with ", ". Never the
    display string or `reason`: those add `(from <source>)` tags, which break the checker's
    comma split and fuzzy match. The text goes to the env only; never into a model prompt."""
    if not isinstance(report, dict):
        return None, None
    answer = report.get('answer')
    basis = (report.get('verification') or {}).get('evidence_basis')
    if isinstance(answer, dict):
        text = answer.get('text')
        if basis == 'answer_values_sourced' and isinstance(text, str) and text.strip():
            return text, 'answer_text'
        return None, None
    owner = report.get('owner_output')
    if isinstance(owner, dict) and owner.get('status') == 'available':
        items = owner.get('items')
        if isinstance(items, list) and items:
            texts = [i.get('text') for i in items if isinstance(i, dict)]
            if len(texts) == len(items) and all(isinstance(t, str) and t.strip() for t in texts):
                return ', '.join(t.strip() for t in texts), 'owner_excerpts'
    return None, None


def deliver_answer(env, report: dict | None, make_answer_action) -> dict:
    """Write the run's answer to `env` the way AndroidWorld's answer tasks read it: an ANSWER
    action through `env.execute_action`, which sets `env.interaction_cache` (T3A uses the same
    path). `make_answer_action(text)` builds that action (injectable for tests). Returns
    `{"delivered", "source", "chars"}`; the text itself is not returned."""
    text, source = answer_for_checker(report)
    if text is None or env is None:
        return {'delivered': False, 'source': None, 'chars': 0}
    try:
        env.execute_action(make_answer_action(text))
    except AttributeError:
        env.interaction_cache = text
    return {'delivered': True, 'source': source, 'chars': len(text)}
