import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { root, requireSuccess } from "./local-runtime.js";

// Public images only. Do not alter the user's Docker Desktop credential store.
export function publicDockerEnvironment(): NodeJS.ProcessEnv {
  const host = requireSuccess(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]).trim();
  const config = resolve(root, ".local-stage8c", "public-docker");
  mkdirSync(config, { recursive: true, mode: 0o700 });
  writeFileSync(resolve(config, "config.json"), JSON.stringify({ auths: {} }), { mode: 0o600 });
  return { ...process.env, DOCKER_CONFIG: config, DOCKER_HOST: host, DOCKER_CONTEXT: "" };
}
