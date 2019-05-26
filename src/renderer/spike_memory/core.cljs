(ns spike-memory.core
  (:require [clojure.string :as str]
            [aid.core :as aid]
            [cats.core :as m]
            [com.rpl.specter :as s]
            [frp.core :as frp]
            [garden.core :refer [css]]
            [linked.core :as linked]
            [reagent.core :as r]))

(def electron
  (js/require "electron"))

(def remote
  electron.remote)

(def window-state-keeper
  (js/require "electron-window-state"))

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

(def configs
  (linked/set {:path      "https://www.oxfordlearnersdictionaries.com/definition/english/"
               :selectors ["#ox-header"
                           "#header"
                           ".menu_button"
                           "#ad_topslot_a"
                           ".entry-header"
                           ".btn"
                           ".xr-gs"
                           ".pron-link"
                           ".social-wrap"
                           "#rightcolumn"
                           "#ox-footer"
                           "a.go-to-top"]}
              {:path      "https://duckduckgo.com/?ia=images&iax=images&q="
               :selectors ["#header_wrapper"]}))

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
       (map (partial vector :li))
       (concat [:ul {:style {:display         "flex"
                             :flex-direction  "column"
                             :height          "45%"
                             :margin          0
                             :justify-content (str "flex-"
                                                   justification)}}])
       vec))

(defn review-component
  [above* current below*]
  [:div
   {:on-double-click #(edit)
    :style           {:height "100%"
                      :width  "100%"}}
   [direction-component "end" above*]
   [:div {:style {:height          "10%"
                  :display         "flex"
                  :flex-direction  "column"
                  :justify-content "center"}} current]
   [direction-component "start" below*]])

(def review-view
  ((aid/lift-a (partial vector review-component))
    above
    current-behavior
    below))

(defn app-component
  [review* review-view*]
  (if review*
    review-view*
    edit-component))

(def app-view
  ((aid/lift-a (partial vector app-component))
    review
    review-view))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app"))
         app-view)

(def loop-event
  (partial run! (partial apply frp/run)))

(loop-event {source-current sink-current
             source-redraw  sink-redraw})

(def get-window
  #(let [window-state (window-state-keeper. #js{:file (str "window-state/"
                                                           %
                                                           ".json")})]
     (doto
       (remote.BrowserWindow. window-state)
       window-state.manage)))

(defonce contents
  (->> configs
       (map-indexed (fn [k v]
                      [(get-window k) v]))
       (into (linked/map))))

(def get-css
  (comp css
        (partial s/setval*
                 s/AFTER-ELEM
                 {:display "none !important"})))

(aid/defcurried render-content
  [word [window config]]
  (-> window
      (.loadURL (-> config
                    :path
                    (str word)))
      (.then #(-> config
                  :selectors
                  get-css
                  window.webContents.insertCSS))
      (.catch aid/nop)))

(frp/run (comp (partial (aid/flip run!) contents)
               render-content)
         sink-current)

(defn bind
  [menu s e]
  (->> #js{:accelerator s
           :click       #(e)}
       (remote.MenuItem.)
       (.append menu)))

(def bind-keymap
  #(let [menu (remote.Menu.getApplicationMenu)]
     (run! (partial apply bind menu) %)
     (remote.Menu.setApplicationMenu menu)))

(def keymap
  {"Alt+A" all
   "Alt+R" right
   "Alt+D" deleted
   "Alt+W" wrong
   "J"     down
   "K"     up})

(bind-keymap keymap)

(frp/activate)
