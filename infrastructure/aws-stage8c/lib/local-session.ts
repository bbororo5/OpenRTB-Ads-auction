import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { initialize, stateDirectory, validateProject } from "./local-config.js";
import { command, requireSuccess, root } from "./local-runtime.js";

export class LocalSession {
  readonly directory: string;
  constructor(readonly project = "rtb-local") {
    validateProject(project); this.directory = stateDirectory(project);
  }
  get env() { return { ...process.env, RTB_LOCAL_STATE: this.directory }; }
  args(args: string[]) {
    if (!existsSync(resolve(this.directory, "runtime.env"))) throw new Error("Local configuration absent; run up first");
    return ["docker", "compose", "--project-name", this.project, "--env-file", resolve(this.directory, "runtime.env"),
      "-f", resolve(root, "docker-compose.local.yml"), ...args];
  }
  compose(args: string[], timeout = 30000) { return requireSuccess(this.args(args), timeout, this.env); }
  async execute(args: string[], timeout: number) {
    const argv = this.args(args);
    return new Promise<number>((resolve, reject) => {
      const child = spawn(argv[0]!, argv.slice(1), { cwd: root, env: this.env, stdio: "inherit" });
      const timer = setTimeout(() => child.kill("SIGTERM"), timeout);
      child.once("error", e => { clearTimeout(timer); reject(e); });
      child.once("close", code => { clearTimeout(timer); resolve(code ?? 1); });
    });
  }
  async up(build = true) {
    initialize(this.project);
    if (build && await this.execute(["build"], 20 * 60_000)) throw new Error("Local build failed");
    if (await this.execute(["up", "-d", "--wait", "--wait-timeout", "120"], 180000)) throw new Error("Local startup failed");
    await waitFor(async () => (await fetch("http://127.0.0.1:18080/health/ready", { signal: AbortSignal.timeout(2000) })).ok, 90000);
    const valid = this.compose(["exec", "-T", "ledger-store", "psql", "-U", "postgres", "-d", "rtb", "-Atc",
      "SELECT count(*) FROM regional_campaign_budget WHERE campaign_id='campaign-1' AND campaign_ends_at > now()"]);
    if (valid.trim() !== "1") throw new Error("Database campaign expired/missing; existing data was preserved");
  }
  status() { return JSON.parse(this.compose(["ps", "--all", "--format", "json"]).trim().split("\n").filter(Boolean).map(s => s).join(",").replace(/^/, "[").replace(/$/, "]")); }
  down(volumes = false) { this.compose(["down", "--remove-orphans", ...(volumes ? ["--volumes"] : [])], 120000); }
}
export async function waitFor(check: () => Promise<boolean>, timeout: number, interval = 1000) {
  const deadline = Date.now() + timeout;
  do {
    try { if (await check()) return; } catch { /* bounded readiness retry */ }
    await new Promise(r => setTimeout(r, interval));
  } while (Date.now() < deadline);
  throw new Error("Readiness timed out");
}
