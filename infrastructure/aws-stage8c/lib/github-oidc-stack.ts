import { CfnOutput, Stack, StackProps } from "aws-cdk-lib";
import { CfnOIDCProvider, CfnRole } from "aws-cdk-lib/aws-iam";
import { Construct } from "constructs";

export const githubRepository = "bbororo5/OpenRTB-Ads-auction";
// Verified using GET /repos/{owner}/{repo}/actions/oidc/customization/sub.
// New GitHub subjects include immutable IDs; names alone do not match.
export const githubSubjectPrefix = "repo:bbororo5@114351464/OpenRTB-Ads-auction@1273253542";
export const githubBranch = "main";
export const githubRoleName = "RtbStage8cGitHub";
export const githubProviderHost = "token.actions.githubusercontent.com";

export function assertGitHubSubjectConfiguration(configuration: {
  use_default?: boolean;
  sub_claim_prefix?: string;
}): void {
  if (configuration.use_default !== true || configuration.sub_claim_prefix !== githubSubjectPrefix) {
    throw new Error("GitHub OIDC subject changed; review the exact subject before updating AWS trust.");
  }
}

interface GitHubOidcStackProps extends StackProps {
  readonly existingProviderArn?: string;
}

/** Authentication first. Deployment privileges require a separate reviewed change. */
export class GitHubOidcStack extends Stack {
  constructor(scope: Construct, id: string, props: GitHubOidcStackProps) {
    super(scope, id, props);
    const expectedProviderArn = `arn:aws:iam::${this.account}:oidc-provider/${githubProviderHost}`;
    if (props.existingProviderArn && props.existingProviderArn !== expectedProviderArn) {
      throw new Error("Existing GitHub OIDC provider must belong to the target account.");
    }
    const providerArn = props.existingProviderArn ?? new CfnOIDCProvider(this, "GitHubProvider", {
      url: `https://${githubProviderHost}`,
      clientIdList: ["sts.amazonaws.com"],
    }).attrArn;

    const role = new CfnRole(this, "GitHubRole", {
      roleName: githubRoleName,
      description: "Stage8C GitHub OIDC authentication baseline; no deployment permissions yet",
      maxSessionDuration: 3600,
      assumeRolePolicyDocument: {
        Version: "2012-10-17",
        Statement: [{
          Effect: "Allow",
          Action: "sts:AssumeRoleWithWebIdentity",
          Principal: { Federated: providerArn },
          Condition: {
            StringEquals: {
              [`${githubProviderHost}:aud`]: "sts.amazonaws.com",
              [`${githubProviderHost}:sub`]: `${githubSubjectPrefix}:ref:refs/heads/${githubBranch}`,
            },
          },
        }],
      },
      // GetCallerIdentity needs no permission grant. No access keys, PassRole,
      // AssumeRole, administrative managed policy, or resource creation rights.
      tags: [{ key: "Project", value: "low-latency-rtb" }, { key: "Purpose", value: "github-oidc" }],
    });
    new CfnOutput(this, "GitHubRoleArn", { value: role.attrArn });
  }
}
