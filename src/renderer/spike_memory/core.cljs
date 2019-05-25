(ns spike-memory.core
  (:require [clojure.string :as str]
            [aid.core :as aid]
            [cats.core :as m]
            cljsjs.mousetrap
            [com.rpl.specter :as s]
            [frp.core :as frp]
            [linked.core :as linked]
            [reagent.core :as r]))

(frp/defe cancel
          edit
          typing
          all
          right
          wrong
          deleted
          down
          up
          sink-save
          save
          source-redraw
          source-current)

(def review
  (->> edit
       (aid/<$ false)
       (m/<> (aid/<$ true (m/<> cancel save)))
       (frp/stepper true)))

(def words
  (->> typing
       (frp/stepper "")
       (frp/snapshot save)
       (m/<$> (comp (partial apply linked/set)
                    str/split-lines
                    last))))

(def progress
  ;TODO implement this event
  (m/<$> (comp (partial apply linked/map)
               (partial (aid/flip interleave) (repeat :right)))
         words))

(def filter-status
  (->> (m/<> (aid/<$ :right right)
             (aid/<$ :deleted deleted)
             (aid/<$ :wrong wrong))
       (m/<$> #(partial filter (comp (partial = %)
                                     val)))
       (m/<> (aid/<$ identity all))
       (frp/stepper identity)))

(def sink-redraw
  (m/<> save up down all right deleted wrong))

(defn get-direction
  [f g]
  (->> (frp/snapshot source-redraw
                     (frp/stepper "" source-current)
                     filter-status
                     (frp/stepper {} progress))
       (m/<$> (fn [[_ current filter-status* progress*]]
                (->> progress*
                     (f (comp (partial not= current)
                              key))
                     g
                     filter-status*
                     keys)))
       (frp/stepper [])))

(def above
  (get-direction take-while identity))

(def below
  (get-direction drop-while rest))

(def current-behavior
  (frp/stepper "" source-current))

(def get-movement
  (partial m/<$> (aid/if-then-else (comp empty?
                                         last)
                                   second
                                   (comp first
                                         last))))

(def sink-current
  (m/<> (m/<$> first words)
        (m/<> (->> above
                   (m/<$> reverse)
                   (frp/snapshot up current-behavior)
                   get-movement)
              (->> below
                   (frp/snapshot down current-behavior)
                   get-movement))))

(def edit-component
  [:form
   [:textarea {:on-change #(-> %
                               .-target.value
                               typing)}]
   [:button {:on-click #(cancel)} "Cancel"]
   [:button {:on-click #(save)} "Save"]])

(defn direction-component
  [justification direction*]
  (->> direction*
       (mapv (partial vector :li))
       (s/setval s/BEGINNING
                 [:ul {:style {:display         "flex"
                               :flex-direction  "column"
                               :height          "45%"
                               :margin          0
                               :justify-content (str "flex-"
                                                     justification)}}])))

(aid/defcurried mnemonic-component
  [s path]
  [:webview {:src   (str path s)
             :style {:width "30%"}}])

(defn review-component
  [above* current below*]
  ;TODO add video
  (->> ["https://www.oxfordlearnersdictionaries.com/definition/english/"
        "https://duckduckgo.com/?ia=images&iax=images&q="]
       (mapv (mnemonic-component current))
       (s/setval s/BEGINNING
                 [:div
                  {:style {:display "flex"}}
                  [:div
                   {:on-double-click #(edit)
                    :style           {:height   "100%"
                                      :overflow "hidden"
                                      :width    "150px"}}
                   [direction-component "end" above*]
                   [:div {:style {:height          "10%"
                                  :display         "flex"
                                  :flex-direction  "column"
                                  :justify-content "center"}} current]
                   [direction-component "start" below*]]])))

(def review-view
  ((aid/lift-a review-component)
    above
    current-behavior
    below))

(defn app-component
  [review* review-view*]
  (if review*
    review-view*
    edit-component))

(def app-view
  ((aid/lift-a app-component)
    review
    review-view))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app"))
         app-view)

(def loop-event
  (partial run! (partial apply frp/run)))

(loop-event {source-current sink-current
             source-redraw  sink-redraw})

(defn bind
  [s e]
  (js/Mousetrap.bind s #(e)))

(def bind-keymap
  (partial run! (partial apply bind)))

(def keymap
  {"alt+a" all
   "alt+r" right
   "alt+d" deleted
   "alt+w" wrong
   "j"     down
   "k"     up})

(bind-keymap keymap)

(frp/activate)
