(ns study.test-middleware
  "A middleware for the tests: answers the op \"study-test/hello\", so a
   test can see the app's middleware was added."
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]))

(defn wrap-hello [handler]
  (fn [{:keys [op] :as message}]
    (if (= "study-test/hello" op)
      (transport/send (:transport message) (response-for message :status :done :hello "from the app's middleware"))
      (handler message))))

(set-descriptor! #'wrap-hello
                 {:requires #{"clone"}
                  :expects  #{}
                  :handles  {"study-test/hello" {:doc "Says hello." :requires {} :optional {} :returns {}}}})
