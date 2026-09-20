import { createHash, createPublicKey, publicEncrypt, constants } from "node:crypto";
import { createObservationKey } from "./observation-identity.js";

// Verified multi-architecture manifest for official v1.102.4; no mutable latest tag.
export const tailscaleImage = "tailscale/tailscale@sha256:2667499ed87ae29218f292556ba062918402dd5e92e93637af14867e4df12dd3";
export type Remote = (script: string, timeoutSeconds: number) => Promise<string>;
export function accessNames(runId: string) {
  if (!/^rtb-[a-z0-9-]{1,64}$/.test(runId)) throw new Error("Invalid run ID");
  const name = `rtb-observe-${createHash("sha256").update(runId).digest("hex").slice(0, 16)}`;
  return { name, dir: `/run/${name}`, cli: `docker exec ${name} tailscale --socket=/tmp/tailscaled.sock` };
}
export function sealKey(publicKey: string, key: string): string {
  const parsed = createPublicKey(publicKey);
  if (parsed.asymmetricKeyType !== "rsa" || (parsed.asymmetricKeyDetails?.modulusLength ?? 0) < 3072)
    throw new Error("Observer must provide an RSA public key of at least 3072 bits");
  return publicEncrypt({ key: parsed, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, Buffer.from(key)).toString("base64");
}
export function prepareAccess(runId: string): string {
  const { name, dir } = accessNames(runId);
  return `set -eu
umask 077
test ! -e ${dir}
mkdir -m 700 ${dir}
docker pull ${tailscaleImage} >/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out ${dir}/private.pem 2>/dev/null
docker run -d --name ${name} --network host --cap-drop ALL --security-opt no-new-privileges --read-only --tmpfs /tmp:mode=1777 --tmpfs /var/lib/tailscale:mode=700 -v ${dir}:/run/auth:ro --entrypoint tailscaled ${tailscaleImage} --tun=userspace-networking --state=mem: --socket=/tmp/tailscaled.sock >/dev/null
attempt=0
until docker exec ${name} test -S /tmp/tailscaled.sock; do
  attempt=$((attempt + 1)); test "$attempt" -lt 30; sleep 1
done
openssl pkey -in ${dir}/private.pem -pubout
`;
}
export function activateAccess(runId: string, encryptedKey: string): string {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(encryptedKey)) throw new Error("Invalid sealed key");
  const { name, dir, cli } = accessNames(runId);
  return `set -eu
umask 077
trap 'rm -f ${dir}/private.pem ${dir}/auth.key' EXIT
printf '%s' '${encryptedKey}' | base64 -d | openssl pkeyutl -decrypt -inkey ${dir}/private.pem -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 -pkeyopt rsa_mgf1_md:sha256 -out ${dir}/auth.key
${cli} up --auth-key=file:/run/auth/auth.key --hostname=${name} --accept-dns=false --accept-routes=false --advertise-tags=tag:rtb-observer --timeout=60s >/dev/null 2>&1
rm -f ${dir}/private.pem ${dir}/auth.key
timeout 30 ${cli} serve --bg --https=443 http://127.0.0.1:3000 >/dev/null 2>&1
${cli} status --json
`;
}
export function stopAccess(runId: string): string {
  const { name, dir, cli } = accessNames(runId);
  return `set -eu
failed=0
if docker container inspect ${name} >/dev/null 2>&1; then
  timeout 15 ${cli} logout >/dev/null 2>&1 || failed=1
  docker rm -f ${name} >/dev/null || failed=1
fi
rm -f ${dir}/private.pem ${dir}/auth.key
if test -d ${dir}; then rmdir ${dir} || failed=1; fi
exit "$failed"
`;
}
export function observationUrl(status: any, runId: string): string {
  const { name } = accessNames(runId);
  const dns = status?.Self?.DNSName?.replace(/\.$/, "");
  if (status?.BackendState !== "Running" || status?.Self?.Online !== true
    || dns !== `${name}.taild7dd00.ts.net` || !status?.CertDomains?.includes(dns))
    throw new Error("Observer is not online with the expected HTTPS identity; check tailnet HTTPS settings");
  return `https://${dns}`;
}
export async function connectObservation(runId: string, remote: Remote,
  issueKey = createObservationKey): Promise<string> {
  const publicKey = await remote(prepareAccess(runId), 180);
  const credential = await issueKey(runId);
  try {
    const status = JSON.parse(await remote(activateAccess(runId, sealKey(publicKey, credential.key)), 120));
    return observationUrl(status, runId);
  } finally { await credential.revoke(); }
}
