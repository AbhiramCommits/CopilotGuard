import time

import requests


class ServiceClient:

    def __init__(self, base_url, timeout=300):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def create_review(self, diff, conventions_yaml, test_template, review_template):
        payload = {
            "diff": diff,
            "conventions": conventions_yaml,
            "allowRedactedSend": False,
            "testPromptTemplateId": test_template,
            "reviewPromptTemplateId": review_template,
        }
        start = time.monotonic()
        try:
            response = requests.post(
                f"{self.base_url}/api/v1/reviews", json=payload, timeout=self.timeout
            )
        except requests.RequestException as exc:
            return {"error": str(exc)}, None
        latency_ms = (time.monotonic() - start) * 1000.0
        try:
            return response.json(), latency_ms
        except ValueError:
            return {
                "error": f"non-json response {response.status_code}: {response.text[:200]}"
            }, latency_ms
