(ns re-dnd-demo.core
  (:require [re-dnd-demo.config :as config]
            [re-dnd-demo.views :as my-views]
            [re-dnd.events]
            [re-dnd.subs]
            [re-dnd.views :as views]
            [re-frame.core :as re-frame]
            [reagent.core :as reagent]))

(defn dev-setup []
  (when config/debug?
    (devtools.core/enable-feature! :sanity-hints)
    (devtools.core/install!)
    (enable-console-print!)
    (println "dev mode")))

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [my-views/main-panel]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (re-frame/dispatch-sync [:initialize-db])
  (dev-setup)
  (mount-root))
