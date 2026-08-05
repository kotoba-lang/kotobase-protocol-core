# kotobase-protocol-core

**The shared bottom of the kotobase protocol stack: HTTP request/response data, JSON, ETag fingerprints, the content-addressed block space, a memory store, and a registry-driven router.**

Split out of [`kotobase-protocols`](https://github.com/kotoba-lang/kotobase-protocols)
by superproject ADR-2608051000 (`1 repo = 1 capability`). Every surface repo
(`kotobase-protocol-{s3,ipfs,atproto,git}`) depends on this one and on nothing
else; surface repos never depend on each other.

| namespace | what it owns |
|---|---|
| `kotobase.protocols.http` | ring-shaped request/response as plain data |
| `kotobase.protocols.json` | dependency-free JSON encode/parse (cljc, string keys preserved) |
| `kotobase.protocols.hash` | FNV fingerprint for ETags — **not** cryptographic, **not** a CID |
| `kotobase.protocols.blocks` | shared content-addressed block space (CID is the key; it does not mint them) |
| `kotobase.protocols.store` | source-local deterministic memory host |
| `kotobase.protocols.router` | host-label dispatch with single-origin prefix fallback |

## The router takes its handlers, it does not require them

Before the split, `router` `:require`d s3/ipfs/atproto/git directly. That single
namespace is the reason the four surfaces could not live in separate
repositories — the router tied them together at compile time. It is now a
registry:

```clojure
(require '[kotobase.protocols.router :as router]
         '[kotobase.protocols.s3 :as s3])

(router/handle {:store store :apex "kotobase.net" :surfaces {:s3 s3/handle}}
               {:method :get :host "s3.kotobase.net" :path "/health"})
```

A surface absent from `:surfaces` is not served — including its `/health`, so a
deploy shell never advertises readiness for a surface it does not carry.

**A shell that serves exactly one host does not need this namespace at all**; it
can call its one handler directly. The router is for the combined deployment and
for self-hosted mesh peers, where `:apex` is injectable so the same code serves
any domain.

## Test

```bash
nbb --classpath "src:test" bin/run_tests.cljs
```

No sibling checkout needed — core depends on nothing. The `:test` alias in
`deps.edn` is the JVM compat suite only.

Router tests here use stub handlers on purpose: what core owns is the dispatch
decision. The cross-surface integration test that drives real handlers stays in
the `kotobase-protocols` facade.
