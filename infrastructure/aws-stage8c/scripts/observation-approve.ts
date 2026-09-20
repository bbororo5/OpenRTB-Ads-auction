import { gateKey, isApproval, type Gate } from "../lib/observation-gate.js";
import { observationAws, observationObject } from "../lib/observation-store.js";

try {
  const runId = process.env.OBSERVATION_RUN_ID ?? "";
  const phase = process.env.OBSERVATION_PHASE ?? "";
  const key = gateKey(runId, phase);
  if (observationAws(["sts", "get-caller-identity"]).Account !== "333982363617") throw new Error("Wrong account");
  const lease = observationAws(["cloudformation", "describe-stacks", "--stack-name", "RtbStage8cLease"]).Stacks[0];
  const tags = Object.fromEntries(lease.Tags.map((tag: any) => [tag.Key, tag.Value]));
  if (tags.Project !== "low-latency-rtb" || tags.Stage !== "8c" || tags.RunId !== runId
    || !["CREATE_COMPLETE", "UPDATE_COMPLETE"].includes(lease.StackStatus)
    || !Number.isFinite(Date.parse(tags.ExpiresAt)) || Date.now() >= Date.parse(tags.ExpiresAt)) {
    throw new Error("No active lease for this run");
  }
  const gate = observationObject(key) as Gate | undefined;
  if (!gate || gate.runId !== runId || gate.phase !== phase || !gate.nonce) throw new Error("This gate is not open");
  const approval = { ...gate, approved: true };
  if (!isApproval(gate, approval, Date.now())) throw new Error("Gate expired");
  observationObject(gateKey(runId, phase, true), approval);
  console.log(`Approved ${runId} ${phase}; this does not extend the experiment deadline.`);
} catch (error) { console.error(String(error)); process.exitCode = 1; }
