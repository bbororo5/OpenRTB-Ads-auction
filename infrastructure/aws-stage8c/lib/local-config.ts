import { randomBytes, createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { root } from "./local-runtime.js";

export function validateProject(project: string) {
  if (!/^rtb-local(?:-[a-z0-9]+)*$/.test(project)) throw new Error("Invalid local project name");
  return project;
}
export function stateDirectory(project: string) { return resolve(root, ".local-stage8c", validateProject(project)); }
export function initialize(project: string, directory = stateDirectory(project), now = Date.now()) {
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const campaignFile = resolve(directory, "campaigns.json");
  if (!existsSync(campaignFile)) {
    const campaign = { version: "v1", campaigns: [{ id: "campaign-1", active: true, bidCpmMilliKrw: 2000,
      startsAt: new Date(now - 300000).toISOString(), endsAt: new Date(now + 7 * 86400000).toISOString(),
      creatives: [{ id: "creative-1", width: 300, height: 250 }] }] };
    writeFileSync(campaignFile, JSON.stringify(campaign), { mode: 0o600 });
  }
  const raw = readFileSync(campaignFile, "utf8");
  const campaign = JSON.parse(raw).campaigns?.[0];
  if (!campaign || !Number.isFinite(Date.parse(campaign.endsAt)) || Date.parse(campaign.endsAt) <= now)
    throw new Error("Local campaign expired/invalid; preserve evidence and create a fresh verification session");
  const envFile = resolve(directory, "runtime.env");
  if (!existsSync(envFile)) writeFileSync(envFile, [
    `LOCAL_DB_PASSWORD=${randomBytes(24).toString("hex")}`,
    `LOCAL_NOTICE_KEY=${randomBytes(32).toString("base64")}`,
    `LOCAL_RENDER_KEY=${randomBytes(32).toString("base64")}`,
    `LOCAL_CAMPAIGN_SHA=${createHash("sha256").update(raw).digest("hex")}`,
  ].join("\n") + "\n", { mode: 0o600 });
  const env = readFileSync(envFile, "utf8");
  if (!env.includes(`LOCAL_CAMPAIGN_SHA=${createHash("sha256").update(raw).digest("hex")}`))
    throw new Error("Campaign checksum mismatch; refusing to silently regenerate runtime identity");
  for (const name of ["LOCAL_DB_PASSWORD", "LOCAL_NOTICE_KEY", "LOCAL_RENDER_KEY"])
    if (!new RegExp(`^${name}=.+$`, "m").test(env)) throw new Error(`Missing ${name}`);
  return { directory, envFile };
}
