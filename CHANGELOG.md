# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Automated `-SNAPSHOT` publish to GitHub Packages on every CI-green push to `main`.
- Manually-dispatched release workflow with patch/minor/major bump picker that
  updates `VERSION`, `CHANGELOG.md`, `README.md`, tags, and deploys to Clojars.
- "Releasing" section in `README.md` documenting the release workflow
  prerequisites (Clojars deploy token, release GitHub App, branch
  protection) and the dispatch-and-pick-bump-level flow.

## [0.1.0] - 2026-04-27

### Added
- Initial public release of `graphql-server`: a code-first GraphQL library
  built on Lacinia with Malli-driven schema generation, Ring transport,
  and core.async-based subscriptions.

[Unreleased]: https://github.com/edpaget/graphql-server/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/edpaget/graphql-server/releases/tag/v0.1.0
