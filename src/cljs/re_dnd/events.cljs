(ns re-dnd.events
  (:require [re-frame.core :as re-frame]
            [re-frame.core :as rf]
            [reagent.core :as reagent]
            [taoensso.timbre :as timbre
             :refer-macros (log  trace  debug  info  warn  error  fatal  report
                                 logf tracef debugf infof warnf errorf fatalf reportf
                                 spy get-env log-env)]
            [vimsical.re-frame.cofx.inject :as inject]))

(set! js/document.body.onmousemove #(re-frame/dispatch [:dnd/mouse-moves
                                                        (+ (.-clientX %)
                                                           (.-scrollX js/window))
                                                        (+
                                                         (.-clientY %)
                                                         (.-scrollY js/window))]))
(set! js/document.body.onmousedown #(re-frame/dispatch [:dnd/set-mouse-button-status true]))
(set! js/document.body.onmouseup #(re-frame/dispatch [:dnd/set-mouse-button-status false]))

(defn bounding-rect
  [e]
  (if (nil? e)
    nil
    (let [rect (.getBoundingClientRect e)
          pos  {:top    (.-top rect)
                :left   (.-left rect)
                :bottom (.-bottom rect)
                :right  (.-right rect)}]
      (-> pos
          (update :top    + (.-scrollY js/window))
          (update :right  + (.-scrollX js/window))
          (update :bottom + (.-scrollY js/window))
          (update :left   + (.-scrollX js/window))))))

(defn collides?
  [{r1 :right l1 :left t1 :top b1 :bottom}
   {r2 :right l2 :left t2 :top b2 :bottom}]
  (not (or (< r1 l2) (> l1 r2) (< b1 t2) (> t1 b2))))

(defn translate
  "Moves a boundingClientRect with x and y pixels"
  [sub x y]
  (-> sub
      (update :left + x)
      (update :right - x)
      (update :top + y)
      (update :bottom - y)))

(defn set-all-draggables-to-idle
  [db]
  (-> db
      (update-in [:dnd/state :drop-zones]
              (fn [o]
                (into {}
                      (map (fn [[k v]]
                             [k
                              (into {}
                                    (map (fn [[k' v']]
                                           [k'
                                            (-> v'
                                                (assoc :status :idle)
                                                (dissoc :position))])
                                         v))])
                           o))))
      (update-in [:dnd/state :draggables]
              (fn [o]
                (into {}
                      (map (fn [[k v]]
                             [k (assoc v :status :idle)])
                           o))))))

(defn insert-at-pos
  [pos hmap e]
  (if (= 0 pos)
    (concat [[(:id e) e]] (map hmap))
    (let [[h t] (split-at pos hmap)]
      (debug hmap)
      (debug e)
      (debug (into {}
                   (concat h [[(:id e) e]] t)))
      (into {}
            (concat h [[(:id e) e]] t)))))


(defn move-element-in-map
  [m k new-pos]
  (let [e (get m k)
        [h t] (split-at new-pos m)
        _ (debug h)
        _ (debug t)
        _ (debug e)
        comparator (comp (partial = k) first)]
    (into {}
          (concat (remove comparator h) [[(:id e) e]] (remove comparator t)))))

(re-frame/reg-event-db
 :dnd/delete-drop-zone-element
 (fn [db [_ dz-id elt-id]]
   (update-in db [:dnd/state :drop-zones dz-id] dissoc elt-id)))

(re-frame/reg-event-db
 :dnd/move-drop-zone-element
 (fn [db [_ dz-id e-id new-pos]]
   (update-in db [:dnd/state :drop-zones dz-id]
              move-element-in-map e-id new-pos)))

(re-frame/reg-event-db
 :dnd/add-drop-zone-element
 (fn [db [_ drop-zone-id {:keys [id type] :as elt} dropped-position]]
   (assert id "Please set a :id key in the second parameter of options.")
   (when-not type
     (warn "Please set a :type key in the second parameter of options"))
   (if-not dropped-position ;;append
     (assoc-in db [:dnd/state :drop-zones drop-zone-id id] elt)
     (update-in db
                [:dnd/state :drop-zones drop-zone-id]
                (partial insert-at-pos dropped-position)
                elt))))

(re-frame/reg-event-db
 :dnd/initialize-drop-zone
 (fn [db [_ id opts]]
   (-> db
       (assoc-in [:dnd/state :drop-zone-options id] opts)
       (assoc-in [:dnd/state :drop-zones id] {}))))

(defn find-first-dragging-element
  [db]
  (let [d (->> (get-in db [:dnd/state :draggables])
               (filter (fn [[k v]] (= (:status v) :dragging)))
               ;;gets the key
               ffirst)
        d' (->> (get-in db [:dnd/state :drop-zones])
                (map (fn [[dz-id dz]]
                       [dz-id (->> dz
                                   vals
                                   (filter
                                    (fn [r]
                                      (= (:status r) :dragging)))
                                   first
                                   :id)]))
                first)]
    (if d
      [nil d]
      d')))

(re-frame/reg-event-fx
 :dnd/set-mouse-button-status
 (fn [{db :db} [_ down?]]
   ;;when not down?, check first dragging id, and handle a drop
   ;; through a re-dispatch for cleanliness
   (let [[drop-zone-id draggable-id] (find-first-dragging-element db)]
     (cond->
         {:db (assoc db :mouse-button down?)}
       (and (not down?) draggable-id)
       (assoc :dispatch [:dnd/end-drag draggable-id drop-zone-id])))))

(re-frame/reg-event-fx
 :dnd/mouse-moves
 (fn [{db :db} [_ x y]]
   (let [db' (assoc-in db [:dnd/state :mouse-position] {:x x :y y})]
     (if (:mouse-button db)
       (let [[drop-zone-id draggable-id] (find-first-dragging-element db)]
         (if draggable-id
           {:db       db'
            :dispatch [:dnd/drag-move draggable-id drop-zone-id x y]}
           {:db db'}))
       {:db db'}))))

(defn clear-selection
  []
  (let [sel (.-selection js/document)]
    (if (and sel (.hasOwnProperty sel "empty"))
      (.empty sel)
      ;;else
      (do
        (when (.-getSelection js/window)
          (.removeAllRanges (.getSelection js/window)))
        (if-let [ae (.-activeElement js/document)]
          (let [tag-name (-> ae .-nodeName .toLowerCase)]
            (if (or
                 (and
                  (= "text" (.-type ae))
                  (= "input" tag-name))
                 (= "textarea" tag-name))
              (set! (.-selectionStart ae) (.-selectionEnd ae)))))))))

(re-frame/reg-event-db
 :dnd/drag-move
 (fn [db [_ id drop-zone-id x y]]
   ;;(debug "drag-move" id x y)
   (assert id)
   (when id
     (clear-selection))
   (if drop-zone-id
     (assoc-in db [:dnd/state :drop-zones drop-zone-id id :position] {:x (- x 20)
                                                                 :y (- y 20)})

     (assoc-in db [:dnd/state :draggables id :position] {:x (- x 20)
                                                    :y (- y 20)}))))

(re-frame/reg-event-db
 :dnd/hover
 (fn  [db [_ id drop-zone-id hover-in?]]
   (let []
     (if (:mouse-button db)
       db
       (if drop-zone-id
         (assoc-in db [:dnd/state :drop-zones drop-zone-id id :status] (if hover-in? :hover :idle))
         ;;else just a normal draggable
         (assoc-in db [:dnd/state :draggables id :status] (if hover-in? :hover nil)))))))


(re-frame/reg-event-db
 :dnd/start-drag
 (fn  [db [_ id drop-zone-id x y w h]]
   (let []
     (debug (str "start-drag " drop-zone-id "," id ", x: " x ", y: " y ", w: " w ", h: " h))
     (if drop-zone-id
       (-> db
           (assoc-in [:dnd/state :drop-zones drop-zone-id id :status] :dragging)
           (assoc-in [:dnd/state :drop-zones drop-zone-id id :position] {:x      (- x 20)
                                                              :y      (- y 20)
                                                              :width  w
                                                              :height h}))
       ;;else just a normal draggable
       (-> db
           (assoc-in [:dnd/state :draggables id :status] :dragging)
           (assoc-in [:dnd/state :draggables id :position] {:x      (- x 20)
                                                 :y      (- y 20)
                                                 :width  w
                                                 :height h}))))))


(re-frame/reg-event-fx
 :dnd/end-drag
 [(re-frame/inject-cofx ::inject/sub [:dnd/get-colliding-drop-zone-and-index])]
 (fn  [{db                    :db
        drop-zones-being-hit? :dnd/get-colliding-drop-zone-and-index}
       [_ source-draggable-id source-drop-zone-id]]
   (debug drop-zones-being-hit?)
   (if-let [[drop-zone-id [[dropped-element-id index]]] (first drop-zones-being-hit?)]
     (let [options                  (get-in db [:dnd/state :drop-zone-options drop-zone-id])
           drag-target-hit-dispatch (into (:drop-dispatch options)
                                          [[source-drop-zone-id source-draggable-id]
                                           [drop-zone-id dropped-element-id index]])]
       {:db       (set-all-draggables-to-idle db)
        :dispatch drag-target-hit-dispatch})
     {:db (set-all-draggables-to-idle db)})))

(re-frame/reg-event-db
 :dnd/reorder-drop
 (fn [db [_ drop-zone-id dropped-element-id]]
   (let [drag-box    (bounding-rect (.getElementById js/document "drag-box"))
         drop-zone   (bounding-rect (.getElementById js/document (str "drop-zone-" drop-zone-id)))]
     (debug drop-zone-id dropped-element-id)
     (debug drag-box drop-zone)
     (cond
       (or
        (nil? drop-zone)
        (nil? drag-box))
       (do
         (debug "No dragbox / dropzone")
         db)

       (collides? drag-box drop-zone)
       (do (debug "Colliding!")
           db) ;; TODO fix this

       :otherwise ;;no-op
       (do
         (debug "No collide")
         db)))))
