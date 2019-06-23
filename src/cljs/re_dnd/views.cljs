(ns re-dnd.views
  (:require [goog.dom :as dom]
            [goog.style :as style]
            [re-dnd.events :as dnd]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [taoensso.timbre :as timbre
             :refer-macros (log  trace  debug  info  warn  error  fatal  report
                                 logf tracef debugf infof warnf errorf fatalf reportf
                                 spy get-env log-env)]))

(defmulti dropped-widget
  (fn [{:keys [type id]}] type))

(defmethod dropped-widget
  :dnd/drop-marker
  [{:keys [type id]}]
  [:div.drop-marker])

(defmulti drag-handle
  (fn [{:keys [type id]}] type))

(defmethod drag-handle
  :dnd/drop-marker
  [{:keys [type id]}]
  [:div.drop-marker])

(defn start-drag-fn
  [id drop-zone-id e e2]
  (let  [p (or (dom/getAncestorByClass (.-target e) "dropped-element")
               (dom/getAncestorByClass (.-target e) "draggable"))
         bounds (style/getBounds p)]
    (rf/dispatch [:dnd/start-drag id
                  drop-zone-id
                  (.-left bounds)
                  (.-top bounds)
                  (.-width bounds)
                  (.-height bounds)])))

(defn hover-fn
  [elt-id drop-zone-id hover-in?]
  (rf/dispatch [:dnd/hover elt-id drop-zone-id hover-in?]))

(defn draggable
  ([id]
   [draggable id nil])
  ([id body]
   (let [drag-status (rf/subscribe [:dnd/drag-status id nil])]
     (fn [id body]
       [:div.draggable
        {:on-mouse-down (partial start-drag-fn id nil)
         :on-mouse-over (partial hover-fn id nil true)
         :on-mouse-out (partial hover-fn id nil false)}
        (when (= :hover @drag-status)
          [:div.drag-mask
           {:style {:width "100%"
                    :height "100%"}}])
        (when body
          body)]))))

(defn reorder-fn
  [drop-zone-id dropped-element-id e]
  (rf/dispatch [:dnd/reorder-drop drop-zone-id dropped-element-id]))

(defn dropped-element
  [id de]
  (let [drag-status (rf/subscribe [:dnd/drag-status (:id de) id])]
    (fn [id de]
      [:div.dropped-element.row
       {:id (str "dropped-element-" (name (:id de)))}
       #_(when (= :hover @drag-status)
          [:div.drag-mask
           {:style {:width  "100%"
                    :height "100%"}}])

       [:div.drag-handle.col-md-1
        {:on-mouse-over (partial hover-fn (:id de) id true)
         :on-mouse-out  (partial hover-fn (:id de) id false)
         :on-mouse-down (partial start-drag-fn (:id de) id)
         ;;drop-zone elements can be re-ordered, this is the only functionality
         :on-mouse-up   (partial reorder-fn id (:id de))}
        [drag-handle de]]
       [:div.dropped-element-body.col-md-11
        [dropped-widget de]]
       [:div {:style {:clear :both}}]])))

(defn drop-zone
  ([id]
   [drop-zone id nil])
  ([id body]
   (let [dropped-elements (rf/subscribe [:dnd/dropped-elements-with-drop-marker id])
         overlaps?        (rf/subscribe [:dnd/draggable-overlaps? id])]
     (fn [id body]
       [:div.drop-zone
        {:id        (str "drop-zone-" (name id))
         :className (if @overlaps? "highlight" "")}
        (map (fn [de]
               ^{:key (:id de)}
               [dropped-element id de])
             @dropped-elements)
        (when body body)]))))

(defn drag-box
  "Box floating around following the mouse"
  []
  (let [s (rf/subscribe [:dnd/drag-box])]
    (fn []
      (let [{:keys [width height x y] :as drag-box} @s]
        [:div#drag-box.drag-box
         {:style {:display (if (nil? drag-box) "none")
                  :width  (str width "px")
                  :height (str height "px")
                  :top    (str y "px")
                  :left   (str x "px")}}]))))
