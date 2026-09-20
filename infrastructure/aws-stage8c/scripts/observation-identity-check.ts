import { createObservationKey } from "../lib/observation-identity.js";

// Verify both trust and the exact auth-key capability without registering a node.
const runId = `rtb-authcheck-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}`;
const credential = await createObservationKey(runId);
await credential.revoke();
console.log("GitHub → Tailscale OIDC and one-use key creation/revocation verified; no AWS resources or tailnet devices created.");
