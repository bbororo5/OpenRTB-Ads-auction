import { execFileSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { App, BootstraplessSynthesizer } from "aws-cdk-lib";
import { ExperimentIdentityStack } from "../lib/experiment-identity-stack.js";
import { assertGitHubSubjectConfiguration, githubRepository } from "../lib/github-oidc-stack.js";

const account = "333982363617", region = "ap-northeast-2";
const [command = "synth"] = process.argv.slice(2);
const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
try {
  if (!["synth", "install"].includes(command)) throw new Error("Use synth | install");
  if (command === "install") {
    const identity = JSON.parse(execFileSync("aws", ["sts", "get-caller-identity", "--output", "json"], { encoding: "utf8" }));
    if (identity.Account !== account) throw new Error("Wrong AWS account");
    assertGitHubSubjectConfiguration(JSON.parse(execFileSync("gh", ["api", `repos/${githubRepository}/actions/oidc/customization/sub`], { encoding: "utf8" })));
    try {
      execFileSync("aws", ["cloudformation", "describe-stacks", "--region", region, "--stack-name", "RtbStage8cControl"], { stdio: "pipe" });
      throw new Error("Remove legacy control safely before installing permanent identities");
    } catch (error) {
      if (!String((error as any).stderr).includes("does not exist")) throw error;
    }
  }
  const app = new App({ outdir: path.join(directory, "cdk.out", "experiment-identity") });
  const stack = new ExperimentIdentityStack(app, "RtbStage8cIdentity", { env: { account, region }, synthesizer: new BootstraplessSynthesizer() });
  const assembly = app.synth();
  const template = path.join(assembly.directory, assembly.getStackByName(stack.stackName).templateFile);
  console.log(`Persistent IAM-only template: ${template}`);
  if (command === "install") execFileSync("aws", ["cloudformation", "deploy", "--region", region,
    "--stack-name", stack.stackName, "--template-file", template, "--capabilities", "CAPABILITY_NAMED_IAM", "--no-fail-on-empty-changeset"], { stdio: "inherit" });
} catch (error) { console.error(String(error)); process.exitCode = 1; }
