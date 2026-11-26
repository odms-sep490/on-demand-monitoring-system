"""Tests for Python runtime Prompt Resolver (hot-reload, variable rendering, stale-cache fallback)."""
import json
import os
import unittest
from unittest.mock import patch, MagicMock

import prompt_resolver


class PromptResolverTest(unittest.TestCase):
    def setUp(self):
        prompt_resolver.invalidate_cache()

    def _mock_response(self, version_number: int, content: str):
        payload = json.dumps({
            "data": {
                "versionId": f"ver-{version_number}",
                "versionNumber": version_number,
                "renderedContent": content,
                "modelParameters": {"temperature": 0.0},
                "staleCacheUsed": False,
            }
        }).encode("utf-8")
        cm = MagicMock()
        cm.__enter__.return_value.read.return_value = payload
        return cm

    def test_resolves_from_registry_and_hot_reloads_on_invalidation(self):
        with patch.dict(os.environ, {"DESCRIPTION_AI_TOKEN": "test-secret"}):
            with patch("urllib.request.urlopen", return_value=self._mock_response(1, "Hello {{name}} v1")):
                first = prompt_resolver.resolve_prompt("report.inspection.system", {"name": "ODM"})
                self.assertEqual(first.version_number, 1)
                self.assertEqual(first.content, "Hello ODM v1")
                self.assertEqual(first.source, "registry")

            # Hot-reload after cache invalidation picks up v2 without worker restart
            prompt_resolver.invalidate_cache("report.inspection.system")
            with patch("urllib.request.urlopen", return_value=self._mock_response(2, "Updated {{name}} v2")):
                second = prompt_resolver.resolve_prompt("report.inspection.system", {"name": "ODM"})
                self.assertEqual(second.version_number, 2)
                self.assertEqual(second.content, "Updated ODM v2")
                self.assertEqual(second.source, "registry")

    def test_uses_stale_cache_when_registry_becomes_unavailable(self):
        with patch.dict(os.environ, {"DESCRIPTION_AI_TOKEN": "test-secret"}):
            with patch("urllib.request.urlopen", return_value=self._mock_response(3, "Active v3")):
                prompt_resolver.resolve_prompt("order.description.system")

            # Force TTL expiry while keeping cached snapshot
            snap, _ = prompt_resolver._cache["order.description.system"]
            prompt_resolver._cache["order.description.system"] = (snap, 0.0)

            with patch("urllib.request.urlopen", side_effect=OSError("registry down")):
                fallback = prompt_resolver.resolve_prompt("order.description.system")
                self.assertEqual(fallback.version_number, 3)
                self.assertTrue(fallback.stale_cache_used)
                self.assertEqual(fallback.source, "stale-cache")

    def test_offline_bootstrap_fallback_when_no_token_or_cache(self):
        with patch.dict(os.environ, {"DESCRIPTION_AI_TOKEN": ""}):
            snap = prompt_resolver.resolve_prompt("order.consultant.router", bootstrap_fallback="Bootstrap {{x}}", variables={"x": "ok"})
            self.assertEqual(snap.content, "Bootstrap ok")
            self.assertEqual(snap.source, "bootstrap-offline")


if __name__ == "__main__":
    unittest.main()
