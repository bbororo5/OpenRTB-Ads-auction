import { CfnOutput, Stack, StackProps } from "aws-cdk-lib";
import { CfnRole, CfnInstanceProfile } from "aws-cdk-lib/aws-iam";
import { Construct } from "constructs";
import { githubSubjectPrefix, githubProviderHost } from "./github-oidc-stack.js";

export const deploymentRole = "RtbStage8cDeploy";
export const controlRunnerRole = "RtbStage8cControlRunner";
export const controlExecutionRole = "RtbStage8cControlExecution";
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

/** Persistent IAM only; billing resources belong to ExperimentControlStack. */
export class ExperimentIdentityStack extends Stack {
  constructor(scope: Construct, id: string, props: StackProps) {
    super(scope, id, props);
    const arn = (service: string, resource: string) => `arn:aws:${service}:${this.region}:${this.account}:${resource}`;
    const roleArn = (name: string) => `arn:aws:iam::${this.account}:role/${name}`;
    const bucket = bucketName(this.account, this.region);
    const bucketArn = `arn:aws:s3:::${bucket}`;
    const repoArn = arn("ecr", `repository/${repositoryName}`);
    const stacks = ["RtbStage8c", "RtbStage8cLease", "RtbStage8cSafetyCanary"].map(name => arn("cloudformation", `stack/${name}/*`));
    const regional = { StringEquals: { "aws:RequestedRegion": this.region } };
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
        allow(["cloudformation:DescribeStacks", "cloudformation:DeleteStack"], [arn("cloudformation", "stack/RtbStage8cControl/*")]),
        allow(["iam:PassRole"], [execution.attrArn], { StringEquals: { "iam:PassedToService": "cloudformation.amazonaws.com" } }),
        allow(["ec2:DescribeInstances", "ec2:DescribeVolumes", "ec2:DescribeVpcs"], ["*"], regional),
        allow(["ec2:TerminateInstances"], [arn("ec2", "instance/*")], { StringEquals: { "ec2:ResourceTag/Project": "low-latency-rtb", "ec2:ResourceTag/Stage": "8c" } }),
        allow(["ecr:ListImages", "ecr:BatchDeleteImage"], [repoArn]),
        allow(["s3:ListBucket"], [bucketArn]), allow(["s3:DeleteObject"], [`${bucketArn}/rtb-*/*`]),
        allow(["logs:CreateLogStream", "logs:PutLogEvents"], [arn("logs", `log-group:/aws/lambda/${reaperName}:*`)]),
      ] } }],
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
        allow(["lambda:InvokeFunction", "lambda:GetFunctionConfiguration"], [arn("lambda", `function:${reaperName}`)]),
        allow(["events:DescribeRule", "events:ListTargetsByRule"], [arn("events", `rule/${ruleName}`)]),
        allow(["ssm:GetParameter", "ssm:GetParameters"], [arn("ssm", "parameter/rtb/stage8c/bootstrap-version"), `arn:aws:ssm:${this.region}::parameter/aws/service/ami-amazon-linux-latest/*`]),
        allow(["ssm:SendCommand"], [`arn:aws:ssm:${this.region}::document/AWS-RunShellScript`]),
        allow(["ssm:SendCommand"], [arn("ec2", "instance/*")], { StringEquals: { "ssm:resourceTag/Project": "low-latency-rtb", "ssm:resourceTag/Stage": "8c" } }),
        allow(["ssm:GetCommandInvocation", "ssm:DescribeInstanceInformation"], ["*"], regional),
      ] } }],
    });
    new CfnOutput(this, "DeployRoleArn", { value: deploy.attrArn });
    // This service role cannot create IAM identities, run EC2, or touch the
    // workload. The control runner is a separate trust boundary from tests.
    const controlExecution = new CfnRole(this, "ControlExecution", {
      roleName: controlExecutionRole, assumeRolePolicyDocument: serviceTrust("cloudformation.amazonaws.com"),
      policies: [{ policyName: "EphemeralControlOnly", policyDocument: { Version: "2012-10-17", Statement: [
        allow(["iam:PassRole"], [reaper.attrArn], { StringEquals: { "iam:PassedToService": "lambda.amazonaws.com" } }),
        allow(["lambda:CreateFunction", "lambda:DeleteFunction", "lambda:GetFunction", "lambda:GetFunctionConfiguration",
          "lambda:AddPermission", "lambda:RemovePermission", "lambda:GetPolicy", "lambda:TagResource", "lambda:UntagResource", "lambda:ListTags"], [arn("lambda", `function:${reaperName}`)]),
        allow(["events:PutRule", "events:DeleteRule", "events:DescribeRule", "events:PutTargets", "events:RemoveTargets", "events:ListTargetsByRule", "events:TagResource", "events:UntagResource", "events:ListTagsForResource"], [arn("events", `rule/${ruleName}`)]),
        allow(["s3:CreateBucket", "s3:DeleteBucket", "s3:GetBucketLocation", "s3:ListBucket", "s3:GetBucketTagging", "s3:PutBucketTagging",
          "s3:GetEncryptionConfiguration", "s3:PutEncryptionConfiguration", "s3:GetBucketPublicAccessBlock", "s3:PutBucketPublicAccessBlock",
          "s3:GetLifecycleConfiguration", "s3:PutLifecycleConfiguration"], [bucketArn]),
        allow(["ecr:CreateRepository", "ecr:DeleteRepository", "ecr:DescribeRepositories", "ecr:PutLifecyclePolicy", "ecr:GetLifecyclePolicy", "ecr:DeleteLifecyclePolicy", "ecr:TagResource", "ecr:UntagResource", "ecr:ListTagsForResource"], [repoArn]),
        allow(["ssm:PutParameter", "ssm:DeleteParameter", "ssm:GetParameter", "ssm:AddTagsToResource", "ssm:RemoveTagsFromResource", "ssm:ListTagsForResource"], [arn("ssm", "parameter/rtb/stage8c/bootstrap-version")]),
        allow(["logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy", "logs:DeleteRetentionPolicy", "logs:TagResource", "logs:UntagResource", "logs:ListTagsForResource"], [arn("logs", `log-group:/aws/lambda/${reaperName}`), arn("logs", `log-group:/aws/lambda/${reaperName}:*`)]),
        allow(["logs:DescribeLogGroups"], ["*"], regional),
        allow(["cloudwatch:PutMetricAlarm", "cloudwatch:DeleteAlarms", "cloudwatch:DescribeAlarms", "cloudwatch:TagResource", "cloudwatch:UntagResource", "cloudwatch:ListTagsForResource"], [arn("cloudwatch", "alarm:RtbStage8cReaperErrors")]),
      ] } }],
    });
    new CfnRole(this, "ControlRunner", {
      roleName: controlRunnerRole,
      assumeRolePolicyDocument: deploy.assumeRolePolicyDocument,
      maxSessionDuration: 3600,
      policies: [{ policyName: "ControlLifecycleOnly", policyDocument: { Version: "2012-10-17", Statement: [
        allow(["cloudformation:CreateStack", "cloudformation:DeleteStack", "cloudformation:DescribeStacks", "cloudformation:DescribeStackEvents"], [arn("cloudformation", "stack/RtbStage8cControl/*")]),
        allow(["iam:PassRole"], [controlExecution.attrArn], { StringEquals: { "iam:PassedToService": "cloudformation.amazonaws.com" } }),
        allow(["cloudformation:DescribeStacks"], stacks),
        allow(["ec2:DescribeInstances", "ec2:DescribeVolumes", "ec2:DescribeVpcs"], ["*"], regional),
        allow(["ecr:ListImages"], [repoArn]),
        allow(["s3:ListBucket"], [bucketArn]),
      ] } }],
    });
  }
}
