import copy
import unittest

from runner_support import playwright_command, resumable_metrics


class ResumeTests(unittest.TestCase):
    def setUp(self):
        self.previous = {
            'evidenceHash': 'execution-one', 'judgeModel': 'qwen3:8b',
            'metrics': {
                'TurnRelevancy': {'applicable': True, 'passed': True, 'score': 1.0},
                'Faithfulness': {'applicable': True, 'passed': False, 'score': 0.2},
                'KnowledgeRetention': {'applicable': False, 'reason': 'Single-turn case'},
                'AnswerRelevancy': {'applicable': True, 'status': 'BLOCKED', 'error': 'timeout'},
                'TurnFaithfulness': {'applicable': True, 'qualityStatus': 'EVALUATION_ERROR'},
            },
        }

    def test_resume_retries_errors_and_preserves_real_negative_verdict(self):
        original = copy.deepcopy(self.previous)
        reused = resumable_metrics(self.previous, 'execution-one', 'qwen3:8b', True)
        self.assertEqual(set(reused), {'TurnRelevancy', 'Faithfulness', 'KnowledgeRetention'})
        self.assertFalse(reused['Faithfulness']['passed'])
        self.assertEqual(self.previous, original)

    def test_new_execution_does_not_reuse_scores(self):
        self.assertEqual(resumable_metrics(self.previous, 'execution-two', 'qwen3:8b', True), {})

    def test_new_judge_does_not_reuse_scores(self):
        self.assertEqual(resumable_metrics(self.previous, 'execution-one', 'qwen3:4b', True), {})

    def test_explicit_rescore_does_not_reuse_scores(self):
        self.assertEqual(resumable_metrics(self.previous, 'execution-one', 'qwen3:8b', False), {})


class BrowserCommandTests(unittest.TestCase):
    def test_native_launchers_keep_identical_playwright_arguments(self):
        windows = playwright_command('nt')
        linux = playwright_command('posix')
        self.assertEqual(windows[0], 'npx.cmd')
        self.assertEqual(linux[0], 'npx')
        self.assertEqual(windows[1:], linux[1:])


if __name__ == '__main__':
    unittest.main()
