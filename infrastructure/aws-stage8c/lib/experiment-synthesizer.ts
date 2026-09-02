import { DefaultStackSynthesizer } from "aws-cdk-lib";
import { bucketName, repositoryName, executionRole } from "./experiment-control-stack.js";

export function experimentSynthesizer(account: string, region: string, runId: string): DefaultStackSynthesizer {
  if (!/^rtb-[a-z0-9-]{1,64}$/.test(runId)) throw new Error("Invalid experiment run ID");
  return new DefaultStackSynthesizer({
    fileAssetsBucketName: bucketName(account, region), imageAssetsRepositoryName: repositoryName,
    bucketPrefix: `${runId}/`, dockerTagPrefix: `${runId}-`,
    // Empty role ARNs explicitly use the caller's restricted credentials.
    deployRoleArn: "", lookupRoleArn: "", fileAssetPublishingRoleArn: "", imageAssetPublishingRoleArn: "",
    cloudFormationExecutionRole: `arn:aws:iam::${account}:role/${executionRole}`,
    bootstrapStackVersionSsmParameter: "/rtb/stage8c/bootstrap-version", generateBootstrapVersionRule: false,
  });
}
