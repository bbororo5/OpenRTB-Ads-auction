import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { App, BootstraplessSynthesizer } from "aws-cdk-lib";
import { ExperimentControlStack } from "../lib/experiment-control-stack.js";
import { controlExecutionRole, bucketName, repositoryName } from "../lib/experiment-identity-stack.js";
import { assertControlCanBeRemoved } from "../lib/control-lifecycle.js";

const account = "333982363617", region = "ap-northeast-2", name = "RtbStage8cControl";
const [command = "synth", acknowledgement] = process.argv.slice(2);
const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
function aws(args: string[]): any {
  return JSON.parse(execFileSync("aws", [...args, "--region", region, "--output", "json", "--no-cli-pager"],
    { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }) || "null");
}
function stack(stackName: string): any {
  try { return aws(["cloudformation", "describe-stacks", "--stack-name", stackName]).Stacks?.[0]; }
  catch (error) {
    if (String((error as any).stderr).includes("does not exist")) return undefined;
    throw error;
  }
}
try {
  if (!["synth", "install", "remove"].includes(command)) throw new Error("Use synth | install/remove --ack-cost");
  if (command !== "synth") {
    if (acknowledgement !== "--ack-cost") throw new Error("Require --ack-cost");
    if (aws(["sts", "get-caller-identity"]).Account !== account) throw new Error("Wrong AWS account");
  }
  if (command === "remove") {
    const control = stack(name);
    if (control) {
      const filters = ["Name=tag:Project,Values=low-latency-rtb", "Name=tag:Stage,Values=8c"];
      assertControlCanBeRemoved({
        stacks: ["RtbStage8c", "RtbStage8cLease", "RtbStage8cSafetyCanary"].map(stack).filter(Boolean),
        instances: aws(["ec2", "describe-instances", "--filters", ...filters]).Reservations.flatMap((r: any) => r.Instances),
        volumes: aws(["ec2", "describe-volumes", "--filters", ...filters]).Volumes,
        vpcs: aws(["ec2", "describe-vpcs", "--filters", ...filters]).Vpcs,
        images: aws(["ecr", "list-images", "--repository-name", repositoryName]).imageIds,
        objects: aws(["s3api", "list-objects-v2", "--bucket", bucketName(account, region)]).Contents ?? [],
      });
      aws(["cloudformation", "delete-stack", "--stack-name", control.StackId,
        "--role-arn", `arn:aws:iam::${account}:role/${controlExecutionRole}`]);
      aws(["cloudformation", "wait", "stack-delete-complete", "--stack-name", control.StackId]);
      if (stack(control.StackId)?.StackStatus !== "DELETE_COMPLETE") throw new Error("Control deletion not verified");
      console.log("Control DELETE_COMPLETE; persistent IAM identities retained.");
    } else console.log("Control stack absent.");
  } else {
    const app = new App({ outdir: path.join(directory, "cdk.out", "control") });
    new ExperimentControlStack(app, name, { env: { account, region }, synthesizer: new BootstraplessSynthesizer() });
    const assembly = app.synth();
    const template = path.join(assembly.directory, assembly.getStackByName(name).templateFile);
    console.log(`Ephemeral control template: ${template}`);
    if (command === "install") {
      if (stack(name)) throw new Error("Control already exists; inspect and remove safely before creating a new one");
      if (["RtbStage8c", "RtbStage8cLease", "RtbStage8cSafetyCanary"].some(n => stack(n))) throw new Error("Previous experiment remains");
      const result = aws(["cloudformation", "create-stack", "--stack-name", name,
        "--template-body", readFileSync(template, "utf8"),
        "--role-arn", `arn:aws:iam::${account}:role/${controlExecutionRole}`]);
      aws(["cloudformation", "wait", "stack-create-complete", "--stack-name", result.StackId]);
    }
  }
} catch (error) { console.error(String((error as any).stderr || error)); process.exitCode = 1; }
