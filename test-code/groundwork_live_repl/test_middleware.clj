(ns groundwork-live-repl.test-middleware
  "A middleware for the tests: answers the op \"live-repl-test/hello\", so a
   test can see the app's middleware was added."
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]))

(defn wrap-hello [handler]
  (fn [{:keys [op] :as message}]
    (if (= "live-repl-test/hello" op)
      (transport/send (:transport message) (response-for message :status :done :hello "from the app's middleware"))
      (handler message))))

(set-descriptor! #'wrap-hello
                 {:requires #{"clone"}
                  :expects  #{}
                  :handles  {"live-repl-test/hello" {:doc "Says hello." :requires {} :optional {} :returns {}}}})
