(ns re-dnd.subs
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [re-dnd.events :as h]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as timbre
             :refer-macros (log  trace  debug  info  warn  error  fatal  report
                                 logf tracef debugf infof warnf errorf fatalf reportf
                                 spy get-env log-env)]))

(re-frame/reg-sub
 :dnd/position
 (fn [db [_ id]]
   (get-in db [:dnd/state :draggables id :position])))

(defn get-dz-element
 [db dz-id draggable-id]
 (->>
  (get-in db [:dnd/state :drop-zones dz-id])
  (filter (fn [{id :id}]
            (= id draggable-id)))
  first))

(re-frame/reg-sub
 :dnd/drag-box
 (fn [db _]
   (let [[drop-zone-id draggable-id] (h/find-first-dragging-element db)]
     (if (and drop-zone-id draggable-id)
       (:position
        (get-dz-element db drop-zone-id draggable-id))
       ;;else
       (when draggable-id
         (get-in db [:dnd/state :draggables draggable-id :position]))))))

(re-frame/reg-sub
 :dnd/db
 (fn [db _]
   db))

(re-frame/reg-sub
 :dnd/drag-status
 (fn [db [_ id drop-zone-id]]
   (if drop-zone-id
     (:status (get-dz-element db drop-zone-id id))
     (get-in db [:dnd/state :draggables id :status]))))

(re-frame/reg-sub
 :dnd/draggable-overlaps?
 (fn [db [_ id]]
   ;;returns true if the position of :drag-box overlaps the drop-zone with the supplied id.
   (let [drop-zone (h/bounding-rect (.getElementById
                                     js/document
                                     (str "drop-zone-" (name id))))
         drag-box  (h/bounding-rect (.getElementById
                                     js/document
                                     "drag-box"))
         collides? (h/collides? drop-zone drag-box)]
     collides?)))

(re-frame/reg-sub
 :dnd/mouse-position
 (fn [db _]
   (get-in db [:dnd/state :mouse-position])))

(defn calculate-drop-zone-collisions
  "Returns a list of tuples of [<dropzone-id> <index>]"
  [dropzone-elements]
  (let [drag-box (h/bounding-rect (.getElementById js/document "drag-box"))]
    (->> dropzone-elements
         (map-indexed (fn [i e]
                        [(inc i) e]))
         (filter
          (fn [[i e]]
            (if (not (:id e))
              false
              (let [dropped-rect (h/bounding-rect
                                  (.getElementById
                                   js/document
                                   (str "dropped-element-" (name (:id e)))))]
                (h/collides? dropped-rect drag-box)))))
         (map (fn [[i e]]
                [(:id e) i])))))

(re-frame/reg-sub
 :dnd/dropped-item-overlap-id
 (fn [[_ id] _]
   ;; we need this to trigger a re-run of this sub when the mouse moves.
   ;; these derived signal graphs only rerun if signal changes,
   ;; but draggable-overlaps? can become stable
   ;; we do need that one for improved efficiency
   [(re-frame/subscribe [:dnd/mouse-position])
    (re-frame/subscribe [:dnd/draggable-overlaps? id])
    (re-frame/subscribe [:dnd/dropped-elements id])])
 (fn [[mouse-pos draggable-overlaps? dropzone-elements]]
   ;;returns true if the position of :drag-box overlaps the dragtarget of the supplied id.
   (if-not draggable-overlaps?
     nil
     ;;else ,we have overlap, calculate with which of the dropped elements we collide, and return the first one if there's more than one
     (-> dropzone-elements
         calculate-drop-zone-collisions
         ffirst))))

(re-frame/reg-sub
 :dnd/dragged-element
 (fn [db _]
   (let [draggable-being-dragged   (->>
                                    db
                                    :dnd/state
                                    :draggables
                                    vals
                                    (filter #(= :dragging (:status %)))
                                    first)
         droppeditem-being-dragged (->>
                                    db
                                    :dnd/state
                                    :drop-zones
                                    vals
                                    flatten
                                    (filter #(= :dragging (:status %)))
                                    first)]
     (or draggable-being-dragged droppeditem-being-dragged))))

(re-frame/reg-sub
 :dnd/dragdrop-options
 (fn [db [_ id]]
   (get-in db [:dnd/state :drop-zone-options id])))

(re-frame/reg-sub
 :dnd/dropped-elements-with-drop-marker
 (fn [[_ id] _]
   [(re-frame/subscribe [:dnd/dragged-element])
    (re-frame/subscribe [:dnd/dragdrop-options id])
    (re-frame/subscribe [:dnd/dropped-item-overlap-id id])
    (re-frame/subscribe [:dnd/draggable-overlaps? id])
    (re-frame/subscribe [:dnd/dropped-elements id])])
 (fn [[dragged-element options overlap-id overlap-dropzone? dropzone-elements]]
   (if (and (or overlap-dropzone? overlap-id)
            dragged-element)
     ;;we have overlap, and there is dragging going on, insert the separator in there.
     (debug dragged-element)
     (let [dm    (:drop-marker options)
           sep   (if dm
                   {:type dm
                    :id   dm}
                   {:type :dnd/drop-marker
                    :id   :dnd/drop-marker})
           parts (partition-by #(= overlap-id (:id %)) dropzone-elements)
           sz    (count parts)]
       (flatten
        (case sz
          0 [sep]
          1 [(first parts) sep]
          2 (if (= overlap-id (-> parts ffirst :id))
              [(first parts) sep (last parts)]
              ;;else, put sep at the back
              [(first parts) (last parts) sep])
          3 [(first parts) (second parts) sep (last parts)])))
     ;;else no dragging going on, return the elements
     (do (debug dropzone-elements)
         dropzone-elements))))

(re-frame/reg-sub
 :dnd/get-colliding-drop-zone-and-index
 ;; Returns collisions of currently dragged elements with drop-zone(s)
 ;; Returns a map from drop-zone-id
 :<- [:dnd/mouse-position]
 :<- [:dnd/drop-zones]
 :<- [:dnd/dragged-element]
 (fn [[mouse drop-zones dragged-element]]
   (debug mouse (count drop-zones) dragged-element)
   (into {}
         (comp
          (map
           (fn [[dz-id dz]]
             (let [drag-box (h/bounding-rect (.getElementById js/document "drag-box"))
                   dz-box   (h/bounding-rect (.getElementById js/document (str "drop-zone-" (name dz-id))))
                   k        (when (h/collides? drag-box dz-box)
                              dz-id)]
               [k (calculate-drop-zone-collisions dz)])))
          (remove
           (fn [[k _]]
             (nil? k))))
         drop-zones)))

(re-frame/reg-sub
 :dnd/drop-zones
 (fn [db _]
   (get-in db [:dnd/state :drop-zones])))

(re-frame/reg-sub
 :dnd/dropped-elements
 (fn [db [_ id]]
   (get-in db [:dnd/state :drop-zones id])))
