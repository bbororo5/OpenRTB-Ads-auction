import { spawn } from "node:child_process";
import { root } from "./local-runtime.js";
import { publicDockerEnvironment } from "./local-docker.js";

// Streaming a bounded source context avoids BuildKit session headers containing
// non-ASCII workspace paths. It also excludes credentials and local evidence.
export const buildInputs = ["Dockerfile", "settings.gradle", "build.gradle", "ssp-app/src", "ssp-app/build.gradle",
  "dsp-app/src", "dsp-app/build.gradle", "performance/k6", "performance/fixtures/stage8c",
  "scripts/performance/aws-vm-baseline.sh", "infrastructure/postgres"];
export async function buildImages() {
  const env = publicDockerEnvironment();
  for (const app of ["ssp", "dsp", "support"]) {
    await new Promise<void>((resolve, reject) => {
      const tar = spawn("tar", ["--no-xattrs", "-cf", "-", ...buildInputs], { cwd: root, env: { ...process.env, COPYFILE_DISABLE: "1" }, stdio: ["ignore", "pipe", "inherit"] });
      const args = ["build", "-t", `rtb-local-${app}:dev`, ...(app === "support"
        ? ["-f", "performance/fixtures/stage8c/Dockerfile"] : ["--build-arg", `APP_MODULE=${app}-app`]), "-"];
      const docker = spawn("docker", args, { cwd: root, env, stdio: ["pipe", "inherit", "inherit"] });
      tar.stdout.pipe(docker.stdin);
      docker.stdin.on("error", () => { /* docker exit is authoritative */ });
      let tarCode: number | null = null;
      let finished = false;
      const fail = (error: Error) => { if (finished) return; finished = true; clearTimeout(timer); tar.kill("SIGKILL"); docker.kill("SIGKILL"); reject(error); };
      const timer = setTimeout(() => fail(new Error(`Build timed out: ${app}`)), 10 * 60000);
      tar.on("error", fail); docker.on("error", fail);
      tar.on("close", code => { tarCode = code; if (code) fail(new Error("Build context archive failed")); });
      docker.on("close", code => {
        if (finished) return;
        if (code || tarCode !== 0) { fail(new Error(`Build failed: ${app}`)); return; }
        finished = true; clearTimeout(timer); resolve();
      });
    });
  }
}
