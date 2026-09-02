#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { App, BootstraplessSynthesizer } from "aws-cdk-lib";
import { GitHubOidcStack, githubProviderHost } from "../lib/github-oidc-stack.js";

const account = "333982363617";
const region = "ap-northeast-2";
const stackName = "RtbStage8cGitHubAuth";
const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const [command = "synth", ...args] = process.argv.slice(2);

function aws(arguments_: string[]): string {
  return execFileSync("aws", [...arguments_, "--region", region, "--output", "json", "--no-cli-pager"], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
}

try {
  if (!["synth", "install"].includes(command) || args.length > 0) {
    throw new Error("Usage: npm run github-oidc -- synth|install (select credentials with AWS_PROFILE)");
  }
  let existingProviderArn: string | undefined;
  if (command === "install") {
    // Fail before mutation if credentials are absent or belong to another account.
    const identity = JSON.parse(aws(["sts", "get-caller-identity"]));
    if (identity.Account !== account) throw new Error(`Refusing install outside account ${account}.`);
    const arn = `arn:aws:iam::${account}:oidc-provider/${githubProviderHost}`;
    const providers = JSON.parse(aws(["iam", "list-open-id-connect-providers"]));
    if (providers.OpenIDConnectProviderList.some((provider: { Arn: string }) => provider.Arn === arn)) {
      const provider = JSON.parse(aws(["iam", "get-open-id-connect-provider", "--open-id-connect-provider-arn", arn]));
      if (!provider.ClientIDList.includes("sts.amazonaws.com")) {
        throw new Error("Existing provider lacks sts.amazonaws.com audience; review it without overwriting shared configuration.");
      }
      existingProviderArn = arn;
    }
    // An existing auth stack must keep owning its provider on subsequent installs.
    let ownedProvider = false;
    try {
      const resources = JSON.parse(aws(["cloudformation", "list-stack-resources", "--stack-name", stackName]));
      ownedProvider = resources.StackResourceSummaries.some(
        (resource: { ResourceType: string }) => resource.ResourceType === "AWS::IAM::OIDCProvider",
      );
    } catch (error) {
      const stderr = error && typeof error === "object" && "stderr" in error ? String(error.stderr) : "";
      if (!stderr.includes("does not exist")) throw error;
    }
    if (ownedProvider) existingProviderArn = undefined;
  }
  const app = new App({ outdir: path.join(directory, "cdk.out", "github-auth") });
  new GitHubOidcStack(app, stackName, {
    env: { account, region },
    synthesizer: new BootstraplessSynthesizer(),
    existingProviderArn,
  });
  const assembly = app.synth();
  const template = path.join(assembly.directory, assembly.getStackByName(stackName).templateFile);
  process.stdout.write(`Authentication-only template: ${template}\n`);
  if (command === "install") {
    // No CDK bootstrap or Docker build: only the dedicated authentication stack.
    execFileSync("aws", ["cloudformation", "deploy", "--region", region,
      "--stack-name", stackName, "--template-file", template,
      "--capabilities", "CAPABILITY_NAMED_IAM", "--no-fail-on-empty-changeset", "--no-cli-pager"],
    { stdio: "inherit" });
    process.stdout.write(aws(["cloudformation", "describe-stacks", "--stack-name", stackName,
      "--query", "Stacks[0].Outputs"]));
  }
} catch (error) {
  const stderr = error && typeof error === "object" && "stderr" in error ? String(error.stderr).trim() : "";
  process.stderr.write(`github-oidc: ${stderr || String(error)}\n`);
  process.exitCode = 1;
}
