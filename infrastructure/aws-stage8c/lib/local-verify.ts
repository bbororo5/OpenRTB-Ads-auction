import net from "node:net";
import { randomUUID } from "node:crypto";
import { LocalSession } from "./local-session.js";
import { runLoad, save } from "./local-evidence.js";
import { withCleanup, installSignalCleanup } from "./local-lifecycle.js";
import { initialize } from "./local-config.js";
import { buildImages } from "./local-build.js";
import { acceptance } from "./local-acceptance.js";
import { diagnostics } from "./local-diagnostics.js";

export async function verificationSession() {
  const names = ["ssp", "grafana", "tempo", "loki", "prometheus", "pyroscope", "collector", "grpc", "otlp", "metrics", "profiler"];
  const reservations: net.Server[] = [];
  const ports: Record<string, number> = {};
  try {
    for (const name of names) {
      const server = net.createServer(); reservations.push(server);
      await new Promise<void>((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
      ports[name] = (server.address() as net.AddressInfo).port;
    }
  } finally { for (const server of reservations) server.close(); }
  // Docker owns the final bind. A port race must fail, never fall back to another session.
  return new LocalSession(`rtb-local-verify-${randomUUID().slice(0, 8)}`, false, ports);
}
export async function verify() {
  const session = await verificationSession();
  // Builds create no running services. Install cleanup before the first runtime mutation.
  await buildImages();
  initialize(session.project);
  const cleanup = () => {
    session.down(true);
    save(session.directory, "cleanup.json", { complete: true, at: new Date().toISOString(), project: session.project });
  };
  const removeHandlers = installSignalCleanup(cleanup);
  try { return await withCleanup(async () => {
    try {
    await session.up(false);
    save(session.directory, "session.json", { project: session.project, ports: session.ports, at: new Date().toISOString() });
    const { result, infrastructurePassed } = await acceptance(session);
    console.log(`Verification evidence: ${result.directory}`);
    console.log(`Environment/evidence acceptance: ${infrastructurePassed ? "PASS" : "FAIL"}; k6 exit: ${result.code}`);
    return result.code || (infrastructurePassed ? 0 : 1);
    } catch (error) {
      save(session.directory, "failure.json", { error: String(error), at: new Date().toISOString() });
      try { save(session.directory, "failure-runtime.json", diagnostics(session)); } catch { /* retain primary error */ }
      throw error;
    }
  }, cleanup); } finally { removeHandlers(); }
}
