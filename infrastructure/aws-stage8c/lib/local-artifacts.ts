import { writeFileSync } from "node:fs";
import { resolve } from "node:path";

export function save(directory: string, name: string, data: unknown) {
  writeFileSync(resolve(directory, name), JSON.stringify(data, null, 2) + "\n", { mode: 0o600 });
}
