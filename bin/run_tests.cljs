;; nbb test runner. Run from the repo root:
;;
;;   nbb --classpath "src:test" bin/run_tests.cljs
;;
;; core has no dependencies, so no sibling checkout is needed.
(ns run-tests
  (:require [cljs.test :as t]
            [kotobase.protocols.json-test]
            [kotobase.protocols.router-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'kotobase.protocols.json-test
             'kotobase.protocols.router-test)
