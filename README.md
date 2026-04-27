# graphql-server

A Clojure library for defining GraphQL schemas using Malli, wrapping Lacinia with Ring support.

## Features

- Malli-based schema definition
- Lacinia GraphQL engine
- Ring middleware support
- No Pedestal dependency

## Installation

### Stable release (Clojars)

Once published to Clojars, add to your `deps.edn`:

```clojure
{:deps {net.carcdr/graphql-server {:mvn/version "0.1.1"}}}
```

### Snapshot stream (GitHub Packages)

If you want to track `main` between releases, consume the `-SNAPSHOT`
artifact published to GitHub Packages on every CI-green push:

```clojure
;; deps.edn
{:deps {net.carcdr/graphql-server {:mvn/version "0.1.2-SNAPSHOT"}}
 :mvn/repos
 {"github-graphql-server"
  {:url "https://maven.pkg.github.com/edpaget/graphql-server"}}}
```

GitHub Packages requires authentication even for public reads. Configure
`~/.m2/settings.xml` with a personal access token (scope `read:packages`)
following GitHub's docs:
<https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages>.

## Usage

TODO

## Releasing

Releases are cut by manually dispatching the **Release** workflow
(`.github/workflows/release.yml`) and picking a bump level
(`patch`, `minor`, or `major`). The workflow updates `VERSION`,
`CHANGELOG.md`, and `README.md`, tags `vX.Y.Z`, and deploys the JAR
to Clojars. After a successful deploy it commits a follow-up
`X.Y.Z+1-SNAPSHOT` bump back to `main`.

### Prerequisites (one-time setup)

1. **Clojars deploy token** — create a deploy token (not your account
   password) at <https://clojars.org/tokens> scoped to
   `net.carcdr/graphql-server`. Add the following repo secrets at
   **Settings → Secrets and variables → Actions**:
   - `CLOJARS_USERNAME` — your Clojars username
   - `CLOJARS_PASSWORD` — the deploy token

2. **Release GitHub App** — register a GitHub App (Settings → Developer
   settings → GitHub Apps → New GitHub App) with `contents: write`
   permission, install it on this repo, and add:
   - `RELEASE_APP_ID` — the App ID
   - `RELEASE_APP_PRIVATE_KEY` — the App's private key (PEM contents)

   This is required so the post-release snapshot-bump commit pushed
   back to `main` re-triggers CI and the snapshot publish workflow.
   The default `GITHUB_TOKEN` cannot trigger downstream workflows.

3. **Branch protection on `main`** — at **Settings → Branches**,
   require these status checks before merge:
   - `Lint and Format`
   - `Test`

   The release workflow gates on these check-run names against the
   tip of `main` before deploying; without branch protection enforcing
   them, an uncheck-run commit can reach `main` and the gate will fail.

### Cutting a release

1. Go to **Actions → Release → Run workflow**.
2. Pick a bump level: `patch`, `minor`, or `major`.
3. Watch the run. On success:
   - `vX.Y.Z` tag is pushed.
   - JAR is deployed to Clojars (visible at
     <https://clojars.org/net.carcdr/graphql-server>).
   - `main` advances by two commits: the release and the next-snapshot bump.

### Bump semantics

The current `VERSION` is always `X.Y.Z-SNAPSHOT`, representing the
in-development next version.

- `patch` releases the current `X.Y.Z-SNAPSHOT` as `X.Y.Z` (drops the
  suffix), then bumps to `X.Y.(Z+1)-SNAPSHOT`.
- `minor` releases as `X.(Y+1).0`, then bumps to `X.(Y+1).1-SNAPSHOT`.
- `major` releases as `(X+1).0.0`, then bumps to `(X+1).0.1-SNAPSHOT`.

## License

See LICENSE file in repository root.
