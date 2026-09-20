export interface RequestEvidence {
  requestId: string; traceId: string; startedAt: string; finishedAt: string;
  status: number; durationMs: number; valid: boolean; projectWon: boolean;
}
export type EvidenceRemote = (commands: string[], timeout: number) => Promise<{
  status: string; stdout: string; stderr: string;
}>;

export function parseRequestJournal(text: string, expected: number): RequestEvidence[] {
  const records = [...text.matchAll(/RTB_REQUEST ([A-Za-z0-9+/=]+)/g)].map(match =>
    JSON.parse(Buffer.from(match[1]!, "base64").toString("utf8")) as RequestEvidence);
  if (!Number.isInteger(expected) || expected <= 0 || expected > 1000 || records.length !== expected)
    throw new Error(`Request journal incomplete: ${records.length}/${expected}`);
  const ids = new Set<string>();
  for (const r of records) {
    if (!/^stage8c-[\w-]+$/.test(r.requestId) || !/^[a-f0-9]{32}$/.test(r.traceId)
      || /^0+$/.test(r.traceId) || ids.has(r.traceId)
      || !Number.isFinite(Date.parse(r.startedAt)) || !Number.isFinite(Date.parse(r.finishedAt))
      || Date.parse(r.finishedAt) < Date.parse(r.startedAt)
      || !Number.isInteger(r.status) || r.status < 0 || r.status > 599
      || !Number.isFinite(r.durationMs) || r.durationMs < 0
      || typeof r.valid !== "boolean" || typeof r.projectWon !== "boolean")
      throw new Error("Invalid request evidence");
    ids.add(r.traceId);
  }
  return records.sort((a, b) => a.startedAt.localeCompare(b.startedAt));
}

// SSM truncates stdout. Read bounded chunks, validating every byte count instead
// of treating a truncated JSON/log file as complete evidence.
export async function readRemoteEvidence(remote: EvidenceRemote, file: string): Promise<string> {
  if (!/^\/tmp\/rtb-k6-results\/[a-z0-9-]+\.(log|json)$/.test(file)) throw new Error("Unsafe evidence path");
  const sizeResult = await remote([`wc -c < ${file}`], 10);
  const size = Number(sizeResult.stdout.trim());
  if (sizeResult.status !== "Success" || !Number.isInteger(size) || size <= 0 || size > 1_000_000)
    throw new Error("Evidence file unavailable or exceeds 1 MB bound");
  const parts: Buffer[] = [];
  for (let offset = 0; offset < size; offset += 12000) {
    const count = Math.min(12000, size - offset);
    const chunk = await remote([`dd if=${file} bs=1 skip=${offset} count=${count} status=none | base64 -w0`], 10);
    const bytes = Buffer.from(chunk.stdout.trim(), "base64");
    if (chunk.status !== "Success" || bytes.length !== count) throw new Error("Truncated evidence chunk");
    parts.push(bytes);
  }
  return Buffer.concat(parts).toString("utf8");
}

export function selectTraceRequests(records: RequestEvidence[]): RequestEvidence[] {
  // Failed requests first, then slow valid requests; one fast control for comparison.
  const ordered = [...records].sort((a, b) => Number(a.valid) - Number(b.valid) || b.durationMs - a.durationMs);
  const selected = ordered.slice(0, 9);
  const control = records.filter(r => r.valid && r.status === 200).sort((a, b) => a.durationMs - b.durationMs)[0];
  if (control && !selected.includes(control)) selected.push(control);
  return selected;
}

export function requestReport(records: RequestEvidence[], summary: any): string {
  const interesting = records.filter(r => !r.valid || r.durationMs > 50);
  return ["# 요청 증거 — k6 클라이언트 측정", "",
    "UTC 시각. Grafana 서버 histogram p99와 k6 전체 실행 p99는 다른 측정값입니다.",
    "요청 duration은 k6 http_req_duration이며 연결 준비 시간 등 전체 wall time과 다릅니다.",
    "",
    `- 요청: ${records.length}`, `- 실패/계약 위반: ${records.filter(r => !r.valid).length}`,
    `- p99: ${summary.metrics.http_req_duration["p(99)"]} ms (목표 ≤50 ms)`,
    `- 낙찰: ${records.filter(r => r.projectWon).length}/${records.filter(r => r.valid).length} (유효 경매만 분모)`,
    "", "## 실패 또는 50ms 초과 요청", "",
    "| 요청 시작 UTC | 상태 | duration ms | trace ID | 요청 ID |",
    "|---|---:|---:|---|---|",
    ...interesting.map(r => `| ${r.startedAt} | ${r.status} | ${r.durationMs.toFixed(2)} | ${r.traceId} | ${r.requestId} |`),
    "", "## 다음 판단", "",
    "1. 실패가 시작 부분에 몰리는지 먼저 확인한다. 초기화가 원인이라는 결론은 아직 아니다.",
    "2. traces.json에서 해당 trace와 정상 비교 요청의 span을 비교한다. 누락 trace는 정상으로 간주하지 않는다.",
    "3. 서비스·DB·외부 호출 중 시간을 쓴 구간을 찾되 부모/자식 span 시간을 중복 합산하지 않는다.",
    "4. 가설 하나와 반증 조건을 적고 최소 변경 후 같은 cold-start 조건으로 재시험한다.",
    "", "범위: 최대 10개 trace 표본. 모든 로그/프로파일을 백업한 자료가 아닙니다.",
  ].join("\n");
}

export function traceSpans(trace: any, expectedId: string) {
  const batches = trace.batches ?? trace.resourceSpans ?? [];
  const spans = batches.flatMap((batch: any) => {
    const service = batch.resource?.attributes?.find((a: any) => a.key === "service.name")?.value?.stringValue ?? "unknown";
    return (batch.scopeSpans ?? batch.instrumentationLibrarySpans ?? []).flatMap((scope: any) =>
      (scope.spans ?? []).map((span: any) => ({ ...span, service })));
  });
  if (!spans.length) throw new Error("Trace has no spans");
  for (const span of spans) {
    // Tempo v1 JSON may encode OTLP bytes as base64; accept canonical hex too.
    const id = /^[a-f0-9]{32}$/i.test(span.traceId ?? "") ? span.traceId.toLowerCase()
      : Buffer.from(span.traceId ?? "", "base64").toString("hex");
    if (id !== expectedId || !/^\d+$/.test(String(span.startTimeUnixNano)) || !/^\d+$/.test(String(span.endTimeUnixNano))
      || BigInt(span.endTimeUnixNano) < BigInt(span.startTimeUnixNano)) throw new Error("Trace identity/timing mismatch");
  }
  return spans.sort((a: any, b: any) => Number(BigInt(a.startTimeUnixNano) - BigInt(b.startTimeUnixNano)));
}

export function evidenceHtml(records: RequestEvidence[], summary: any, traces: any[]): string {
  const escape = (v: unknown) => String(v).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
  const header = "<tr><th>시작 UTC</th><th>상태</th><th>k6 지연(ms)</th><th>trace ID</th></tr>";
  const rows = records.filter(r => !r.valid || r.durationMs > 50).map(r =>
    `<tr><td>${escape(r.startedAt)}</td><td>${r.status}</td><td>${r.durationMs.toFixed(2)}</td><td><a href="#${r.traceId}">${r.traceId}</a></td></tr>`).join("");
  const panels = traces.map(item => {
    if (item.state !== "retrieved") return `<section id="${item.request.traceId}"><h3>${item.request.traceId}</h3><p>trace 미확보: ${escape(item.error)}</p></section>`;
    const spans = traceSpans(item.trace, item.request.traceId);
    const start = BigInt(spans[0].startTimeUnixNano);
    const body = spans.map((s: any) => `<tr><td>${escape(s.service)}</td><td>${escape(s.name)}</td><td>${(Number(BigInt(s.startTimeUnixNano) - start) / 1e6).toFixed(2)}</td><td>${(Number(BigInt(s.endTimeUnixNano) - BigInt(s.startTimeUnixNano)) / 1e6).toFixed(2)}</td><td>${escape(s.spanId)}</td><td>${escape(s.parentSpanId ?? "")}</td></tr>`).join("");
    return `<section id="${item.request.traceId}"><h3>${item.request.status} · ${item.request.durationMs.toFixed(2)} ms · ${item.request.traceId}</h3><table><tr><th>서비스</th><th>span</th><th>시작 +ms</th><th>소요 ms</th><th>span ID</th><th>부모 ID</th></tr>${body}</table></section>`;
  }).join("");
  const valid = records.filter(r => r.valid).length;
  return `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>RTB 관찰 증거</title>
<style>body{font:16px system-ui;max-width:1200px;margin:32px auto;padding:0 20px;color:#172536}table{border-collapse:collapse;width:100%;font-size:14px}td,th{border:1px solid #ccd4dd;padding:8px;text-align:left;overflow-wrap:anywhere}section{margin:32px 0}h1,h2{color:#164c78}a{color:#145ea8}</style>
<h1>RTB 요청 → trace 검토</h1><p>서버를 철거한 뒤에도 볼 수 있는 정적 자료입니다. 외부 네트워크 요청 없음.</p>
<p>출처: k6 전체 실행 summary + 요청 journal. 요청 ${records.length}건 · 실패/계약 위반 ${records.length - valid}건 · p99 ${escape(summary.metrics.http_req_duration["p(99)"])}ms (목표 ≤50ms) · 낙찰 ${records.filter(r => r.projectWon).length}/${valid} 유효 경매.</p>
<p>Grafana의 서버 histogram p99와 다른 측정값입니다. journal 기록/강제 sampled trace를 켠 진단 실행이며 이전 실행과 관측 오버헤드가 다릅니다. 인과관계는 아직 확정하지 않습니다.</p>
<h2>1. 실패 또는 50ms 초과 요청</h2><p>UTC 기준. 실패가 초반에 몰리는지 확인하세요. trace는 최대 10개 표본만 보존하며, 아래에 없는 ID는 requests.json 기록만 있습니다.</p><table>${header}${rows}</table>
<h2>2. 실패·느린 요청과 정상 비교 요청의 span</h2><p>시작 +ms는 해당 trace 첫 span 기준입니다. 부모·자식 span의 시간은 겹칩니다. 더해서 총 지연으로 해석하지 마세요. trace가 존재해도 모든 서비스가 연결됐다는 보장은 없습니다.</p>${panels}
<h2>3. 가설 → 반증 실험 → 수정 → 재시험</h2><p>어느 구간이 오래 걸렸는가? 정상 요청과 무엇이 다른가? 가설 하나를 적고 확인 실험 후 최소 변경을 합니다. 같은 revision 외 조건·부하·cold-start·진단 설정으로 비교합니다.</p></html>`;
}
