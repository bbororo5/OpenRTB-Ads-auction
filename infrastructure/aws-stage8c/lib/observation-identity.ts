// Public identifiers, not secrets. Trust is restricted to this repository's main ref.
export const observationClientId = "Tt8iPTfD8D11CNTRL-kUQ6XPCmRZ11CNTRL";
export const observationAudience = `api.tailscale.com/${observationClientId}`;
export const observationTag = "tag:rtb-observer";

type Request = typeof fetch;
async function json(request: Request, url: string, init: RequestInit, label: string): Promise<any> {
  // Never propagate request/response bodies: they may contain credentials.
  try {
    const response = await request(url, { ...init, redirect: "error", signal: AbortSignal.timeout(20_000) });
    if (!response.ok) throw new Error();
    return await response.json();
  } catch { throw new Error(`${label} failed (credential details suppressed)`); }
}
function secret(value: unknown, label: string): string {
  if (typeof value !== "string" || !value || /[\r\n]/.test(value)) throw new Error(`Invalid ${label}`);
  if (process.env.GITHUB_ACTIONS === "true") console.log(`::add-mask::${value}`);
  return value;
}
export async function observationAccessToken(request: Request = fetch, env = process.env): Promise<string> {
  if (env.GITHUB_ACTIONS !== "true" || !env.ACTIONS_ID_TOKEN_REQUEST_URL || !env.ACTIONS_ID_TOKEN_REQUEST_TOKEN)
    throw new Error("Observation identity requires GitHub Actions OIDC; no local AWS login fallback");
  const url = new URL(env.ACTIONS_ID_TOKEN_REQUEST_URL);
  if (url.protocol !== "https:" || !url.hostname.endsWith(".actions.githubusercontent.com"))
    throw new Error("Unexpected GitHub OIDC endpoint");
  url.searchParams.set("audience", observationAudience);
  const identity = await json(request, url.toString(), {
    headers: { Authorization: `Bearer ${env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}` },
  }, "GitHub OIDC");
  const jwt = secret(identity.value, "identity token");
  const token = await json(request, "https://api.tailscale.com/api/v2/oauth/token-exchange", {
    method: "POST", body: new URLSearchParams({ client_id: observationClientId, jwt }),
  }, "Tailscale token exchange");
  return secret(token.access_token, "access token");
}
export async function createObservationKey(runId: string, request: Request = fetch, env = process.env): Promise<{ key: string; revoke: () => Promise<void> }> {
  if (!/^rtb-[a-z0-9-]{1,64}$/.test(runId)) throw new Error("Invalid run ID");
  const token = await observationAccessToken(request, env);
  const root = "https://api.tailscale.com/api/v2/tailnet/-/keys";
  const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
  const value = await json(request, root, { method: "POST", headers, body: JSON.stringify({
    description: runId, expirySeconds: 600,
    capabilities: { devices: { create: { reusable: false, ephemeral: true, preauthorized: true, tags: [observationTag] } } },
  }) }, "One-use observation key creation");
  if (typeof value.id !== "string" || !/^[a-zA-Z0-9_-]+$/.test(value.id)) throw new Error("Invalid auth key ID");
  return { key: secret(value.key, "auth key"), revoke: async () => {
    try {
      const response = await request(`${root}/${value.id}`, { method: "DELETE", headers, redirect: "error", signal: AbortSignal.timeout(20_000) });
      // A consumed non-reusable key may already have disappeared.
      if (!response.ok && response.status !== 404) throw new Error();
    } catch { throw new Error("Observation auth key revocation failed; key expires within 10 minutes"); }
  } };
}
