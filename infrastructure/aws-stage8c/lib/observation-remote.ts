import { execFileSync } from "node:child_process";
import type { Remote } from "./observation-access.js";

export function observerRemote(instanceId: string, checkCancellation = () => {}): Remote {
  if (!/^i-[a-f0-9]{8,17}$/.test(instanceId)) throw new Error("Invalid observer instance ID");
  const aws = (args: string[]): any => {
    try {
      return JSON.parse(execFileSync("aws", [...args, "--region", "ap-northeast-2", "--output", "json", "--no-cli-pager",
        "--cli-connect-timeout", "10", "--cli-read-timeout", "20"], {
        encoding: "utf8", timeout: 30_000, stdio: ["ignore", "pipe", "pipe"],
      }));
    } catch (error) {
      const stderr = error && typeof error === "object" && "stderr" in error ? String(error.stderr) : "";
      if (stderr.includes("InvocationDoesNotExist")) return undefined;
      // Do not include the command, output or nested cause in diagnostics.
      throw new Error("Observation SSM request failed");
    }
  };
  return async (script, timeoutSeconds) => {
    checkCancellation();
    const sent = aws(["ssm", "send-command", "--instance-ids", instanceId, "--document-name", "AWS-RunShellScript",
      "--timeout-seconds", String(timeoutSeconds), "--parameters",
      JSON.stringify({ commands: [script], executionTimeout: [String(timeoutSeconds)] })]);
    const commandId = sent?.Command?.CommandId;
    if (!commandId) throw new Error("Missing observation SSM command ID");
    const deadline = Date.now() + (timeoutSeconds + 30) * 1000;
    while (Date.now() < deadline) {
      checkCancellation();
      const result = aws(["ssm", "get-command-invocation", "--command-id", commandId, "--instance-id", instanceId]);
      if (result?.Status === "Success" && result.ResponseCode === 0) return result.StandardOutputContent ?? "";
      if (result && !["Pending", "InProgress", "Delayed"].includes(result.Status))
        throw new Error(`Observation remote command failed (${result.Status}); sensitive output suppressed`);
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    throw new Error("Observation remote command timed out");
  };
}
