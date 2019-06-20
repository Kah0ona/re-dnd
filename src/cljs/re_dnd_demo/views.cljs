(ns re-dnd-demo.views
  (:require [fipp.clojure :refer [pprint]]
            [re-dnd-demo.events :as h]
            [re-dnd.events :as dnd]
            [re-dnd.views :as dndv]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [taoensso.timbre :as timbre
             :refer-macros (log  trace  debug  info  warn  error  fatal  report
                                 logf tracef debugf infof warnf errorf fatalf reportf
                                 spy get-env log-env)]))

(rf/reg-sub :db
            (fn [db] db))

(defn debug-panel
  "pretty prints data in a nice box on the screen."
  [s]
  (let [collapsed (r/atom false)]
    (fn [s]
      [:div.debug-window-wrap
       [:div {:on-click #(swap! collapsed not)
              :style {:cursor :pointer
                      :padding "10px"
                      :border "1px solid #ccc"}}
        [:div.clear
         [:div.pull-right [:b "Debug window "]]
         [:div (if @collapsed "▷ expand" "▽ collapse")]]]
       (when-not @collapsed
         [:pre (with-out-str (pprint s))])])))

;;this should have its own file, custom_events
(defmethod dndv/dropped-widget
  :my-drop-marker
  [{:keys [type id]}]
  [:div.drop-marker])

(defmethod dndv/drag-handle
  :my-drop-marker
  [{:keys [type id]}]
  [:div])

(defmethod dndv/dropped-widget
  :bluebox
  [{:keys [type id]}]
  [:div.box.blue-box
   (str type ", " id)])

(defmethod dndv/drag-handle
  :bluebox
  [{:keys [type id]}]
  [:div "bluedraghandlee"])

(defmethod dndv/dropped-widget
  :redbox
  [{:keys [type id]}]
  [:div.box.red-box
   (str type ", " id)])

(defmethod dndv/drag-handle
  :redbox
  [{:keys [type id]}]
  [:div "reddraghandle"])

(defn main-panel
  []
  (let [drag-box-state (rf/subscribe [:dnd/drag-box])
        db             (rf/subscribe [:db])
        last-id        (r/atom 0)]
    (rf/dispatch [:dnd/initialize-drop-zone
                  :drop-zone-1
                  {:drop-dispatch [:my-drop-dispatch]
                   :drop-marker   :my-drop-marker}])
    (fn []
      [:div.container
       {:style {:height "1400px"}}
       [:div {:style {:position :absolute
                      :border   "1px solid black"
                      :top      "400px"}}
        [dndv/drag-box]
        #_(when @drag-box-state
            [dndv/drag-box]) ;;this thing follows the mouse, and takes over the draggable's size
        [:div
         [:p "Drag draggables to the drop-zone to the right, or re-order dropped elements in the drop-zone"]
         #_[debug-panel @db]
         [:button.btn.btn-primary
          {:on-click #(do
                        (swap! last-id inc)
                        (rf/dispatch [:dnd/add-drop-zone-element
                                      :drop-zone-1
                                      {:type (if (odd? @last-id) :redbox :bluebox)
                                       :id   (keyword (str "drop-zone-element-" @last-id))}]))}
          "Add element to dropzone programmatically"]
         [:div.clear]
         [:div {:style {:float :left}}
          [dndv/draggable :draggable1 [:span "draggable1"]]
          [dndv/draggable :draggable2 [:span "draggable2"]]
          [dndv/draggable :draggable3 [:span "draggable3"]]]
         [:div {:style {:float :right}}
          [dndv/drop-zone :drop-zone-1
           [:div "Drop zone"]]]]]
       [:div.clear]

       #_[debug-panel @db]])))
