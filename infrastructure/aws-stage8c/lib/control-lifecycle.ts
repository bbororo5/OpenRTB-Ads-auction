export function assertControlCanBeRemoved(inventory: {
  stacks: unknown[]; instances: { State?: { Name?: string } }[];
  volumes: unknown[]; vpcs: unknown[]; images: unknown[]; objects: unknown[];
}): void {
  if (inventory.stacks.length || inventory.instances.some(i => i.State?.Name !== "terminated")
    || inventory.volumes.length || inventory.vpcs.length || inventory.images.length || inventory.objects.length) {
    throw new Error("Experiment resources or assets remain; retain the watchdog and investigate");
  }
}
