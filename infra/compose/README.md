# infra/compose

Docker Compose for the local environment. `compose.yaml` holds the PRD-01 `default` services (postgres, api, web, demo-shop). Profiles `queue`, `obs`, `s3` and `chaos` join in later PRDs (ARCHITECTURE §9). Run it through the root `Makefile` (`make up`, `make down`, `make reset`, `make logs`).
