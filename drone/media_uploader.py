import subprocess

import httpx


class BackendUrlResolver:
    def __init__(self, configured_base_url: str) -> None:
        self.configured_base_url = configured_base_url.rstrip("/")

    def candidates(self) -> list[str]:
        configured = self.configured_base_url
        candidates = [configured]

        if configured in {"http://localhost:8080", "http://127.0.0.1:8080"}:
            try:
                route = subprocess.check_output(
                    ["ip", "-4", "route", "show", "default"],
                    text=True,
                    timeout=1.0,
                )
                parts = route.split()
                if "via" in parts:
                    candidates.append(f"http://{parts[parts.index('via') + 1]}:8080")
            except (OSError, subprocess.SubprocessError, IndexError):
                pass
            candidates.append("http://host.docker.internal:8080")
            try:
                output = subprocess.check_output(
                    ["sh", "-lc", "awk '/^nameserver / {print $2; exit}' /etc/resolv.conf"],
                    text=True,
                    timeout=1.0,
                ).strip()
                if output:
                    candidates.append(f"http://{output}:8080")
            except (OSError, subprocess.SubprocessError):
                pass

        deduped = []
        for url in candidates:
            if url and url not in deduped:
                deduped.append(url)
        return deduped

    def reachable(self, timeout_s: float = 0.8) -> tuple[bool, str]:
        for base_url in self.candidates():
            try:
                with httpx.Client(timeout=timeout_s) as client:
                    response = client.get(f"{base_url}/simulation-viewer/index.html")
                if response.status_code < 500:
                    return True, base_url
            except httpx.HTTPError:
                continue
        return False, self.configured_base_url
