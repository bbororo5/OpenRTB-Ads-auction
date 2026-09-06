import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { CfnOutput, Stack, StackProps } from "aws-cdk-lib";
import { CfnBucket } from "aws-cdk-lib/aws-s3";
import { CfnRepository } from "aws-cdk-lib/aws-ecr";
import { CfnFunction, CfnPermission } from "aws-cdk-lib/aws-lambda";
import { CfnRule } from "aws-cdk-lib/aws-events";
import { CfnLogGroup } from "aws-cdk-lib/aws-logs";
import { CfnAlarm } from "aws-cdk-lib/aws-cloudwatch";
import { CfnParameter as SsmParameter } from "aws-cdk-lib/aws-ssm";
import { Construct } from "constructs";
import { reaperName, ruleName, repositoryName, bucketName } from "./experiment-identity-stack.js";
export { deploymentRole, executionRole, hostRole, hostProfile, reaperName, ruleName, repositoryName, bucketName } from "./experiment-identity-stack.js";

/** Ephemeral control plane; IAM identities survive its deletion. */
export class ExperimentControlStack extends Stack {
  constructor(scope: Construct, id: string, props: StackProps) {
    super(scope, id, props);
    const roleArn = (name: string) => `arn:aws:iam::${this.account}:role/${name}`;
    const bucket = bucketName(this.account, this.region);
    new CfnBucket(this, "Assets", {
      bucketName: bucket,
      publicAccessBlockConfiguration: { blockPublicAcls: true, blockPublicPolicy: true, ignorePublicAcls: true, restrictPublicBuckets: true },
      bucketEncryption: { serverSideEncryptionConfiguration: [{ serverSideEncryptionByDefault: { sseAlgorithm: "AES256" } }] },
      lifecycleConfiguration: { rules: [{ id: "FallbackExpiry", status: "Enabled", expirationInDays: 1, abortIncompleteMultipartUpload: { daysAfterInitiation: 1 } }] },
    });
    new CfnRepository(this, "Images", {
      repositoryName,
      lifecyclePolicy: { lifecyclePolicyText: JSON.stringify({ rules: [{ rulePriority: 1, selection: { tagStatus: "any", countType: "sinceImagePushed", countUnit: "days", countNumber: 1 }, action: { type: "expire" } }] }) },
    });
    new SsmParameter(this, "Version", { name: "/rtb/stage8c/bootstrap-version", type: "String", value: "32" });
    const logs = new CfnLogGroup(this, "ReaperLogs", { logGroupName: `/aws/lambda/${reaperName}`, retentionInDays: 7 });
    const fn = new CfnFunction(this, "Reaper", {
      functionName: reaperName, role: roleArn("RtbStage8cReaper"), runtime: "nodejs24.x", handler: "index.handler",
      timeout: 120, memorySize: 128,
      description: `reaper-sha256:${createHash("sha256").update(readFileSync(new URL("../runtime/reaper.cjs", import.meta.url))).digest("hex")}`,
      code: { zipFile: readFileSync(new URL("../runtime/reaper.cjs", import.meta.url), "utf8") },
      environment: { variables: { ASSET_BUCKET: bucket, ASSET_REPOSITORY: repositoryName, CONTROL_STACK: "RtbStage8cControl" } },
    });
    fn.addResourceDependency(logs);
    const rule = new CfnRule(this, "Expiry", {
      name: ruleName, state: "ENABLED", scheduleExpression: "rate(1 minute)",
      targets: [{ id: "Reaper", arn: fn.attrArn, retryPolicy: { maximumEventAgeInSeconds: 300, maximumRetryAttempts: 3 } }],
    });
    new CfnPermission(this, "ExpiryPermission", { action: "lambda:InvokeFunction", functionName: fn.ref, principal: "events.amazonaws.com", sourceArn: rule.attrArn });
    new CfnAlarm(this, "ReaperErrors", {
      alarmName: "RtbStage8cReaperErrors", namespace: "AWS/Lambda", metricName: "Errors",
      dimensions: [{ name: "FunctionName", value: reaperName }], statistic: "Sum", period: 60,
      evaluationPeriods: 1, threshold: 1, comparisonOperator: "GreaterThanOrEqualToThreshold", treatMissingData: "notBreaching",
      alarmDescription: "Cleanup failed: inspect RtbStage8cLease and reaper logs. No email destination configured.",
    });
    new CfnOutput(this, "ReaperFunction", { value: fn.ref });
  }
}
