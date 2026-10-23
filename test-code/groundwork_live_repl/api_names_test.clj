(ns groundwork-live-repl.api-names-test
  "The public names of groundwork-live-repl's api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-live-repl.api]))

(deftest the-api-names
  (is (= '#{settings-shape options-shape start-repl-server! find-repl-port stop-repl-server!}
         (set (keys (ns-publics 'groundwork-live-repl.api))))))
