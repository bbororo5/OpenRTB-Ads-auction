import { doctor } from "../lib/local-runtime.js";
import { LocalSession } from "../lib/local-session.js";

try {
  const session = new LocalSession();
  switch (process.argv[2]) {
    case "doctor": console.log(JSON.stringify(await doctor(), null, 2)); break;
    case "up": await session.up(); break;
    case "status": console.log(JSON.stringify(session.status(), null, 2)); break;
    case "down": session.down(); break;
    default: throw new Error("Usage: npm run local -- doctor|up|status|down");
  }
} catch (error) { console.error(String(error)); process.exitCode = 1; }
