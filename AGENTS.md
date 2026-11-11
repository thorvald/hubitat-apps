# Agent working notes

Keep README.md limited to identifying this as a Hubitat apps repository. Put only necessary working notes and references here; avoid unsolicited explanatory documentation.

## Code and packaging

- `apps/afar-room/` contains the linked Afar Room parent and child apps. Preserve their names, `thorvald` namespace, and parent/child relationship.
- `apps/mitsubishi-comfort/` contains the Mitsubishi Comfort app and its two child drivers, Mitsubishi Comfort Unit and Mitsubishi Comfort Stream. Preserve their names and the `thorvald` namespace. The app creates one Unit device per indoor unit, with network ID `comfort-<serial>`, and one Stream device (`comfortstream`) that holds the websocket for live updates, since Hubitat allows websockets only in drivers. Each file carries the package version in its `VERSION` constant.
- The Mitsubishi Comfort cloud API is unofficial and reverse-engineered, so check the references below before changing requests. Temperatures are Celsius on the wire. Fahrenheit setpoints go through Mitsubishi's own conversion table in the driver rather than arithmetic, so that Hubitat, the Comfort app and the remotes agree; the table comes from the comfort_HA integration.
- `repository.json` is the HPM repository index. `apps/afar-room/packageManifest.json` packages both apps as required components, with the parent first and marked primary. `apps/mitsubishi-comfort/packageManifest.json` packages the app as primary and both drivers as required components.
- Preserve manifest UUIDs across releases. When publishing an app update, keep package and app versions aligned, update release metadata, and publish source and metadata together. HPM download URLs use `main`.
- Hubitat runs Groovy 2.4 in a sandbox that only allows listed classes. The linter parses with a newer Groovy, so newer syntax and unlisted classes pass lint and fail on the hub.

## Local tools

- Install pinned dependencies with `npm ci`; runtime requirements are in `package.json` and the linter documentation.
- `npm run lint` reports findings without modifying source. `npm run lint:report` writes `reports/groovy-lint.json`. Warnings and errors produce a nonzero exit status.
- `.groovylintrc.json` sets four-space indentation, relaxes static-compilation, explicit-type, and class-documentation rules for Hubitat scripts, turns off `NoDouble` (a guard for exact monetary arithmetic, which nothing here needs), and allows up to 60 methods per script.
- `npm run format` modifies source; use it for requested formatting and review the diff. Lint results do not verify Hubitat runtime behavior or all dynamic names.

## References

- [Hubitat developer documentation](https://docs2.hubitat.com/en/developer)
- [Hubitat allowed classes](https://docs2.hubitat.com/en/developer/allowed-imports)
- [Mitsubishi Comfort API notes and Python client](https://github.com/ventz/mitsubishi-comfort-api-kumo)
- [Mitsubishi Comfort API field observations](https://github.com/hughobrien/comfortd/blob/main/docs/api-inventory.md)
- [comfort_HA, source of the Fahrenheit setpoint table](https://github.com/jjustinwilson/comfort_HA/blob/main/custom_components/kumo_cloud/climate.py)
- [HPM documentation](https://hubitatpackagemanager.hubitatcommunity.com/)
- [HPM developer documentation and manifest format](https://hubitatpackagemanager.hubitatcommunity.com/devs1.html)
- [npm-groovy-lint](https://github.com/nvuillam/npm-groovy-lint)
- [CodeNarc rules](https://codenarc.org/codenarc-rule-index.html)
