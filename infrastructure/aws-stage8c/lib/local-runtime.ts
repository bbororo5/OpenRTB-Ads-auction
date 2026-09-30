import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import net from "node:net";

export const root = fileURLToPath(new URL("../../../", import.meta.url));
export function command(args: string[], timeout = 30_000, env = process.env) {
  const r = spawnSync(args[0]!, args.slice(1), { cwd: root, env, encoding: "utf8", timeout, maxBuffer: 4_000_000 });
  if (r.error) throw r.error;
  return { code: r.status ?? 1, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
}
export function requireSuccess(args: string[], timeout?: number, env?: NodeJS.ProcessEnv) {
  const r = command(args, timeout, env);
  if (r.code) throw new Error(`${args.slice(0, 3).join(" ")} failed: ${r.stderr.slice(-2000)}`);
  return r.stdout;
}
export async function portAvailable(port: number): Promise<boolean> {
  return new Promise(resolve => {
    const server = net.createServer();
    server.once("error", () => resolve(false));
    server.listen(port, "127.0.0.1", () => server.close(() => resolve(true)));
  });
}
export async function doctor() {
  const docker = JSON.parse(requireSuccess(["docker", "info", "--format", "{{json .}}"]));
  const compose = requireSuccess(["docker", "compose", "version", "--short"]).trim();
  const ports = Object.fromEntries(await Promise.all([18080, 3000, 9090, 3200, 3100, 4040, 4317, 4318, 9464, 13133]
    .map(async p => [p, await portAvailable(p)])));
  return { dockerMemoryGiB: docker.MemTotal / 1024 ** 3, dockerCpus: docker.NCPU, compose, ports,
    node: process.version, root: resolve(root), awsRequired: false };
}
