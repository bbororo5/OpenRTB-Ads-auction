import { doctor } from "../lib/local-runtime.js";

try {
  if (process.argv[2] !== "doctor") throw new Error("Usage: npm run local -- doctor");
  console.log(JSON.stringify(await doctor(), null, 2));
} catch (error) { console.error(String(error)); process.exitCode = 1; }
