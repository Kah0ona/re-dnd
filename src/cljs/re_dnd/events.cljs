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

(defn flip-args
  [f x y]
  (f y x))

(defn bounding-rect
  [e]
  (if (nil? e)
    nil
    (let [rect (.getBoundingClientRect e)
          pos  {:top    (.-top rect)
                :left   (.-left rect)
                :bottom (.-bottom rect)
                :right  (.-right rect)}

          pos' (-> pos
                  (update :top    + (.-scrollY js/window))
                  (update :right  + (.-scrollX js/window))
                  (update :bottom + (.-scrollY js/window))
                  (update :left   + (.-scrollX js/window)))
          ]
      ;;(debug pos)
      ;;(debug pos')
      pos'
      )))

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
                              (map (fn [v']
                                     (-> v'
                                         (assoc :status :idle)
                                         (dissoc :position)))
                                   v)])
                           o))))
      (update-in [:dnd/state :draggables]
              (fn [o]
                (into {}
                      (map (fn [[k v]]
                             [k (assoc v :status :idle)])
                           o))))))

(defn insert-at-pos
  [pos hmap e]
  (let [[h t] (split-at pos hmap)]
    (concat h [e] t)))

(defn move-element-in-list
  [m k new-pos]
  (let [e          (-> (filter (fn [{id :id}]
                                 (= id k)) m)
                       first)
        [h t]      (split-at new-pos m)
        comparator #(= (:id %) k)]
    (concat (remove comparator h) [e] (remove comparator t))))

(re-frame/reg-event-db
 :dnd/delete-drop-zone-element
 (fn [db [_ dz-id elt-id]]
   (update-in db [:dnd/state :drop-zones dz-id]
              #(remove (fn [{id :id}]
                         (= %2 id))
                       %1)
              elt-id)))

(re-frame/reg-event-db
 :dnd/move-drop-zone-element
 (fn [db [_ dz-id e-id new-pos]]
   (update-in db [:dnd/state :drop-zones dz-id] move-element-in-list e-id new-pos)))

(re-frame/reg-event-db
 :dnd/add-drop-zone-element
 (fn [db [_ drop-zone-id {:keys [id type] :as elt} dropped-position]]
   (assert id "Please set a :id key in the second parameter of options.")
   (when-not type
     (warn "Please set a :type key in the second parameter of options"))
   (if-not dropped-position ;;append
     (update-in db [:dnd/state :drop-zones drop-zone-id] conj elt)
     (update-in db
                [:dnd/state :drop-zones drop-zone-id]
                (partial insert-at-pos dropped-position)
                elt))))

(re-frame/reg-event-db
 :dnd/initialize-drop-zone
 (fn [db [_ id opts]]
   (-> db
       (assoc-in [:dnd/state :drop-zone-options id] opts)
       (assoc-in [:dnd/state :drop-zones id] []))))

(defn find-first-dragging-element
  [db]
  (let [d (->> (get-in db [:dnd/state :draggables])
               (filter (fn [[k v]] (= (:status v) :dragging)))
               ;;gets the key
               ffirst)
        d' (->> (get-in db [:dnd/state :drop-zones])
                (map (fn [[dz-id dz]]
                       [dz-id (->> dz
                                   (filter
                                    (comp (partial = :dragging) :status))
                                   first
                                   :id)]))
                (remove (fn [[dz-id dz]]
                          (nil? dz)))
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
     (debug [drop-zone-id draggable-id])
     (cond->
         {:db (assoc db :mouse-button down?)}
       (and (not down?) draggable-id)
       (assoc :dispatch [:dnd/end-drag draggable-id drop-zone-id])))))

(re-frame/reg-event-fx
 :dnd/mouse-moves
 (fn [{db :db} [_ x y]]
;;   (debug "mouse-moves:" x y)
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

(defn update-dz-elt
  [db drop-zone-id elt-id f]
  (update-in db [:dnd/state :drop-zones drop-zone-id]
             (fn [elts id']
               (map (fn [e]
                      (if (= id' (:id e))
                        (f e)
                        e))
                    elts))
             elt-id))

(re-frame/reg-event-db
 :dnd/drag-move
 (fn [db [_ id drop-zone-id x y]]
   ;;(debug "drag-move" id x y)
   (assert id)
   (when id
     (clear-selection))
   (let [pos     {:x (- (- x (.-scrollX js/window)) 20)
                  :y (- (- y (.-scrollY js/window)) 20)}]
     (if drop-zone-id
       (update-dz-elt db drop-zone-id id (fn [e]
                                        (assoc e :position pos)))
       (assoc-in db [:dnd/state :draggables id :position] pos)))))

(re-frame/reg-event-db
 :dnd/hover
 (fn  [db [_ id drop-zone-id hover-in?]]
   (let []
     (if (:mouse-button db)
       db
       (if drop-zone-id
         (update-dz-elt db drop-zone-id id
                        (fn [e]
                          (assoc e :status (if hover-in? :hover :idle))))
         ;;else just a normal draggable
         (assoc-in db [:dnd/state :draggables id :status] (if hover-in? :hover nil)))))))

(re-frame/reg-event-db
 :dnd/start-drag
 (fn  [db [_ id drop-zone-id x y w h]]
   (let []
     (debug (str "start-drag " drop-zone-id "," id ", x: " x ", y: " y ", w: " w ", h: " h))
     (let [pos {:x      (- (- x (.-scrollX js/window)) 20)
                :y      (- (- y (.-scrollY js/window)) 20) ;;discount for scroll pos
                :width  w
                :height h}]
       (debug (:y pos) y (.-scrollY js/window))
       (if drop-zone-id

         (-> db
             (update-dz-elt drop-zone-id id
                            (fn [e]
                              (assoc e
                                     :status :dragging
                                     :position pos))))
         ;;else just a normal draggable
         (-> db
             (assoc-in [:dnd/state :draggables id :status] :dragging)
             (assoc-in [:dnd/state :draggables id :position] pos)))))))


(re-frame/reg-event-fx
 :dnd/end-drag
 [(re-frame/inject-cofx ::inject/sub [:dnd/get-colliding-drop-zone-and-index])]
 (fn  [{db                    :db
        drop-zones-being-hit? :dnd/get-colliding-drop-zone-and-index}
       [_ source-draggable-id source-drop-zone-id]]
   (debug drop-zones-being-hit?)
   {:db         (set-all-draggables-to-idle db)
    :dispatch-n (for [[drop-zone-id [[dropped-element-id index]]] drop-zones-being-hit?]
                  (let [options                  (get-in db [:dnd/state :drop-zone-options drop-zone-id])
                        drag-target-hit-dispatch (into (:drop-dispatch options)
                                                       [[source-drop-zone-id source-draggable-id]
                                                        [drop-zone-id dropped-element-id index]])]
                    drag-target-hit-dispatch))}))

(re-frame/reg-event-db
 :dnd/reorder-drop
 (fn [db [_ drop-zone-id dropped-element-id]]
   (let [drag-box    (bounding-rect (.getElementById js/document "drag-box"))
         drop-zone   (bounding-rect (.getElementById js/document (str "drop-zone-" drop-zone-id)))]
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
