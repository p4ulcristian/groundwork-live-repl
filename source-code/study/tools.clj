(ns study.tools
  "The app's tools, here while study's REPL is open: plain functions, each
   the app's own, under the name the app gave it in start-repl-server!'s
   :tools. study knows nothing of what they do, and puts nothing else here.

     (dir study.tools)                     ; which tools there are
     (doc study.tools/ask-as-person)       ; what one does, in the app's words
     (study.tools/ask-as-person {:orders/of-shop {:shop/id shop-id}} person-id)

   They are put here when the REPL opens and taken away when it closes.
   Nothing of clojure.core is referred here, so a tool may take any name."
  (:refer-clojure :only []))
