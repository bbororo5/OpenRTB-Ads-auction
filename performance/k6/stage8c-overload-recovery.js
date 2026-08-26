import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const NORMAL_RPS = Number(__ENV.NORMAL_RPS || 500);
const OVERLOAD_RPS = Number(__ENV.OVERLOAD_RPS || 1000);
const RECOVERY_RPS = Number(__ENV.RECOVERY_RPS || 100);
const NORMAL_DURATION = __ENV.NORMAL_DURATION || "1m";
const OVERLOAD_DURATION = __ENV.OVERLOAD_DURATION || "1m";
const RECOVERY_DURATION = __ENV.RECOVERY_DURATION || "30s";
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 2000);
const MAX_VUS = Number(__ENV.MAX_VUS || 4000);

const protectedDuration = new Trend("stage8c_protected_duration", true);
const protectedAuctions = new Counter("stage8c_protected_auctions");
const explicitRejections = new Counter("stage8c_explicit_rejections");
const unexpectedFailures = new Counter("stage8c_unexpected_failures");

export const options = {
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "p(99.9)"],
  scenarios: {
    normal: arrivalPhase("normal", 0, NORMAL_RPS, NORMAL_DURATION),
    overload: arrivalPhase("overload", NORMAL_DURATION, OVERLOAD_RPS, OVERLOAD_DURATION),
    recovery: arrivalPhase(
      "recovery",
      addDurations(NORMAL_DURATION, OVERLOAD_DURATION),
      RECOVERY_RPS,
      RECOVERY_DURATION,
    ),
  },
  thresholds: {
    dropped_iterations: ["count==0"],
    "stage8c_protected_duration{phase:normal}": ["p(99)<=50"],
    "stage8c_protected_duration{phase:overload}": ["p(99)<=50"],
    "stage8c_protected_duration{phase:recovery}": ["p(99)<=50"],
    "stage8c_protected_auctions{phase:overload}": ["count>=24000"],
    "stage8c_protected_auctions{phase:recovery}": ["count>=3000"],
    "stage8c_explicit_rejections{phase:overload}": ["count>0"],
    stage8c_unexpected_failures: ["count==0"],
  },
};

export function normal() {
  requestAuction("normal");
}

export function overload() {
  requestAuction("overload");
}

export function recovery() {
  requestAuction("recovery");
}

function requestAuction(phase) {
  const requestId = `stage8c-${phase}-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${BASE_URL}/publisher/auction`,
    JSON.stringify(providerAuction(requestId)),
    {
      headers: { "Content-Type": "application/json" },
      tags: { test_stage: "8c", phase },
    },
  );

  if (response.status === 200) {
    protectedAuctions.add(1, { phase });
    protectedDuration.add(response.timings.duration, { phase });
  } else if (response.status === 503) {
    explicitRejections.add(1, { phase });
  } else {
    unexpectedFailures.add(1, { phase, status: String(response.status) });
  }

  if (phase === "normal" || phase === "recovery") {
    check(response, {
      [`${phase} request succeeds`]: () => response.status === 200,
    });
  } else {
    check(response, {
      "overload is served or explicitly rejected": () =>
        response.status === 200 || response.status === 503,
    });
  }
}

function arrivalPhase(exec, startTime, rate, duration) {
  return {
    executor: "constant-arrival-rate",
    exec,
    startTime: typeof startTime === "number" ? `${startTime}s` : startTime,
    rate,
    timeUnit: "1s",
    duration,
    preAllocatedVUs: PRE_ALLOCATED_VUS,
    maxVUs: MAX_VUS,
  };
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

function addDurations(left, right) {
  return `${durationSeconds(left) + durationSeconds(right)}s`;
}

function durationSeconds(value) {
  const match = /^(\d+)(ms|s|m)$/.exec(value);
  if (!match) {
    throw new Error(`Unsupported duration: ${value}`);
  }
  const amount = Number(match[1]);
  if (match[2] === "ms") {
    return amount / 1000;
  }
  return match[2] === "m" ? amount * 60 : amount;
}
