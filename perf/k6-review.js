// CopilotGuard load test: exercises the full review pipeline (redaction -> LLM ->
// Docker validation -> dual persistence) against a stubbed Anthropic API.
//
// Run:
//   k6 run --vus 5 --duration 60s --summary-export=perf/results/<ts>-summary.json perf/k6-review.js
//   BASE_URL=http://host:port k6 run ...
//
// The --summary-export file contains per-metric aggregates (throughput via the
// http_reqs rate, latency percentiles via http_req_duration).
import http from 'k6/http';
import {check} from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Small fixture diff with a planted off-by-one (no secrets, so redaction does not block).
const DIFF = [
    'diff --git a/src/main/java/com/example/Report.java b/src/main/java/com/example/Report.java',
    'index 1111111..2222222 100644',
    '--- a/src/main/java/com/example/Report.java',
    '+++ b/src/main/java/com/example/Report.java',
    '@@ -8,6 +8,9 @@ public class Report {',
    '     public String summarize(List<String> items) {',
    '         StringBuilder out = new StringBuilder();',
    '+        // eval-fixture: perf_off_by_one',
    '+        for (int i = 0; i <= items.size(); i++) {',
    '+            out.append(items.get(i));',
    '+        }',
    '         return out.toString();',
    '     }',
].join('\n');

const PAYLOAD = JSON.stringify({
    diff: DIFF,
    allowRedactedSend: false,
    testPromptTemplateId: 'generate_tests',
    reviewPromptTemplateId: 'review_diff',
});

export default function () {
    const response = http.post(`${BASE_URL}/api/v1/reviews`, PAYLOAD, {
        headers: {'Content-Type': 'application/json'},
        timeout: '120s',
    });
    check(response, {
        'review accepted': (r) => r.status === 201 || r.status === 422,
    });
}
