import assert from "node:assert/strict";
import test from "node:test";
import { evidenceHtml, parseRequestJournal, readRemoteEvidence, requestReport, selectTraceRequests, traceSpans, type RequestEvidence } from "../lib/request-evidence.js";

const row: RequestEvidence = { requestId: "stage8c-1-0-123", traceId: "123456789abcdef0123456789abcdef0",
  startedAt: "2026-09-20T12:00:00Z", finishedAt: "2026-09-20T12:00:01Z",
  status: 504, durationMs: 180, valid: false, projectWon: false };
const line = (r: RequestEvidence) => `time=x level=info msg="RTB_REQUEST ${Buffer.from(JSON.stringify(r)).toString("base64")}" source=console`;

test("journal preserves failed requests and detects truncation, duplicate IDs and invalid rows", () => {
  assert.deepEqual(parseRequestJournal(line(row), 1), [row]);
  assert.throws(() => parseRequestJournal(line(row), 2), /incomplete/);
  assert.throws(() => parseRequestJournal(`${line(row)}\n${line(row)}`, 2), /Invalid/);
  assert.throws(() => parseRequestJournal(line({ ...row, traceId: "../escape" }), 1), /Invalid/);
  assert.throws(() => parseRequestJournal(line({ ...row, durationMs: -1 }), 1), /Invalid/);
  assert.throws(() => parseRequestJournal("", 0), /incomplete/);
});

test("trace validation accepts Tempo base64 IDs but refuses empty/wrong traces; report escapes names", () => {
  const trace = { batches: [{ resource: { attributes: [{ key: "service.name", value: { stringValue: "rtb-ssp" } }] },
    scopeSpans: [{ spans: [{ traceId: Buffer.from(row.traceId, "hex").toString("base64"), spanId: "01", name: "<script>alert(1)</script>",
      startTimeUnixNano: "1000000", endTimeUnixNano: "2000000" }] }] }] };
  assert.equal(traceSpans(trace, row.traceId).length, 1);
  assert.throws(() => traceSpans({}, row.traceId), /no spans/);
  assert.throws(() => traceSpans(trace, "f".repeat(32)), /mismatch/);
  const html = evidenceHtml([row], { metrics: { http_req_duration: { "p(99)": 180 } } }, [{ request: row, state: "retrieved", trace }]);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /&lt;script&gt;/);
  assert.match(html, /rtb-ssp/);
  assert.match(html, /부모·자식/);
});

test("chunk transport survives stdout limits and rejects missing bytes", async () => {
  const contents = "a".repeat(35000);
  const offsets: number[] = [];
  const result = await readRemoteEvidence(async commands => {
    if (commands[0]!.startsWith("wc")) return { status: "Success", stdout: String(contents.length), stderr: "" };
    const [, offset, count] = commands[0]!.match(/skip=(\d+) count=(\d+)/)!;
    offsets.push(Number(offset));
    return { status: "Success", stdout: Buffer.from(contents.slice(Number(offset), Number(offset) + Number(count))).toString("base64"), stderr: "" };
  }, "/tmp/rtb-k6-results/test-requests.log");
  assert.equal(result, contents);
  assert.deepEqual(offsets, [0, 12000, 24000]);
  await assert.rejects(readRemoteEvidence(async () => ({ status: "Success", stdout: "100", stderr: "" }), "/tmp/rtb-k6-results/test.log"), /Truncated/);
  await assert.rejects(readRemoteEvidence(async () => ({ status: "Failed", stdout: "", stderr: "" }), "/tmp/rtb-k6-results/test.log"), /unavailable/);
  await assert.rejects(readRemoteEvidence(async () => { throw Error("must not execute"); }, "/etc/passwd"), /Unsafe/);
});

test("trace selection is capped, prioritizes failures and includes a fast comparison", () => {
  const rows = Array.from({ length: 20 }, (_, i) => ({ ...row, traceId: i.toString(16).padStart(32, "0"),
    valid: i > 2, status: i > 2 ? 200 : 504, durationMs: i + 1 }));
  const selected = selectTraceRequests(rows);
  assert.equal(selected.length, 10);
  assert.equal(selected.filter(r => !r.valid).length, 3);
  assert.ok(selected.includes(rows[3]!));
  const report = requestReport([row], { metrics: { http_req_duration: { "p(99)": 180 } } });
  assert.match(report, /504/);
  assert.match(report, /목표 ≤50/);
  assert.match(report, /누락 trace는 정상으로 간주하지 않는다/);
});
