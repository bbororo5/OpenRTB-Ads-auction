import { createInterface } from "node:readline";
import { createGateway } from "../../../../performance/fixtures/stage8c/server.mjs";

// Only routing control lives here; HTTP behavior is the deployed implementation.
const targetBaseUrl = new URL("http://127.0.0.1:1");
const server = createGateway({ targetBaseUrl, sspId: "ssp-system-test" });
server.listen(0, "127.0.0.1", () => {
  console.log(`http://127.0.0.1:${server.address().port}/`);
});
const commands = createInterface({ input: process.stdin });
commands.on("line", line => {
  targetBaseUrl.href = new URL(line).href;
  console.log("configured");
});
const stop = () => {
  commands.close();
  server.closeAllConnections();
  server.close();
};
process.on("SIGTERM", stop);
process.on("SIGINT", stop);
process.stdin.on("end", stop);
