# Migrating re-dnd to Pointer Events

Status: **proposal / not yet started**
Author: drafted with Claude (for @Kah0ona)
Target: re-dnd `0.2.0`

## Why

re-dnd today drives drags with **mouse events** and a pair of **global
`document.body` listeners**. That design has three structural weaknesses:

1. **No touch support.** `mousedown`/`mousemove`/`mouseup` don't fire from
   touch input, so the widget is desktop-only. Tablets are a first-class
   surface for the apps that consume re-dnd.
2. **Native text-selection side effects.** A bare `mousedown` starts a browser
   text selection; the browser then auto-scrolls the nearest scroll container
   toward the selection anchor, so the board "jumps" the instant a drag starts.
   `0.1.20` patches this with `(.preventDefault e)` in `start-drag-fn`, but
   that's treating a symptom.
3. **Global-listener fragility.** `reg-event-listeners` (events.cljs) attaches
   `mousemove`/`mousedown`/`mouseup` to `document.body` and re-derives "which
   element is being dragged" on every move via `find-first-dragging-element`.
   Move events can be missed when the cursor leaves the window, and the global
   state (`:mouse-button`) is a workaround for not having capture.

**Pointer Events** (`pointerdown`/`pointermove`/`pointerup`/`pointercancel` +
`setPointerCapture`) solve all three at once and are the modern standard
(this is what dnd-kit etc. use). They are **not** HTML5 native drag-and-drop —
we keep our own drag ghost (`#drag-box`) and our own geometry/collision logic.
See the appendix for why native HTML5 DnD is the wrong target.

### What this migration fixes

- ✅ Touch + pen + mouse through one code path.
- ✅ Selection-scroll jump goes away at the source (`touch-action: none` +
  implicit no-selection during capture) — the `0.1.20` `preventDefault` and
  consumers' `user-select: none` band-aids become unnecessary.
- ✅ No dropped move events: a captured pointer keeps delivering `pointermove`
  to the same element even when the pointer is outside it or off-window.
- ✅ Removes the `document.body` global listeners and the `:mouse-button`
  gating state.
- ⚠️ Partially helps the "marker shows in two lanes on hover" glitch (more
  consistent move stream), but the real fix there is marker de-duplication in
  the overlap subs — tracked separately below.

### What stays the same (public API — no consumer breakage)

- `re-dnd.views/draggable`, `drop-zone`, and the `dropped-widget` /
  `drag-handle` multimethods.
- `:dnd/initialize-drop-zone`, `:dnd/add-drop-zone-element`,
  `:dnd/move-drop-zone-element`, `:dnd/delete-drop-zone-element`.
- Drop-zone options: `:drop-dispatch`, `:three-part-drag-handle`,
  `:drop-marker`.
- The `[source-zone source-elt] [target-zone target-elt target-pos]` shape
  passed to `:drop-dispatch`.

Consumers (e.g. Catermonkey's pipeline board) should need **zero** code
changes — only a version bump.

---

## Current architecture (recap)

```
mousedown on .drag-handle / .draggable
  └─ start-drag-fn (views.cljs)
       → :dnd/start-drag id dz left top width height      ;; element → :dragging + :position

document.body "mousemove"  (reg-event-listeners, events.cljs)
  └─ :dnd/mouse-moves (clientX+scrollX) (clientY+scrollY)
       → updates [:dnd/state :mouse-position]
       → if :mouse-button down AND a dragging element exists:
            :dnd/drag-move id dz x y                       ;; moves #drag-box, clears selection

document.body "mousedown"/"mouseup"
  └─ :dnd/set-mouse-button-status true/false
       → on release, find-first-dragging-element → :dnd/end-drag

:dnd/end-drag  (injects :dnd/get-colliding-drop-zone-and-index)
  └─ getBoundingClientRect collision of #drag-box vs each #drop-zone-/#dropped-element-
  └─ fires the zone's :drop-dispatch, then set-all-draggables-to-idle
```

Key DOM ids the collision math relies on (keep these):
`#drag-box`, `drop-zone-<id>`, `dropped-element-<id>`.

---

## Target architecture (Pointer Events + capture)

```
pointerdown on .drag-handle / .draggable
  └─ start-drag-fn
       → (.setPointerCapture el (.-pointerId e))           ;; route all further pointer* here
       → (.preventDefault e)                                ;; belt; touch-action does the real work
       → :dnd/start-drag id dz pointerId left top width height

pointermove on the SAME element (because of capture)
  └─ :dnd/pointer-move id dz pointerId clientX clientY      ;; replaces the body mousemove
       → updates [:dnd/state :mouse-position]
       → :dnd/drag-move id dz x y

pointerup / pointercancel on the SAME element
  └─ :dnd/pointer-end id dz pointerId
       → :dnd/end-drag id dz                                ;; unchanged collision + drop-dispatch
       → (.releasePointerCapture el pointerId)
```

Capture is the crux: after `setPointerCapture`, the **capturing element**
receives every subsequent `pointermove`/`pointerup`/`pointercancel` for that
`pointerId`, no matter where the pointer goes. So we attach the move/end
handlers to the draggable element itself and **delete the `document.body`
global listeners entirely**. We also no longer need `:mouse-button` state —
being captured *is* the "am I dragging" signal.

We keep updating `[:dnd/state :mouse-position]` on every move because the
overlap subs (`:dnd/dropped-item-overlap-id`,
`:dnd/dropped-elements-with-drop-marker`) re-run off it.

---

## Step-by-step

### 1. CSS / consumer guidance (the real selection fix)

Add to re-dnd's stylesheet (and document for consumers):

```css
.draggable,
.dropped-element .drag-handle {
  touch-action: none;     /* stop the browser scrolling/zooming/selecting on a drag-start */
  -webkit-user-select: none;
  user-select: none;
}
```

Scope `touch-action: none` to the **drag handles**, not whole lanes, so a
finger-scroll inside a lane body still scrolls normally and only a drag from
the grip captures.

### 2. `re-dnd.views/start-drag-fn`

```clojure
(defn start-drag-fn
  [id drop-zone-id e _e2]
  (.preventDefault e)
  (let [target (.-currentTarget e)
        p      (or (dom/getAncestorByClass (.-target e) "dropped-element")
                   (dom/getAncestorByClass (.-target e) "draggable"))
        bounds (style/getBounds p)]
    ;; capture so pointermove/up/cancel keep coming to THIS element
    (.setPointerCapture target (.-pointerId e))
    (rf/dispatch [:dnd/start-drag id drop-zone-id (.-pointerId e)
                  (.-left bounds) (.-top bounds)
                  (.-width bounds) (.-height bounds)])))

(defn pointer-move-fn [id drop-zone-id e]
  (rf/dispatch [:dnd/pointer-move id drop-zone-id (.-pointerId e)
                (.-clientX e) (.-clientY e)]))

(defn pointer-end-fn [id drop-zone-id e]
  (let [target (.-currentTarget e)]
    (when (.hasPointerCapture target (.-pointerId e))
      (.releasePointerCapture target (.-pointerId e)))
    (rf/dispatch [:dnd/pointer-end id drop-zone-id (.-pointerId e)])))
```

### 3. `draggable` / `dropped-element` views

Swap the mouse handlers for pointer handlers on the same elements:

```clojure
;; draggable
{:on-pointer-down  (partial start-drag-fn id nil)
 :on-pointer-move  (partial pointer-move-fn id nil)
 :on-pointer-up    (partial pointer-end-fn id nil)
 :on-pointer-cancel (partial pointer-end-fn id nil)
 :on-pointer-over  (partial hover-fn id nil true)
 :on-pointer-out   (partial hover-fn id nil false)}
```

For `dropped-element`'s `.drag-handle`, do the same. The non-`three-part`
handle currently binds `:on-mouse-down start-drag` + `:on-mouse-up reorder` —
replace with `:on-pointer-down`/`:on-pointer-up`/`:on-pointer-move`/
`:on-pointer-cancel`. The `three-part-drag-handle` caret buttons stay on
`:on-click` (clicks still fire normally; capture only affects pointer* events).

### 4. `re-dnd.events`

- **Delete** `reg-event-listeners` and its call in `:dnd/initialize-drop-zone`.
  (Capture replaces the global listeners.) If you want to keep a global
  safety-net `pointercancel`/`pointerup` on `window` for stuck states, add a
  single capture-phase `pointerup` that dispatches `:dnd/pointer-end` for the
  active pointer — optional.
- **Add** `:dnd/pointer-move` — same body as today's `:dnd/mouse-moves` but
  driven by capture and without the `:mouse-button` gate (capture ⇒ dragging):

  ```clojure
  (rf/reg-event-fx
    :dnd/pointer-move
    (fn [{db :db} [_ id drop-zone-id _pointer-id client-x client-y]]
      (let [x   (+ client-x (.-scrollX js/window))
            y   (+ client-y (.-scrollY js/window))
            db' (assoc-in db [:dnd/state :mouse-position] {:x x :y y})]
        {:db db' :dispatch [:dnd/drag-move id drop-zone-id x y]})))
  ```

- **Add** `:dnd/pointer-end` → just `{:dispatch [:dnd/end-drag id drop-zone-id]}`.
  Keep `:dnd/end-drag` exactly as-is (collision + `:drop-dispatch` + idle).
- **Remove** `:dnd/set-mouse-button-status` and the `:mouse-button` reads in
  `:dnd/mouse-moves`/`:dnd/hover`. `find-first-dragging-element` can stay (used
  by `:dnd/end-drag`) or be replaced by passing the ids through `pointer-end`.
- `:dnd/start-drag` gains a `pointer-id` arg; otherwise unchanged. Stash the
  active pointer-id in `[:dnd/state :active-pointer]` if you add the window
  safety-net.
- `clear-selection` (called from `:dnd/drag-move`) becomes a no-op once
  `touch-action: none` / `user-select: none` are in place; safe to drop.

### 5. Coordinates

Pointer events expose the same `clientX`/`clientY` as mouse events, so the
`(+ clientX scrollX)` math is unchanged. (You could switch to `pageX/pageY` and
drop the `scrollX/scrollY` additions, but keep it identical to minimise risk in
the first pass.)

### 6. Optional: edge auto-scroll (covers the "off-screen lane" complaint)

Inside the move handler, when the drag-box nears the edge of the nearest
scrollable ancestor of `#drag-box`, nudge its `scrollLeft`/`scrollTop` on a
`requestAnimationFrame` loop while the pointer stays in the edge band. This is
the one genuine win HTML5-native gives for free; with pointers it's ~20 lines
and fully under our control. Ship it as a separate follow-up commit so the core
migration stays reviewable.

---

## Edge cases / gotchas

- **`pointercancel`** fires when the browser takes over (e.g. a system gesture).
  Must end the drag cleanly — treat identically to `pointerup`.
- **Multi-touch:** ignore secondary pointers while one is captured. Guard
  `start-drag-fn` so a second `pointerdown` during an active drag is a no-op
  (check `[:dnd/state :active-pointer]`).
- **`setPointerCapture` target:** capture on the element the handler is bound to
  (`currentTarget`), and only release the `pointerId` you captured.
- **`getBoundingClientRect` collisions** are unchanged — `#drag-box`,
  `drop-zone-<id>`, `dropped-element-<id>` ids must remain.
- **Mouse parity:** Pointer Events cover mouse on all evergreen browsers; no
  separate mouse path needed. (Drop IE11 if it was ever supported.)
- **Two-lanes marker glitch** is *not* fully a capture issue — it's that
  `:dnd/dropped-elements-with-drop-marker` can resolve an overlap in more than
  one zone. Fix separately by computing the single winning zone once (highest
  overlap area) in `:dnd/get-colliding-drop-zone-and-index` and only inserting
  the marker there.

---

## Testing checklist

- [ ] Desktop mouse: drag within a zone (reorder), across zones (move), drop on
      empty zone — `:drop-dispatch` payloads identical to `0.1.x`.
- [ ] Touch (real device or DevTools touch emulation): same three flows.
- [ ] No scroll jump on drag-start in a horizontally-scrolled container.
- [ ] Pointer leaves the window mid-drag and returns — no dropped move events.
- [ ] `pointercancel` (trigger via browser gesture) ends the drag cleanly.
- [ ] `three-part-drag-handle` carets still move items via `:on-click`.
- [ ] Existing `doo` tests pass; add a capture/touch case under `test/`.
- [ ] Smoke-test against a real consumer (Catermonkey pipeline board) before
      tagging.

---

## Rollout

1. Branch `feat/pointer-events` off `master`.
2. Land the core migration (steps 1–5), keep the public API stable.
3. Land edge auto-scroll and marker-dedup as separate commits.
4. Version bump `0.2.0` (behaviour-compatible API, new input layer → minor).
5. `lein install` for local consumer testing, then `lein deploy clojars`.
6. Consumers bump the dep; no code changes expected. They can delete their
   `user-select: none` drag band-aids once verified.

---

## Appendix: why not native HTML5 Drag-and-Drop?

It superficially removes the selection-scroll and gives free edge auto-scroll,
but:

- **No touch support at all** — `dragstart` never fires from touch; you'd need a
  second implementation anyway. Disqualifying for tablet-using consumers.
- **Browser-owned drag ghost** — `setDragImage` is limited/inconsistent; you
  lose the styled `#drag-box`.
- **`dragenter`/`dragleave` fire on child crossings** — the same "marker in two
  zones" class of bug, but now baked into the platform API.
- **`dragover` + `preventDefault` and `dataTransfer` footguns** across browsers.

Pointer Events keep the parts of re-dnd that already work (custom ghost,
geometry, the re-frame event model and public API) and replace only the brittle
input layer — with one code path that also covers touch.
