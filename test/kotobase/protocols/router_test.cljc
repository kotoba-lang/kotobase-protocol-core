(ns kotobase.protocols.router-test
  "Dispatch tests for the registry-driven router.

  These use stub handlers on purpose. Before ADR-2608051000 this suite
  drove the real s3/ipfs/atproto/git handlers through the router, which
  is precisely the coupling that kept the four surfaces in one
  repository. What core owns is the *dispatch decision*: which label
  wins, which prefix falls back, what an absent surface does. The
  cross-surface integration test that exercises real handlers lives in
  the kotobase-protocols facade, which still depends on all five."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotobase.protocols.router :as router]))

(defn- stub
  "A handler that echoes which surface it was and what path it saw, so a
  test can assert both dispatch and prefix stripping."
  [k]
  (fn [_ctx req] {:status 200 :surface k :path (:path req)}))

(def ^:private all
  {:s3 (stub :s3) :ipfs (stub :ipfs) :atproto (stub :atproto) :git (stub :git)})

(defn- ctx [surfaces] {:now "2026-08-05T00:00:00Z" :surfaces surfaces})

(deftest surface-of
  (is (= "s3" (router/surface-of "s3.kotobase.net" "kotobase.net")))
  (is (= "atproto" (router/surface-of "atproto.kotobase.net" "kotobase.net")))
  (is (nil? (router/surface-of "kotobase.net" "kotobase.net")))
  (is (nil? (router/surface-of "a.b.kotobase.net" "kotobase.net")))
  (is (nil? (router/surface-of "s3.example.net" "kotobase.net")))
  (is (nil? (router/surface-of nil "kotobase.net"))))

(deftest host-based-dispatch
  (let [c (ctx all)]
    (testing "every registered subdomain has a no-store health boundary"
      (doseq [surface ["s3" "ipfs" "atproto" "git"]
              :let [res (router/handle c {:method :get
                                          :host (str surface ".kotobase.net")
                                          :path "/health"})]]
        (is (= 200 (:status res)))
        (is (= "no-store" (get-in res [:headers "cache-control"])))
        (is (str/includes? (:body res) (str ":surface :" surface)))))
    (testing "a subdomain routes to its own handler"
      (doseq [surface [:s3 :ipfs :atproto :git]]
        (is (= surface (:surface (router/handle c {:method :get
                                                   :host (str (name surface) ".kotobase.net")
                                                   :path "/x"}))))))))

(deftest absent-surface-is-not-served
  (testing "a shell carrying only s3 does not answer for git — neither the
            handler nor the health boundary, so readiness is not claimed
            for a surface this deployment does not have"
    (let [c (ctx {:s3 (stub :s3)})]
      (is (= :s3 (:surface (router/handle c {:method :get :host "s3.kotobase.net" :path "/x"}))))
      (is (= 404 (:status (router/handle c {:method :get :host "git.kotobase.net" :path "/x"}))))
      (is (= 404 (:status (router/handle c {:method :get :host "git.kotobase.net" :path "/health"})))))))

(deftest single-origin-prefix-fallback
  (let [c (ctx all)
        call (fn [path] (router/handle c {:method :get :host "kotobase.net" :path path}))]
    (testing "protocol-inherent prefixes reach their handler with the path intact"
      (is (= {:status 200 :surface :ipfs :path "/ipfs/bafy"} (call "/ipfs/bafy")))
      (is (= {:status 200 :surface :ipfs :path "/ipns/k51"} (call "/ipns/k51")))
      (is (= {:status 200 :surface :atproto :path "/xrpc/com.atproto.repo.getRecord"}
             (call "/xrpc/com.atproto.repo.getRecord"))))
    (testing "mount prefixes are stripped before the handler sees them"
      (is (= {:status 200 :surface :s3 :path "/bkt/key"} (call "/s3/bkt/key")))
      (is (= {:status 200 :surface :git :path "/org/repo/info/refs"}
             (call "/git/org/repo/info/refs"))))
    (testing "an unmatched path is a 404, not a 500"
      (is (= 404 (:status (call "/nope")))))))

(deftest prefix-fallback-skips-absent-surfaces
  (testing "a prefix whose surface is not registered falls through rather
            than dispatching to nil"
    (let [c (ctx {:ipfs (stub :ipfs)})]
      (is (= :ipfs (:surface (router/handle c {:method :get :host "kotobase.net" :path "/ipfs/bafy"}))))
      (is (= 404 (:status (router/handle c {:method :get :host "kotobase.net" :path "/s3/bkt/key"})))))))

(deftest apex-is-injectable
  (testing "the same router serves a self-hosted peer on another domain"
    (let [c (assoc (ctx all) :apex "example.org")]
      (is (= :s3 (:surface (router/handle c {:method :get :host "s3.example.org" :path "/x"}))))
      (is (= 404 (:status (router/handle c {:method :get :host "s3.kotobase.net" :path "/x"})))))))
