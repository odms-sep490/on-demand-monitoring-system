"""Centralized runtime Prompt Resolver client for Python AI workers.

Fetches the active prompt version from Spring Boot (/api/internal/ai/prompts/{key}/active)
with a thread-safe TTL cache for hot-reload without restarting workers.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import urllib.request
from dataclasses import dataclass
from typing import Any

BACKEND_BASE_URL = os.getenv("BACKEND_BASE_URL", "http://localhost:8080").rstrip("/")
CACHE_TTL_SEC = max(1.0, float(os.getenv("PROMPT_CACHE_TTL_SEC", "10")))
VAR_PATTERN = re.compile(r"\{\{\s*([a-zA-Z0-9_.-]+)\s*\}\}")


@dataclass(frozen=True)
class ResolvedPromptSnapshot:
    prompt_key: str
    version_id: str
    version_number: int
    content: str
    model_parameters: dict[str, Any]
    stale_cache_used: bool
    source: str  # "registry" | "stale-cache" | "bootstrap-offline"


_lock = threading.Lock()
_cache: dict[str, tuple[ResolvedPromptSnapshot, float]] = {}


def invalidate_cache(prompt_key: str | None = None) -> None:
    with _lock:
        if prompt_key is None:
            _cache.clear()
        else:
            _cache.pop(prompt_key, None)


def render_variables(template: str, variables: dict[str, Any] | None = None) -> str:
    if not variables:
        return VAR_PATTERN.sub("", template)

    def _replace(match: re.Match[str]) -> str:
        key = match.group(1)
        val = variables.get(key)
        return "" if val is None else str(val)

    return VAR_PATTERN.sub(_replace, template)


def resolve_prompt(
    prompt_key: str,
    variables: dict[str, Any] | None = None,
    bootstrap_fallback: str | None = None,
) -> ResolvedPromptSnapshot:
    """Resolve active prompt from Spring Boot registry with TTL hot-reload and stale-cache fallback."""
    now = time.monotonic()
    with _lock:
        cached = _cache.get(prompt_key)
        if cached is not None and (now - cached[1]) < CACHE_TTL_SEC:
            snap = cached[0]
            return ResolvedPromptSnapshot(
                prompt_key=snap.prompt_key,
                version_id=snap.version_id,
                version_number=snap.version_number,
                content=render_variables(snap.content, variables),
                model_parameters=snap.model_parameters,
                stale_cache_used=False,
                source=snap.source,
            )

    token = os.getenv("DESCRIPTION_AI_TOKEN", "")
    url = f"{BACKEND_BASE_URL}/api/internal/ai/prompts/{prompt_key}/active"
    if token:
        try:
            req = urllib.request.Request(url, headers={"X-Internal-Token": token})
            with urllib.request.urlopen(req, timeout=2.5) as resp:
                body = json.loads(resp.read(200_000).decode("utf-8"))
            data = body.get("data") or body
            snap = ResolvedPromptSnapshot(
                prompt_key=prompt_key,
                version_id=str(data.get("versionId", "v1")),
                version_number=int(data.get("versionNumber", 1)),
                content=str(data.get("renderedContent") or data.get("templateContent") or ""),
                model_parameters=dict(data.get("modelParameters") or {}),
                stale_cache_used=bool(data.get("staleCacheUsed", False)),
                source="registry",
            )
            if snap.content:
                with _lock:
                    _cache[prompt_key] = (snap, time.monotonic())
                return ResolvedPromptSnapshot(
                    prompt_key=snap.prompt_key,
                    version_id=snap.version_id,
                    version_number=snap.version_number,
                    content=render_variables(snap.content, variables),
                    model_parameters=snap.model_parameters,
                    stale_cache_used=snap.stale_cache_used,
                    source="registry",
                )
        except Exception:
            pass

    # Registry temporarily unavailable: prefer last-known-good cached version.
    with _lock:
        stale = _cache.get(prompt_key)
        if stale is not None:
            snap = stale[0]
            return ResolvedPromptSnapshot(
                prompt_key=snap.prompt_key,
                version_id=snap.version_id,
                version_number=snap.version_number,
                content=render_variables(snap.content, variables),
                model_parameters=snap.model_parameters,
                stale_cache_used=True,
                source="stale-cache",
            )

    if bootstrap_fallback is not None:
        return ResolvedPromptSnapshot(
            prompt_key=prompt_key,
            version_id="bootstrap-v1",
            version_number=1,
            content=render_variables(bootstrap_fallback, variables),
            model_parameters={},
            stale_cache_used=False,
            source="bootstrap-offline",
        )
    raise RuntimeError(f"Prompt registry unavailable and no cached version for key: {prompt_key}")
