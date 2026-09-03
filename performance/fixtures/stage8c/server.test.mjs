import assert from "node:assert/strict";
import test from "node:test";
import http from "node:http";
import { once } from "node:events";

import { auctionBucket, externalBidPrice, forwardingHeaders, createGateway } from "./server.mjs";

test("forwarding removes standard and Connection-nominated fields before setting trusted identity", () => {
  const incoming = {
    Connection: " Upgrade, HTTP2-Settings, X-Private-Hop, X-Authenticated-Ssp-Id ",
    Upgrade: "h2c", "HTTP2-Settings": "settings", "X-Private-Hop": "private",
    "Keep-Alive": "timeout=5", "Proxy-Connection": "keep-alive",
    "Proxy-Authorization": "private", "Proxy-Authenticate": "private",
    TE: "trailers", Trailer: "X-Trailer", "Transfer-Encoding": "chunked",
    Host: "old-host", "Content-Length": "999", "X-Authenticated-Ssp-Id": "spoofed",
    "Content-Type": "application/json", "x-openrtb-version": "2.6",
    traceparent: "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
  };
  assert.deepEqual(forwardingHeaders(incoming, "trusted-ssp"), {
    "content-type": "application/json", "x-openrtb-version": "2.6",
    traceparent: incoming.traceparent, "x-authenticated-ssp-id": "trusted-ssp",
  });
  assert.equal(incoming.Upgrade, "h2c", "does not mutate incoming headers");
});

async function listening(server, t) {
  t.after(() => { server.closeAllConnections(); server.close(); });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return new URL(`http://127.0.0.1:${server.address().port}`);
}

function post(url, headers, body = '{"id":"gateway-regression"}') {
  return new Promise((resolve, reject) => {
    const request = http.request(url, { method: "POST", headers }, response => {
      let body = "";
      response.on("data", chunk => { body += chunk; });
      response.on("end", () => resolve({ status: response.statusCode, body }));
      response.on("error", reject);
    });
    request.on("error", reject);
    request.end(body);
  });
}

test("real gateway forwards Java h2c headers safely, including chunked body and trusted identity", async t => {
  const received = [];
  const upstream = await listening(http.createServer(async (request, response) => {
    let body = "";
    for await (const chunk of request) body += chunk;
    received.push({ headers: request.headers, body, url: request.url });
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"accepted":true}');
  }), t);
  const gateway = await listening(createGateway({ targetBaseUrl: upstream, sspId: "trusted-ssp" }), t);
  for (let attempt = 0; attempt < 3; attempt++) {
    const result = await post(new URL("/openrtb/2.6/bid?probe=1", gateway), {
      connection: "Upgrade, HTTP2-Settings, X-Hop, X-Authenticated-Ssp-Id",
      upgrade: "h2c", "http2-settings": "AAEAAEAAAAIAAAAAAAMAAAAAAAQBAAAAAAUAAEAAAAYABgAA",
      "x-hop": "must-not-forward", "x-authenticated-ssp-id": "spoofed",
      "content-type": "application/json", "x-openrtb-version": "2.6",
    });
    assert.equal(result.status, 200, result.body);
  }
  assert.equal(received.length, 3);
  for (const request of received) {
    assert.equal(request.headers.upgrade, undefined);
    assert.equal(request.headers["http2-settings"], undefined);
    assert.equal(request.headers["x-hop"], undefined);
    assert.equal(request.headers["transfer-encoding"], undefined);
    assert.equal(request.headers["x-authenticated-ssp-id"], "trusted-ssp");
    assert.equal(request.headers["x-openrtb-version"], "2.6");
    assert.equal(request.body, '{"id":"gateway-regression"}');
    assert.equal(request.url, "/openrtb/2.6/bid?probe=1");
  }
});

test("gateway preserves upstream no-bid and technical-failure statuses", async t => {
  let status = 204;
  const upstream = await listening(http.createServer(async (request, response) => {
    for await (const ignored of request) { /* drain request */ }
    response.writeHead(status);
    response.end();
  }), t);
  const gateway = await listening(createGateway({ targetBaseUrl: upstream }), t);
  for (const expected of [204, 503]) {
    status = expected;
    assert.equal((await post(gateway, { "content-type": "application/json" })).status, expected);
  }
});

test("external DSPs beat the project DSP in three disjoint buckets", () => {
  const observed = new Set();
  for (let index = 0; observed.size < 4; index += 1) {
    const auctionId = `auction-${index}`;
    const bucket = auctionBucket(auctionId);
    if (observed.has(bucket)) {
      continue;
    }
    observed.add(bucket);
    const aWins = externalBidPrice("external-a", auctionId) > 2;
    const bWins = externalBidPrice("external-b", auctionId) > 2;
    assert.equal(Number(aWins) + Number(bWins), bucket === 3 ? 0 : 1);
  }
});
