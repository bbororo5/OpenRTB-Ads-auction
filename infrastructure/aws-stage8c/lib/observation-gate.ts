export type ObservationPhase = "screen-ready" | "review-done";
export interface Gate {
  runId: string; phase: ObservationPhase; nonce: string; expiresAt: string;
}
export function gateKey(runId: string, phase: string, approval = false): string {
  if (!/^rtb-[a-z0-9-]{1,64}$/.test(runId) || !["screen-ready", "review-done"].includes(phase)) {
    throw new Error("Invalid observation run or phase");
  }
  return `${runId}/observation/${phase}${approval ? "-approval" : ""}.json`;
}
export function isApproval(gate: Gate, value: any, now: number): boolean {
  return now < Date.parse(gate.expiresAt) && value?.runId === gate.runId
    && value?.phase === gate.phase && value?.nonce === gate.nonce && value?.approved === true;
}
export async function waitForObservation(gate: Gate, io: {
  now(): number; checkCancellation(): void; publish(gate: Gate): Promise<void>;
  read(): Promise<unknown>; pause(): Promise<void>;
}): Promise<void> {
  gateKey(gate.runId, gate.phase);
  if (!Number.isFinite(Date.parse(gate.expiresAt))) throw new Error("Invalid gate deadline");
  io.checkCancellation();
  if (io.now() >= Date.parse(gate.expiresAt)) throw new Error("Observation time budget exhausted");
  await io.publish(gate);
  while (io.now() < Date.parse(gate.expiresAt)) {
    io.checkCancellation();
    const value = await io.read();
    io.checkCancellation();
    if (isApproval(gate, value, io.now())) return;
    await io.pause();
  }
  throw new Error(`Observation approval timed out: ${gate.phase}; cleanup required`);
}
