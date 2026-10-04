export async function withCleanup<T>(work: () => Promise<T>, cleanup: () => void | Promise<void>): Promise<T> {
  let failure: unknown;
  try { return await work(); }
  catch (error) { failure = error; throw error; }
  finally {
    try { await cleanup(); }
    catch (error) { throw new AggregateError(failure ? [failure, error] : [error], "Cleanup failed; inspect session resources"); }
  }
}

export function installSignalCleanup(cleanup: () => void) {
  let stopping = false;
  const stop = (signal: string) => {
    if (stopping) return;
    stopping = true;
    try { cleanup(); } catch (error) { console.error(`Cleanup failed after ${signal}: ${String(error)}`); }
    process.exit(signal === "SIGINT" ? 130 : 143);
  };
  const interrupt = () => stop("SIGINT"), terminate = () => stop("SIGTERM");
  process.once("SIGINT", interrupt); process.once("SIGTERM", terminate);
  return () => { process.removeListener("SIGINT", interrupt); process.removeListener("SIGTERM", terminate); };
}
