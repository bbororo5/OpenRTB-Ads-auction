import { createHash } from "node:crypto";
import http from "node:http";

const gatewayPort = Number(process.env.GATEWAY_PORT ?? 8080);
const externalAPort = Number(process.env.EXTERNAL_A_PORT ?? 8081);
const externalBPort = Number(process.env.EXTERNAL_B_PORT ?? 8082);
const dspBaseUrl = new URL(process.env.DSP_BASE_URL ?? "http://127.0.0.1:8083");
const authenticatedSspId = process.env.AUTHENTICATED_SSP_ID ?? "ssp-stage8c";

export function auctionBucket(auctionId) {
  return createHash("sha256").update(auctionId).digest()[0] % 4;
}

export function externalBidPrice(dsp, auctionId) {
  const bucket = auctionBucket(auctionId);
  const winsAgainstProject = dsp === "external-a" ? bucket < 2 : bucket === 2;
  return winsAgainstProject ? 3.0 : 1.5;
}

function startGateway() {
  return http.createServer(async (request, response) => {
    if (request.url === "/health") {
      respondJson(response, 200, { status: "ok", role: "gateway" });
      return;
    }

    const target = new URL(request.url ?? "/", dspBaseUrl);
    const headers = { ...request.headers };
    delete headers.host;
    delete headers["content-length"];
    delete headers["x-authenticated-ssp-id"];
    headers["x-authenticated-ssp-id"] = authenticatedSspId;

    try {
      const upstream = await fetch(target, {
        method: request.method,
        headers,
        body: request.method === "GET" || request.method === "HEAD"
          ? undefined
          : await readBody(request),
        signal: AbortSignal.timeout(2_000),
      });
      response.writeHead(upstream.status, {
        "content-type": upstream.headers.get("content-type") ?? "application/octet-stream",
      });
      response.end(Buffer.from(await upstream.arrayBuffer()));
    } catch (error) {
      respondJson(response, 503, { error: "dsp_unavailable" });
    }
  }).listen(gatewayPort, "0.0.0.0");
}

function startExternalDsp(dsp, port) {
  return http.createServer(async (request, response) => {
    if (request.url === "/health") {
      respondJson(response, 200, { status: "ok", role: dsp });
      return;
    }
    if (request.url?.startsWith("/notices/")) {
      response.writeHead(204);
      response.end();
      return;
    }
    if (request.method !== "POST" || request.url !== "/openrtb/2.6/bid") {
      response.writeHead(404);
      response.end();
      return;
    }

    try {
      const bidRequest = JSON.parse((await readBody(request)).toString("utf8"));
      const impId = bidRequest.imp?.[0]?.id;
      if (typeof bidRequest.id !== "string" || typeof impId !== "string") {
        throw new Error("invalid OpenRTB request");
      }
      const noticeBase = `http://${process.env.SUPPORT_PRIVATE_IP ?? "127.0.0.1"}:${port}`;
      respondJson(response, 200, {
        id: bidRequest.id,
        cur: "KRW",
        seatbid: [{ bid: [{
          id: `${dsp}-${bidRequest.id}`,
          impid: impId,
          price: externalBidPrice(dsp, bidRequest.id),
          nurl: `${noticeBase}/notices/win`,
          lurl: `${noticeBase}/notices/loss`,
          burl: `${noticeBase}/notices/billing`,
          exp: 2,
        }] }],
      });
    } catch (error) {
      respondJson(response, 400, { error: "invalid_request" });
    }
  }).listen(port, "0.0.0.0");
}

async function readBody(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function respondJson(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(body),
  });
  response.end(body);
}

export function startServers() {
  return [
    startGateway(),
    startExternalDsp("external-a", externalAPort),
    startExternalDsp("external-b", externalBPort),
  ];
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const servers = startServers();
  const stop = () => servers.forEach((server) => server.close());
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}
