/** Always attempt cleanup, including partial acquisition/deployment failure. */
export async function runExperiment(steps: {
  acquire: () => Promise<void>;
  deploy: () => Promise<void>;
  verify: () => Promise<void>;
  cleanup: () => Promise<void>;
}): Promise<void> {
  let primaryError: unknown;
  try {
    await steps.acquire();
    await steps.deploy();
    await steps.verify();
  } catch (error) {
    primaryError = error;
  }
  try {
    await steps.cleanup();
  } catch (error) {
    throw new AggregateError(primaryError ? [primaryError, error] : [error], "Cleanup incomplete; independent reaper remains armed");
  }
  if (primaryError) throw primaryError;
}
