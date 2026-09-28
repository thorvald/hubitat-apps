# Agent working notes

Keep README.md limited to identifying this as a Hubitat apps repository. Put only necessary working notes and references here; avoid unsolicited explanatory documentation.

## Code and packaging

- `apps/afar-room/` contains the linked Afar Room parent and child apps. Preserve their names, `thorvald` namespace, and parent/child relationship.
- `repository.json` is the HPM repository index. `apps/afar-room/packageManifest.json` packages both apps as required components, with the parent first and marked primary.
- Preserve manifest UUIDs across releases. When publishing an app update, keep package and app versions aligned, update release metadata, and publish source and metadata together. HPM download URLs use `main`.

## Local tools

- Install pinned dependencies with `npm ci`; runtime requirements are in `package.json` and the linter documentation.
- `npm run lint` reports findings without modifying source. `npm run lint:report` writes `reports/groovy-lint.json`. Warnings and errors produce a nonzero exit status.
- `.groovylintrc.json` sets four-space indentation and relaxes static-compilation, explicit-type, and class-documentation rules for Hubitat scripts.
- `npm run format` modifies source; use it for requested formatting and review the diff. Lint results do not verify Hubitat runtime behavior or all dynamic names.

## References

- [Hubitat developer documentation](https://docs2.hubitat.com/en/developer)
- [HPM documentation](https://hubitatpackagemanager.hubitatcommunity.com/)
- [HPM developer documentation and manifest format](https://hubitatpackagemanager.hubitatcommunity.com/devs1.html)
- [npm-groovy-lint](https://github.com/nvuillam/npm-groovy-lint)
- [CodeNarc rules](https://codenarc.org/codenarc-rule-index.html)
