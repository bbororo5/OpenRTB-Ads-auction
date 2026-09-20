import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { bucketName } from "./experiment-identity-stack.js";

const bucket = bucketName("333982363617", "ap-northeast-2");
export function observationAws(args: string[]): any {
  return JSON.parse(execFileSync("aws", [...args, "--region", "ap-northeast-2", "--output", "json", "--no-cli-pager",
    "--cli-connect-timeout", "5", "--cli-read-timeout", "10"],
  { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: 30_000 }) || "null");
}
export function observationObject(key: string, value?: unknown): any {
  const folder = mkdtempSync(path.join(tmpdir(), "rtb-observation-"));
  const file = path.join(folder, "object.json");
  try {
    if (value !== undefined) {
      writeFileSync(file, JSON.stringify(value), { mode: 0o600 });
      observationAws(["s3api", "put-object", "--bucket", bucket, "--key", key, "--body", file]);
      return;
    }
    try { observationAws(["s3api", "get-object", "--bucket", bucket, "--key", key, file]); }
    catch (error) {
      // Permission, credential and network failures must not become approval waits.
      if (/\(NoSuchKey\)/.test(String((error as any).stderr))) return undefined;
      throw error;
    }
    return JSON.parse(readFileSync(file, "utf8"));
  } finally { rmSync(folder, { recursive: true, force: true }); }
}
