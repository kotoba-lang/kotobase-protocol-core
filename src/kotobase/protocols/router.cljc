(ns kotobase.protocols.router
  "Host-based dispatch for the kotobase protocol surfaces
  (ADR-2607171700, split per ADR-2608051000):

    s3.<apex>       → the :s3 handler
    ipfs.<apex>     → the :ipfs handler
    atproto.<apex>  → the :atproto handler
    git.<apex>      → the :git handler

  plus a single-origin fallback for deploys that only own one
  hostname: /ipfs/* and /xrpc/* dispatch by their protocol-inherent
  prefixes, /s3/* and /git/* by stripped mount prefixes.

  **Handlers are injected, not required.** Before the ADR-2608051000
  split this namespace `:require`d s3/ipfs/atproto/git directly, which
  is why the four surfaces could not live in separate repositories: the
  router is what tied them together at compile time. Now the caller
  supplies `:surfaces` (a map of label → handler) and, optionally,
  `:prefixes`; a surface that is absent from the map simply is not
  served, which is exactly what a per-capability deploy shell wants.

  A shell that serves exactly one host does not need this namespace at
  all — it can call its one handler directly. The router exists for the
  combined deployment and for self-hosted mesh peers, where the apex is
  injectable so the same code serves any domain."
  (:require [clojure.string :as str]
            [kotobase.protocols.http :as http]))

(def ^:private default-prefixes
  "Ordered fallbacks for a single-origin deploy. Each entry is
  [path-prefix surface-label strip?] — strip? drops the mount prefix
  before handing the request on, which /s3 and /git need because their
  wire formats are rooted at the mount point, while /ipfs and /xrpc
  carry protocol-inherent prefixes their handlers expect to see."
  [["/ipfs/" :ipfs false]
   ["/ipns/" :ipfs false]
   ["/xrpc/" :atproto false]
   ["/s3/"   :s3   true]
   ["/git/"  :git  true]])

(defn surface-of
  "\"s3.kotobase.net\" + apex \"kotobase.net\" → \"s3\"; nil when host
  is not a single label in front of the apex."
  [host apex]
  (when (and host (str/ends-with? host (str "." apex)))
    (let [label (subs host 0 (- (count host) (inc (count apex))))]
      (when (and (seq label) (not (str/includes? label ".")))
        label))))

(defn- strip-prefix [req prefix]
  (assoc req :path (subs (:path req) (count prefix))))

(defn handle
  "Route `req` to its protocol surface.

  ctx: {:store ... :now ... :apex \"kotobase.net\"
        :surfaces {:s3 f :ipfs f :atproto f :git f}
        :prefixes [[\"/s3/\" :s3 true] ...]}

  `:surfaces` keys are keywords; the host label is matched against
  their names. Only the surfaces present are served — /health included,
  so a shell does not advertise readiness for a surface it does not
  carry."
  [{:keys [apex surfaces prefixes] :or {apex "kotobase.net"} :as ctx} req]
  (let [path (or (:path req) "")
        prefixes (or prefixes default-prefixes)
        label (surface-of (:host req) apex)
        surface (when label (get surfaces (keyword label)))]
    (cond
      (and (= :get (:method req)) (= "/health" path) surface)
      (http/response 200
                     {"content-type" "application/edn; charset=utf-8"
                      "cache-control" "no-store"}
                     (pr-str {:ok true
                              :service (keyword (str "kotobase.protocols/" label))
                              :surface (keyword label)
                              :apex apex}))

      surface (surface ctx req)

      :else
      (if-let [[prefix k strip?] (first (filter (fn [[p k _]]
                                                  (and (str/starts-with? path p)
                                                       (get surfaces k)))
                                                prefixes))]
        ((get surfaces k) ctx (if strip?
                                (strip-prefix req (subs prefix 0 (dec (count prefix))))
                                req))
        (http/not-found "no protocol surface for this host/path")))))
