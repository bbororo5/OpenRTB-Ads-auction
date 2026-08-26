FROM node:22-alpine

WORKDIR /opt/stage8c

COPY performance/fixtures/stage8c/server.mjs ./server.mjs
COPY performance/k6 ./k6
COPY scripts/performance/aws-vm-baseline.sh ./aws-vm-baseline.sh
COPY infrastructure/postgres/provider-config/schema.sql ./init/provider/01-provider-config.sql
COPY infrastructure/postgres/ssp-claims/schema.sql ./init/provider/02-ssp-claims.sql
COPY infrastructure/postgres/dsp-regional-ledger/schema.sql ./init/ledger/01-ledger.sql
COPY infrastructure/postgres/dsp-money-events/schema.sql ./init/outcome/01-outcome.sql

COPY performance/fixtures/stage8c/init-provider.sql ./init/provider/03-stage8c-seed.sql
COPY performance/fixtures/stage8c/init-ledger.sql ./init/ledger/02-stage8c-seed.sql

USER node
ENTRYPOINT ["node", "/opt/stage8c/server.mjs"]
