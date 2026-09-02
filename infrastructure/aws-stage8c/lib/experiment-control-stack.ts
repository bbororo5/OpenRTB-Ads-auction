import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { CfnOutput, Stack, StackProps } from "aws-cdk-lib";
import { CfnRole, CfnInstanceProfile } from "aws-cdk-lib/aws-iam";
import { CfnBucket } from "aws-cdk-lib/aws-s3";
import { CfnRepository } from "aws-cdk-lib/aws-ecr";
import { CfnFunction, CfnPermission } from "aws-cdk-lib/aws-lambda";
import { CfnRule } from "aws-cdk-lib/aws-events";
import { CfnLogGroup } from "aws-cdk-lib/aws-logs";
import { CfnAlarm } from "aws-cdk-lib/aws-cloudwatch";
import { CfnParameter as SsmParameter } from "aws-cdk-lib/aws-ssm";
import { Construct } from "constructs";
import { githubSubjectPrefix, githubProviderHost } from "./github-oidc-stack.js";

export const deploymentRole = "RtbStage8cDeploy";
export const executionRole = "RtbStage8cExecution";
export const hostRole = "RtbStage8cHost";
export const hostProfile = "RtbStage8cHost";
export const reaperName = "RtbStage8cReaper";
export const ruleName = "RtbStage8cExpiry";
export const repositoryName = "rtb-stage8c-experiment-assets";
export const bucketName = (account: string, region: string) => `rtb-stage8c-experiment-assets-${account}-${region}`;
const allow = (Action: string[], Resource: string[], Condition?: Record<string, unknown>) => ({
  Effect: "Allow", Action, Resource, ...(Condition ? { Condition } : {}),
});
const serviceTrust = (Service: string) => ({ Version: "2012-10-17", Statement: [{ Effect: "Allow", Principal: { Service }, Action: "sts:AssumeRole" }] });

/** Persistent control plane. Never deployed/deleted by the GitHub deployment role. */
export class ExperimentControlStack extends Stack {
  constructor(scope: Construct, id: string, props: StackProps) {
    super(scope, id, props);
    const arn = (service: string, resource: string) => `arn:aws:${service}:${this.region}:${this.account}:${resource}`;
    const roleArn = (name: string) => `arn:aws:iam::${this.account}:role/${name}`;
    const bucket = bucketName(this.account, this.region);
    const bucketArn = `arn:aws:s3:::${bucket}`;
    const repoArn = arn("ecr", `repository/${repositoryName}`);
    const stacks = ["RtbStage8c", "RtbStage8cLease", "RtbStage8cSafetyCanary"].map(name => arn("cloudformation", `stack/${name}/*`));
    const regional = { StringEquals: { "aws:RequestedRegion": this.region } };
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
    const host = new CfnRole(this, "Host", {
      roleName: hostRole, assumeRolePolicyDocument: serviceTrust("ec2.amazonaws.com"),
      managedPolicyArns: ["arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"],
      policies: [{ policyName: "PullExperimentImages", policyDocument: { Version: "2012-10-17", Statement: [
        allow(["ecr:GetAuthorizationToken"], ["*"]),
        allow(["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"], [repoArn]),
      ] } }],
    });
    new CfnInstanceProfile(this, "HostProfile", { instanceProfileName: hostProfile, roles: [host.ref] });
    const execution = new CfnRole(this, "Execution", {
      roleName: executionRole, assumeRolePolicyDocument: serviceTrust("cloudformation.amazonaws.com"),
      policies: [{ policyName: "ProvisionExperimentEc2Only", policyDocument: { Version: "2012-10-17", Statement: [
        // EC2 networking has mixed tagging support. The service role is region/service
        // limited, not an account-wide admin; some network operations remain regional.
        allow(["ec2:Describe*", "ec2:CreateVpc", "ec2:DeleteVpc", "ec2:ModifyVpcAttribute",
          "ec2:CreateSubnet", "ec2:DeleteSubnet", "ec2:ModifySubnetAttribute",
          "ec2:CreateInternetGateway", "ec2:DeleteInternetGateway", "ec2:AttachInternetGateway", "ec2:DetachInternetGateway",
          "ec2:CreateRouteTable", "ec2:DeleteRouteTable", "ec2:AssociateRouteTable", "ec2:DisassociateRouteTable",
          "ec2:CreateRoute", "ec2:DeleteRoute", "ec2:CreateSecurityGroup", "ec2:DeleteSecurityGroup",
          "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress", "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress",
          "ec2:CreateTags", "ec2:DeleteTags", "ec2:TerminateInstances", "ec2:ModifyInstanceAttribute", "ec2:ModifyInstanceCreditSpecification"], ["*"], regional),
        allow(["ec2:RunInstances"], ["*"], { StringEquals: { "aws:RequestedRegion": this.region }, StringEqualsIfExists: { "ec2:InstanceType": "t4g.small" } }),
        // CDK implements requireImdsv2 with a separate launch template per host.
        // Both creation and rollback/deletion need permission; no version editing.
        allow(["ec2:CreateLaunchTemplate", "ec2:DeleteLaunchTemplate"], [arn("ec2", "launch-template/*")], regional),
        allow(["iam:PassRole"], [host.attrArn], { StringEquals: { "iam:PassedToService": "ec2.amazonaws.com" } }),
        allow(["iam:GetInstanceProfile"], [`arn:aws:iam::${this.account}:instance-profile/${hostProfile}`]),
        allow(["s3:GetObject"], [`${bucketArn}/rtb-*/*`]),
        allow(["ssm:GetParameters", "ssm:GetParameter"], [`arn:aws:ssm:${this.region}::parameter/aws/service/ami-amazon-linux-latest/*`, arn("ssm", "parameter/rtb/stage8c/bootstrap-version")]),
      ] } }],
    });
    const reaper = new CfnRole(this, "ReaperRole", {
      roleName: "RtbStage8cReaper", assumeRolePolicyDocument: serviceTrust("lambda.amazonaws.com"),
      policies: [{ policyName: "ReapExperimentOnly", policyDocument: { Version: "2012-10-17", Statement: [
        allow(["cloudformation:DescribeStacks", "cloudformation:DeleteStack"], stacks),
        allow(["cloudformation:UpdateStack"], [arn("cloudformation", "stack/RtbStage8cLease/*")]),
        allow(["iam:PassRole"], [execution.attrArn], { StringEquals: { "iam:PassedToService": "cloudformation.amazonaws.com" } }),
        allow(["ec2:DescribeInstances", "ec2:DescribeVolumes", "ec2:DescribeVpcs"], ["*"], regional),
        allow(["ec2:TerminateInstances"], [arn("ec2", "instance/*")], { StringEquals: { "ec2:ResourceTag/Project": "low-latency-rtb", "ec2:ResourceTag/Stage": "8c" } }),
        allow(["ecr:ListImages", "ecr:BatchDeleteImage"], [repoArn]),
        allow(["s3:ListBucket"], [bucketArn]), allow(["s3:DeleteObject"], [`${bucketArn}/rtb-*/*`]),
        allow(["logs:CreateLogStream", "logs:PutLogEvents"], [arn("logs", `log-group:/aws/lambda/${reaperName}:*`)]),
      ] } }],
    });
    new CfnLogGroup(this, "ReaperLogs", { logGroupName: `/aws/lambda/${reaperName}`, retentionInDays: 7 });
    const fn = new CfnFunction(this, "Reaper", {
      functionName: reaperName, role: reaper.attrArn, runtime: "nodejs24.x", handler: "index.handler",
      timeout: 120, memorySize: 128,
      description: `reaper-sha256:${createHash("sha256").update(readFileSync(new URL("../runtime/reaper.cjs", import.meta.url))).digest("hex")}`,
      code: { zipFile: readFileSync(new URL("../runtime/reaper.cjs", import.meta.url), "utf8") },
      environment: { variables: { ASSET_BUCKET: bucket, ASSET_REPOSITORY: repositoryName } },
    });
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
    const deploy = new CfnRole(this, "Deploy", {
      roleName: deploymentRole, maxSessionDuration: 3600,
      assumeRolePolicyDocument: { Version: "2012-10-17", Statement: [{ Effect: "Allow", Action: "sts:AssumeRoleWithWebIdentity",
        Principal: { Federated: `arn:aws:iam::${this.account}:oidc-provider/${githubProviderHost}` },
        Condition: { StringEquals: { [`${githubProviderHost}:aud`]: "sts.amazonaws.com", [`${githubProviderHost}:sub`]: `${githubSubjectPrefix}:ref:refs/heads/main` } },
      }] },
      policies: [{ policyName: "ExperimentRunner", policyDocument: { Version: "2012-10-17", Statement: [
        allow(["cloudformation:CreateStack", "cloudformation:UpdateStack", "cloudformation:DeleteStack", "cloudformation:DescribeStacks",
          "cloudformation:DescribeStackEvents", "cloudformation:ListStackResources", "cloudformation:GetTemplate", "cloudformation:GetTemplateSummary",
          "cloudformation:CreateChangeSet", "cloudformation:DescribeChangeSet", "cloudformation:ExecuteChangeSet", "cloudformation:DeleteChangeSet"], stacks),
        allow(["cloudformation:DescribeChangeSet", "cloudformation:ExecuteChangeSet", "cloudformation:DeleteChangeSet"], [arn("cloudformation", "changeSet/cdk-deploy-change-set/*")]),
        allow(["iam:PassRole"], [execution.attrArn], { StringEquals: { "iam:PassedToService": "cloudformation.amazonaws.com" } }),
        allow(["ec2:DescribeAvailabilityZones", "ec2:DescribeInstances", "ec2:DescribeInstanceTypes", "ec2:DescribeVolumes", "ec2:DescribeVpcs", "cloudwatch:GetMetricStatistics"], ["*"], regional),
        allow(["s3:GetBucketLocation", "s3:ListBucket"], [bucketArn]),
        allow(["s3:GetObject", "s3:PutObject"], [`${bucketArn}/rtb-*/*`]),
        allow(["ecr:GetAuthorizationToken"], ["*"]),
        allow(["ecr:DescribeImages", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"], [repoArn]),
        allow(["lambda:InvokeFunction", "lambda:GetFunctionConfiguration"], [fn.attrArn]),
        allow(["events:DescribeRule", "events:ListTargetsByRule"], [rule.attrArn]),
        allow(["ssm:GetParameter", "ssm:GetParameters"], [arn("ssm", "parameter/rtb/stage8c/bootstrap-version"), `arn:aws:ssm:${this.region}::parameter/aws/service/ami-amazon-linux-latest/*`]),
        allow(["ssm:SendCommand"], [`arn:aws:ssm:${this.region}::document/AWS-RunShellScript`]),
        allow(["ssm:SendCommand"], [arn("ec2", "instance/*")], { StringEquals: { "ssm:resourceTag/Project": "low-latency-rtb", "ssm:resourceTag/Stage": "8c" } }),
        allow(["ssm:GetCommandInvocation", "ssm:DescribeInstanceInformation"], ["*"], regional),
      ] } }],
    });
    new CfnOutput(this, "DeployRoleArn", { value: deploy.attrArn });
    new CfnOutput(this, "ReaperFunction", { value: fn.ref });
  }
}
