"""Dependency-free policies shared by the local evaluation runners."""


def resumable_metrics(previous, evidence_hash, judge_model, enabled):
    """Reuse completed verdicts only for the same evidence and judge.

    A negative quality verdict remains a verdict. A transport/parser failure is
    not a verdict and must be retried. The caller archives the previous record.
    """
    if not enabled or previous.get('evidenceHash') != evidence_hash or previous.get('judgeModel') != judge_model:
        return {}
    return {
        name: metric for name, metric in previous.get('metrics', {}).items()
        if metric.get('status') != 'BLOCKED'
        and metric.get('qualityStatus') != 'EVALUATION_ERROR'
    }


def playwright_command(platform):
    """Invoke npm's native launcher without requiring PowerShell."""
    return ['npx.cmd' if platform == 'nt' else 'npx', 'playwright', 'test',
            '--config', 'playwright.deepeval.config.ts']
