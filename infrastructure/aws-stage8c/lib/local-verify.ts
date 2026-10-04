import net from "node:net";
import { randomUUID } from "node:crypto";
import { LocalSession } from "./local-session.js";
import { runLoad, save } from "./local-evidence.js";

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
  try {
    await session.up();
    save(session.directory, "session.json", { project: session.project, ports: session.ports, at: new Date().toISOString() });
    const result = await runLoad(session, "observe");
    console.log(`Verification evidence: ${result.directory}`);
    return result.code || (result.collected.complete ? 0 : 1);
  } finally { session.down(true); }
}
