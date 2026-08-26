import assert from "node:assert/strict";
import test from "node:test";

import { auctionBucket, externalBidPrice } from "./server.mjs";

test("external DSPs beat the project DSP in three disjoint buckets", () => {
  const observed = new Set();
  for (let index = 0; observed.size < 4; index += 1) {
    const auctionId = `auction-${index}`;
    const bucket = auctionBucket(auctionId);
    if (observed.has(bucket)) {
      continue;
    }
    observed.add(bucket);
    const aWins = externalBidPrice("external-a", auctionId) > 2;
    const bWins = externalBidPrice("external-b", auctionId) > 2;
    assert.equal(Number(aWins) + Number(bWins), bucket === 3 ? 0 : 1);
  }
});
