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
{:deps {net.carcdr/graphql-server {:mvn/version "0.1.0"}}}
```

### Snapshot stream (GitHub Packages)

If you want to track `main` between releases, consume the `-SNAPSHOT`
artifact published to GitHub Packages on every CI-green push:

```clojure
;; deps.edn
{:deps {net.carcdr/graphql-server {:mvn/version "0.1.1-SNAPSHOT"}}
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

## License

See LICENSE file in repository root.
