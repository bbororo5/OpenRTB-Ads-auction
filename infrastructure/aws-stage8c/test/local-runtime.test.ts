import assert from "node:assert/strict";
import test from "node:test";
import net from "node:net";
import { command, portAvailable, root } from "../lib/local-runtime.js";

test("local command does not require AWS and resolves repository root", () => {
  assert.match(root, /\/$/);
  assert.equal(command([process.execPath, "-e", "process.exit(7)"]).code, 7);
});
test("doctor detects occupied loopback ports", async () => {
  const server = net.createServer();
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  try { assert.equal(await portAvailable((server.address() as net.AddressInfo).port), false); }
  finally { server.close(); }
});
