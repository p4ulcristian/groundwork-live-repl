(ns groundwork-live-repl.tools
  "The app's tools, here while groundwork-live-repl's REPL is open: plain functions, each
   the app's own, under the name the app gave it in start-repl-server!'s
   :tools. groundwork-live-repl knows nothing of what they do, and puts nothing else here.

     (dir groundwork-live-repl.tools)                     ; which tools there are
     (doc groundwork-live-repl.tools/ask-as-person)       ; what one does, in the app's words
     (groundwork-live-repl.tools/ask-as-person {:orders/of-shop {:shop/id shop-id}} person-id)

   They are put here when the REPL opens and taken away when it closes.
   Nothing of clojure.core is referred here, so a tool may take any name."
  (:refer-clojure :only []))
