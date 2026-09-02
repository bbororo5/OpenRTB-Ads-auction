import { execFileSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { App, BootstraplessSynthesizer } from "aws-cdk-lib";
import { ExperimentControlStack } from "../lib/experiment-control-stack.js";

const account = "333982363617";
const region = "ap-northeast-2";
const [command = "synth", acknowledgement] = process.argv.slice(2);
const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
try {
  if (!["synth", "install"].includes(command)) throw new Error("Use synth or install --ack-cost");
  if (command === "install") {
    if (acknowledgement !== "--ack-cost") throw new Error("Persistent Lambda, logs and asset storage may incur charges. Require --ack-cost.");
    const identity = JSON.parse(execFileSync("aws", ["sts", "get-caller-identity", "--output", "json"], { encoding: "utf8" }));
    if (identity.Account !== account) throw new Error("Wrong AWS account");
  }
  const app = new App({ outdir: path.join(directory, "cdk.out", "control") });
  const stack = new ExperimentControlStack(app, "RtbStage8cControl", { env: { account, region }, synthesizer: new BootstraplessSynthesizer() });
  const assembly = app.synth();
  const template = path.join(assembly.directory, assembly.getStackByName(stack.stackName).templateFile);
  console.log(`Control plane template: ${template}`);
  if (command === "install") execFileSync("aws", ["cloudformation", "deploy", "--region", region,
    "--stack-name", stack.stackName, "--template-file", template, "--capabilities", "CAPABILITY_NAMED_IAM", "--no-fail-on-empty-changeset"], { stdio: "inherit" });
} catch (error) {
  console.error(String(error));
  process.exitCode = 1;
}
