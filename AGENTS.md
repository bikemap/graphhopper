# Repository Guidelines

## Project Structure & Module Organization
GraphHopper is a multi-module Maven project. `core/` hosts the routing engine (`core/src/main/java`) and its tests in `core/src/test/java`. `web/` wraps the Dropwizard server, while `web-api/` and `client-hc/` expose the HTTP contract. Import utilities live in `reader-gtfs/`, with domain-specific tools under `map-matching/`, `isochrone/`, and `navigation/`. Shared documentation resides in `docs/`, and scripts such as `graphhopper.sh` sit at the repository root. Keep generated data (e.g., `.osm-gh` directories) out of the tree.

## Build, Test, and Development Commands
Use Maven from the project root: `mvn clean install -DskipTests` compiles all modules and creates `web/target/graphhopper-web-*.jar`. `mvn clean test verify` runs the full test suite and quality checks; run it before every push. For rapid iteration on the server, `mvn -pl web -am package` rebuilds API-facing modules only. During local experiments, `./graphhopper.sh build` packages the web service, and `./graphhopper.sh --action web --config config-example.yml --input berlin-latest.osm.pbf` starts a Dropwizard instance on `localhost:8989`.

## Coding Style & Naming Conventions
Follow IntelliJ defaults with the shared `.editorconfig`: four-space indentation, Unix line endings, and a 100-character line width. Java classes use PascalCase, fields and methods camelCase, and YAML keys kebab-case (`graphhopper.graph.location`). Prefer descriptive names over abbreviations and avoid reformatting import blocks unless necessary.

## Testing Guidelines
JUnit-based tests live beside their modules (for example, `core/src/test/java`). New features need unit coverage plus integration coverage when a public API changes; name classes `*Test` or `*IT`. Execute `mvn clean test` locally and add targeted executions (e.g., `mvn -pl map-matching test`) for module-specific work. Include real-world fixtures under `src/test/resources` and keep them minimal.

## Commit & Pull Request Guidelines
Recent history shows short, imperative commit subjects (e.g., “Set result readonly after writing to it”). Reference relevant issues with `#123` when applicable and limit each commit to one logical change. Pull requests should describe the behaviour change, list validation steps or command outputs, and attach screenshots for UI-facing updates. Confirm `mvn clean test verify` passes before requesting review.
