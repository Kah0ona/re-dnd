(ns re-dnd-demo.events
  (:require [re-dnd-demo.db :as db]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [taoensso.timbre :as timbre
             :refer-macros (log  trace  debug  info  warn  error  fatal  report
                                 logf tracef debugf infof warnf errorf fatalf reportf
                                 spy get-env log-env)]))

(rf/reg-event-db
 :initialize-db
 (fn  [_ _]
   db/default-db))

(def last-id (r/atom 0))

(rf/reg-event-fx
 :my-drop-dispatch
 (fn [{db :db}
      [_
       [source-drop-zone-id source-element-id]
       [drop-zone-id dropped-element-id dropped-position]]]
   ;;position = index in the list of dropped elements
   (debug "source:" source-drop-zone-id source-element-id
          " target:" drop-zone-id dropped-element-id dropped-position)
   (swap! last-id inc)
   {:db       db
    :dispatch
    (if (= source-drop-zone-id drop-zone-id)
      [:dnd/move-drop-zone-element drop-zone-id source-element-id dropped-position]

      [:dnd/add-drop-zone-element
       drop-zone-id
       {:id   (keyword (str (name source-element-id) "-dropped-" @last-id))
        :type (if (odd? @last-id )
                :bluebox
                :redbox)}
       dropped-position])}))
