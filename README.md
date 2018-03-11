# re-dnd

## Introduction


Re-dnd (drag and drop) is a configurable widget for dragging items onto a drop-zone.
It is designed to be configurable, so that it looks and behaves like you want it to. Being re-frame based, it also provides
a nice API for querying or changing drag/drop state (through subscribe/dispatch).

You can configure what happens upon dropping an element, with sensible defaults in place if you don't.

Let's get started, shall we?

### Usage
First add this to your project.clj dependencies

[![Clojars Project](https://img.shields.io/clojars/v/re-dnd.svg)](https://clojars.org/re-dnd)

re-dnd will store all its state in the local app-db, just as the rest of your re-frame app.
To avoid name clashes, it will store all its state under the key `:dnd/state` in the db.

Below the usage is explained through an example. Check the [demo](http://sytematic.nl/re-dnd-demo/index.html) here.

First initialize everything properly
```
To make sure everything is initialized properly, require re-dnd.events in your core.cljs,
or where ever your entry point is.
(ns your-app.core
 (:require
   ...
   [re-dnd.events] ;; make sure the events are registered and inited properly.
   [re-dnd.views] ;;make sure the internal components are registered.
   [re-dnd.subs] ;; make sure subs are registered
   ...
   ))
```

Now we can create the visual part of the drag and drop. We define a draggable component and a drop-zone.

*NOTE:* we provide them with id's (normal keys, here :draggable-1 and :drop-zone-1), make sure these keys are unique globally, ie.
not shared with other draggables/drop-zones (on the same page). If you would do this, state changes of one draggable/drop-zone are overwriting state of others.
```
(ns my-project.my-view
  (:require
  [re-frame.core :as rf]
  [reagent.core :as r]
  [re-dnd.events :as dnd]
  [re-dnd.views :as dndv]))

(defn my-drop-zone
 []
 [dndv/drop-zone :drop-zone-1 ;;:drop-zone-1 is a unique identifier
  [:div "A custom body for the drop-zone (optional)"]])

(defn my-draggable
 []
  [dndv/draggable :draggable-1
   [:div "Style me any way you like, i'm draggable."]])



(defn my-panel
  []
  (let [;;this state is necesary to determine if we need to show the drag-box
        drag-box-state (rf/subscribe [:dnd/drag-box])]
    ;;It's best not to put this here, but for conciseness it is done here.
    ;;It should be called when loading the page with the dnd functionality.
    ;;It prepares the app-db with some initial state.
    (rf/dispatch [:dnd/initialize-drop-zone
                  :drop-zone-1 ;;note, this key is the same as in my-drop-zone above.
                  ;; options

                  {:drop-dispatch [:my-drop-dispatch] ;;re-frame event handler that will be called when draggable is dropped on a drop-zone
                  :ignore-drops-of-type #{:type1 type2} ;;optional, if you have more than one dropzone, and u want to exclude drop triggers from certain types
                  :drop-marker :my-drop-marker ;;multi-method dispatch-value for dnd/dropped-widget
                  }

    ])
    (fn []
      [:div
        ;; the drag-box widget is a visual that follows the mouse,
        ;; and takes the size of the draggable currently being dragged
        (when @drag-box-state
          [dndv/drag-box])

        [my-draggable]

        [my-drop-zone]])))

```

Now we have the basic stuff there. You might need some CSS to make it look decent. The point is that the draggable
can be dropped on the drop-zone, and then the [:my-drop-dispatch] event will be called. Let's implement this event:

```
(def last-id (r/atom 0))

(rf/reg-event-fx
 :my-drop-dispatch
 (fn [{db :db}
      [_
       ;; the callback contains two vectors, of the source and of the target.
       ;; Note the source-drop-zone-id, it's possible the dropped element actually comes from
       ;; a drop-zone (ie. re-ordering within the drop-zone). In the example above, we have an external
       ;; draggable, in which case source-drop-zone-id would be nil.
       [source-drop-zone-id source-element-id]
       [drop-zone-id dropped-element-id dropped-position]]] ;;position = index in the list of dropped elements
   (swap! last-id inc)
   {:db       db
    :dispatch
    ;;if the source drop-zone and target drop-zone is the same, it means we need to re-order the items
    ;; (at least, in this example we want that, but what you want is completely up to you :-))
    (if (= source-drop-zone-id drop-zone-id)
      ;;built-in dispatch for re-ordering elements in a drop-zone
      [:dnd/move-drop-zone-element drop-zone-id source-element-id dropped-position]

      ;;Built-in dispatch for adding a drop-zone-element ('dropped-element') in a drop-zone.
      ;;Our current logic is to just add a new entry to the drop-zone.
      ;;Your requirement might be different.
      [:dnd/add-drop-zone-element
       drop-zone-id
       {:id   (keyword (str (name source-element-id) "-dropped-" @last-id))
        ;;The type key is the dispatch-value of the dndv/dropped-widget multi-method.
        ;;thus, by means of multi-methods we can create any component we'd like.
        :type (if (odd? @last-id )
                :bluebox
                :redbox)}
       dropped-position])}))

```

Before dropping an item, a visual is shown indicating where the component will be dropped (ie. between which already existing dropped elements). You can create a custom one like so:

```
;;this is a multi method implementation fo dndv/dropped-widget.
;;You can create multiple ones, and based on the :type key in the options map of this thing
(defmethod dndv/dropped-widget
 :my-drop-marker
 [{:keys [type id]}]
  [:div.my-drop-marker "Some visual showing when dragged object is hovering over drop zone."])
```

If you don't supply the :drop-marker key upon initialization of the drop-zone, by default this implementation will be used.
```
;;we dispatch on the :type key
(defmulti dropped-widget
  (fn [{:keys [type id]}] type))

;;default drop-marker, which you could style using CSS.
(defmethod dropped-widget
  :dnd/drop-marker
  [{:keys [type id]}]
  [:div.drop-marker])
```

## CSS classes
Some default components come with CSS classes. The following base CSS should be put into place, and tweaked where you see fit:

```
.draggable, dropped-element {
   cursor: move;
   position: relative;
}

.draggable .drag-mask,
.dropped-element .drag-mask {
   position: absolute;
   top: 0px;
   left: 0px;
   width: 0px;
   height: 0px;
   background-color: rgba(0,0,0,0.2);
   z-index: 9999;
}

.drop-zone.highlight {
   border: 1px solid #000;
}

.drop-marker {
   width: 100%;
   height: 4px;
   margin-bottom: 5px;
   background-color: black;
}

.drag-box {
  position: fixed;
  background-color: rgba(0,0,0,0.2);
  z-index: 999;
  height: 0px;
  width: 0px;
}
```

## More of the API, events and subscriptions

Since this is re-frame, all the registered events are available to you. Here are some that could come in handy:
```
;;Adds it at position/index 2, this parameter is optional, if omitted, element will be added to the back
(rf/dispatch [:dnd/add-drop-zone-element :my-drop-zone-id :my-dropped-element-id 2])
;;Moves it to the new index 2
(rf/dispatch [:dnd/move-drop-zone-element :my-drop-zone-id :my-dropped-element-id 2])
;;Deletes a dropped element from the drop-zone
(rf/dispatch [:dnd/delete-drop-zone-element :my-drop-zone-id :my-dropped-element-id])

```

Also, there are various subscriptions that might be of use. They're used internally, but some could be useful to you.
```
;; returns a map of drop-zone-id to a list of elements within the drop-zone that are also colliding, and their positional index in the drop-zone
;; ie. {:my-drop-zone-1 [[:my-dropped-element-1 0] [:my-dropped-element-2 1]] ...}
(rf/subscribe [:dnd/get-colliding-drop-zone-and-index])

;;all drop-zone elements in the drop-zone, returns a list of maps, each map containing at least keys :type and :id,
;; optionally have key :status
(rf/subscribe [:dnd/dropped-elements :my-drop-zone-id])

;;same as above, but on the correct spot, it also returns a map of type {:type :my-drop-marker :id :my-drop-marker},
;;indicating the drop marker. Only shows the drop-marker if there is hover activity of a draggable over the drop-zone.
;;Internally we map over the result of this sub, and dispatch the dndv/dropped-widget multimethod with the record.
(rf/subscribe [:dnd/dropped-elements-with-drop-marker :my-drop-zone-id])

;;lists all registered drop-zones and their state as a map, keyed with drop-zone-id.
(rf/subscribe [:dnd/drop-zones])

;;do we have a colliding draggable over this drop-zone?
(rf/subscribe [:dnd/draggable-overlaps? :my-drop-zone-id])

;;position of the mouse
(rf/subscribe [:dnd/mouse-position])

;;position of the drag-box, when dragging
(rf/subscribe [:dnd/drag-box])
```


## Development Mode

### Start Cider from Emacs:

Put this in your Emacs config file:

```
(setq cider-cljs-lein-repl
	"(do (require 'figwheel-sidecar.repl-api)
         (figwheel-sidecar.repl-api/start-figwheel!)
         (figwheel-sidecar.repl-api/cljs-repl))")
```

Navigate to a clojurescript file and start a figwheel REPL with `cider-jack-in-clojurescript` or (`C-c M-J`), or if you use Spacemacs, just press `,"`.

### Run the demo application, which depends on the library:

```
lein dev
```

Figwheel will automatically push cljs changes to the browser.

Wait a bit, then browse to [http://localhost:3449](http://localhost:3449).

### Run tests:

```
lein clean
lein doo phantom test once
```

The above command assumes that you have [phantomjs](https://www.npmjs.com/package/phantomjs) installed. However, please note that [doo](https://github.com/bensu/doo) can be configured to run cljs.test in many other JS environments (chrome, ie, safari, opera, slimer, node, rhino, or nashorn).

## Production Build

To compile clojurescript to javascript:

```
lein build
```
