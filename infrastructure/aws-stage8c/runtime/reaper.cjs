// Also loaded by unit tests. AWS clients are constructed only in the Lambda handler.
const PROJECT = 'low-latency-rtb';
const WORKLOAD = 'RtbStage8c';
const LEASE = 'RtbStage8cLease';
const CANARY = 'RtbStage8cSafetyCanary';
const MAX_LIFETIME_MS = 45 * 60 * 1000;
const validRunId = value => /^rtb-[a-z0-9-]{1,64}$/.test(value ?? '');
const tagsOf = stack => Object.fromEntries((stack.Tags ?? []).map(tag => [tag.Key, tag.Value]));

function owned(stack) {
  const tags = tagsOf(stack);
  if (tags.Project !== PROJECT || tags.Stage !== '8c' || !validRunId(tags.RunId)) {
    throw new Error(`Refusing unowned stack ${stack.StackName}`);
  }
  return tags;
}

function deadline(stack) {
  const created = new Date(stack.CreationTime).getTime();
  if (!Number.isFinite(created)) throw new Error('Missing authoritative creation time');
  const requested = Date.parse(tagsOf(stack).ExpiresAt);
  return Number.isFinite(requested) ? Math.min(requested, created + MAX_LIFETIME_MS) : created;
}

function createReaper(api, config, now = Date.now) {
  async function stack(name) {
    try {
      const result = await api('cloudformation', 'DescribeStacks', { StackName: name });
      return result.Stacks?.[0];
    } catch (error) {
      if (error.name === 'ValidationError' && /does not exist/.test(error.message)) return undefined;
      throw error;
    }
  }
  async function removeStack(target) {
    if (target.StackStatus === 'DELETE_IN_PROGRESS') return;
    try {
      await api('cloudformation', 'DeleteStack', { StackName: target.StackId });
    } catch (error) {
      if (error.name === 'ValidationError' && /does not exist/.test(error.message)) return;
      throw error;
    }
  }
  async function pages(service, operation, input, field, tokenField = 'NextToken') {
    const values = [];
    let token;
    do {
      const page = await api(service, operation, { ...input, ...(token ? { [tokenField]: token } : {}) });
      values.push(...(page[field] ?? []));
      token = page[tokenField];
    } while (token);
    return values;
  }
  async function clearAssets(runId) {
    const images = await pages('ecr', 'ListImages', { repositoryName: config.repository }, 'imageIds', 'nextToken');
    const selected = images.filter(image => image.imageTag?.startsWith(`${runId}-`));
    for (let start = 0; start < selected.length; start += 100) {
      const result = await api('ecr', 'BatchDeleteImage', {
        repositoryName: config.repository,
        imageIds: selected.slice(start, start + 100).map(image => ({ imageTag: image.imageTag })),
      });
      if (result.failures?.some(failure => failure.failureCode !== 'ImageNotFound')) {
        throw new Error('ECR cleanup incomplete');
      }
    }
    // Snapshot keys first: deleting while paginating cannot skip a subsequent page.
    const keys = [];
    let continuation;
    do {
      const page = await api('s3', 'ListObjectsV2', {
        Bucket: config.bucket, Prefix: `${runId}/`, ContinuationToken: continuation,
      });
      keys.push(...(page.Contents ?? []).map(object => ({ Key: object.Key })));
      continuation = page.NextContinuationToken;
    } while (continuation);
    for (let start = 0; start < keys.length; start += 1000) {
      const result = await api('s3', 'DeleteObjects', {
        Bucket: config.bucket, Delete: { Objects: keys.slice(start, start + 1000), Quiet: true },
      });
      if (result.Errors?.length) throw new Error('S3 cleanup incomplete');
    }
  }
  async function reapLease(event) {
    const lease = await stack(LEASE);
    if (!lease) {
      // A manually bypassed runner must not silently evade the watchdog.
      const orphan = await stack(WORKLOAD);
      if (orphan) {
        owned(orphan);
        if (now() >= deadline(orphan)) await removeStack(orphan);
        throw new Error('Workload exists without lease: inspect ownership and cleanup');
      }
      return { state: 'idle' };
    }
    const { RunId: runId } = owned(lease);
    const forced = event.mode === 'cleanup';
    if (forced && event.runId !== runId) throw new Error('Cleanup run ID does not own the lease');
    if (!forced && now() < deadline(lease)) return { state: 'active', runId };

    const workload = await stack(WORKLOAD);
    if (workload && owned(workload).RunId !== runId) throw new Error('Workload and lease ownership differ');
    const filters = [
      { Name: 'tag:Project', Values: [PROJECT] },
      { Name: 'tag:Stage', Values: ['8c'] },
      { Name: 'tag:RunId', Values: [runId] },
      { Name: 'tag:aws:cloudformation:stack-name', Values: [WORKLOAD] },
    ];
    const reservations = await pages('ec2', 'DescribeInstances', { Filters: filters }, 'Reservations');
    const instances = reservations.flatMap(item => item.Instances ?? []);
    const terminate = instances.filter(instance => !['terminated', 'shutting-down'].includes(instance.State.Name));
    if (terminate.length) await api('ec2', 'TerminateInstances', { InstanceIds: terminate.map(instance => instance.InstanceId) });
    if (workload) {
      await removeStack(workload);
      return { state: 'deleting-stack', runId };
    }
    if (instances.some(instance => instance.State.Name !== 'terminated')) return { state: 'waiting-instances', runId };
    const volumes = await pages('ec2', 'DescribeVolumes', { Filters: filters }, 'Volumes');
    const vpcs = await pages('ec2', 'DescribeVpcs', { Filters: filters }, 'Vpcs');
    if (volumes.length || vpcs.length) throw new Error('Orphan EBS/VPC remains; lease retained for retry and alarm');
    await clearAssets(runId);
    await removeStack(lease);
    return { state: 'deleting-lease', runId };
  }
  return async function reap(event = {}) {
    // Canary failures must never prevent cleanup of a billable experiment.
    let canaryError;
    try {
      const canary = await stack(CANARY);
      if (canary) {
        owned(canary);
        if (now() >= deadline(canary)) await removeStack(canary);
      }
    } catch (error) { canaryError = error; }
    const result = await reapLease(event);
    if (canaryError) throw canaryError;
    return result;
  };
}

exports.createReaper = createReaper;
exports.deadline = deadline;
exports.validRunId = validRunId;
exports.handler = async event => {
  const types = { cloudformation: 'CloudFormation', ec2: 'EC2', ecr: 'ECR', s3: 'S3' };
  const clients = {};
  const api = async (service, operation, input) => {
    if (!clients[service]) {
      const sdk = require(`@aws-sdk/client-${service}`);
      clients[service] = { sdk, client: new sdk[`${types[service]}Client`]({ maxAttempts: 3 }) };
    }
    const { sdk, client } = clients[service];
    return client.send(new sdk[`${operation}Command`](input));
  };
  const result = await createReaper(api, { bucket: process.env.ASSET_BUCKET, repository: process.env.ASSET_REPOSITORY })(event);
  console.log(JSON.stringify(result));
  return result;
};
