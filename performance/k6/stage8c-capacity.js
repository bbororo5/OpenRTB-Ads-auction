import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const RPS = Number(__ENV.RPS || 500);
const DURATION = __ENV.DURATION || "10m";
const PROJECT_DSP_ID = __ENV.PROJECT_DSP_ID || "project-dsp";
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || Math.max(500, RPS * 2));
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(1000, RPS * 4));

const technicalFailures = new Counter("stage8c_technical_failures");
const invalidAuctions = new Counter("stage8c_invalid_auctions");
const projectDspWinRate = new Rate("stage8c_project_dsp_win_rate");

export const options = {
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "p(99.9)"],
  scenarios: {
    normal_peak: {
      executor: "constant-arrival-rate",
      rate: RPS,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    checks: ["rate==1"],
    http_req_failed: ["rate==0"],
    http_req_duration: ["p(99)<=50"],
    dropped_iterations: ["count==0"],
    stage8c_technical_failures: ["count==0"],
    stage8c_invalid_auctions: ["count==0"],
    stage8c_project_dsp_win_rate: ["rate>=0.20", "rate<=0.28"],
  },
};

export default function () {
  const requestId = `stage8c-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${BASE_URL}/publisher/auction`,
    JSON.stringify(providerAuction(requestId)),
    {
      headers: { "Content-Type": "application/json" },
      tags: { test_stage: "8c", phase: "normal_peak" },
    },
  );

  const result = parseJson(response);
  const slot = result?.slots?.[0];
  const valid = response.status === 200
    && result.auctionId
    && result.slots?.length === 1
    && slot.impId === "imp-1"
    && slot.cpmKrw > 0
    && typeof slot.renderProof === "string"
    && slot.renderProof.length > 0;

  technicalFailures.add(response.status !== 200);
  invalidAuctions.add(!valid);
  if (valid) {
    projectDspWinRate.add(slot.dspId === PROJECT_DSP_ID);
  }
  check(response, {
    "auction succeeds": () => response.status === 200,
    "auction result preserves the slot contract": () => valid,
  });
}

function providerAuction(requestId) {
  return {
    providerId: "provider-stage8c",
    providerKeyId: "key-stage8c",
    providerRequestId: requestId,
    tmaxMillis: 180,
    slots: [{
      impId: "imp-1",
      width: 300,
      height: 250,
      floorCpmKrw: 1.000,
    }],
  };
}

function parseJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}
