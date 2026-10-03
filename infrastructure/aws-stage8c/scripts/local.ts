import { doctor } from "../lib/local-runtime.js";
import { LocalSession } from "../lib/local-session.js";
import { backendStatus } from "../lib/local-telemetry.js";
import { runLoad } from "../lib/local-evidence.js";

try {
  const session = new LocalSession("rtb-local", process.argv.includes("--profiles"));
  switch (process.argv[2]) {
    case "doctor": console.log(JSON.stringify(await doctor(), null, 2)); break;
    case "up": await session.up(); break;
    case "status": {
      const containers = session.status();
      const profiler = containers.find((c: any) => c.Service === "otel-ebpf-profiler");
      console.log(JSON.stringify({ containers, telemetry: await backendStatus(),
        profiling: profiler ? { state: profiler.State, note: "Container state is not proof of profile ingestion; Linux VM only" } : { state: "not-enabled" } }, null, 2));
      break;
    }
    case "down": session.down(); break;
    case "smoke": case "observe": {
      const result = await runLoad(session, process.argv[2]);
      console.log(`Evidence: ${result.directory}`); process.exitCode = result.code; break;
    }
    default: throw new Error("Usage: npm run local -- doctor|up|status|down");
  }
} catch (error) { console.error(String(error)); process.exitCode = 1; }
