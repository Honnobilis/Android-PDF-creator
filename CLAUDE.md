# Project conventions

- Android apps are built for 64-bit ARM only (`abiFilters += listOf("arm64-v8a")`). Do not add 32-bit ABIs.
- APKs are built by GitHub Actions (`.github/workflows/build.yml`); the debug key in `app/debug.keystore` is fixed so builds install as updates.
- UI language: English.
