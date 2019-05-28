(ns spike-memory.core
  (:require [clojure.string :as str]
            [aid.core :as aid]
            [cats.core :as m]
            cljsjs.mousetrap
            [com.rpl.specter :as s]
            [frp.clojure.core :as core]
            [frp.core :as frp]
            [garden.core :refer [css]]
            [hodgepodge.core :refer [clear! local-storage]]
            [linked.core :as linked]
            [reagent.core :as r]
            [spike-memory.helpers :as helpers]))

(def electron
  (js/require "electron"))

(def remote
  electron.remote)

(def window-state-keeper
  (js/require "electron-window-state"))

(frp/defe cancel
          edit
          typing
          all-filter
          right-filter
          wrong-filter
          delete-filter
          down
          up
          right
          wrong
          save
          clear
          undo
          redo
          source-current
          source-progress)

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

(def current-behavior
  (frp/stepper (get-in local-storage [:state :current] "") source-current))

(def stored-progress
  (get-in local-storage [:state :progress] (linked/map)))

(def size
  10)

(def sink-progress
  (frp/undoable size
                undo
                redo
                [right wrong words]
                (->> current-behavior
                     (frp/snapshot (m/<> (aid/<$ :right right)
                                         (aid/<$ :wrong wrong)))
                     (m/<$> (partial apply (aid/flip array-map)))
                     (m/<> (frp/event stored-progress)
                           (m/<$> (comp (partial apply linked/map)
                                        (partial (aid/flip interleave)
                                                 (repeat :right)))
                                  words))
                     core/merge)))

(def progress-behavior
  (frp/stepper stored-progress
               sink-progress))

(def status
  (frp/stepper (get-in local-storage [:state :status] :all)
               (m/<> (aid/<$ :all all-filter)
                     (aid/<$ :right right-filter)
                     (aid/<$ :delete delete-filter)
                     (aid/<$ :wrong wrong-filter))))

(def filter-status
  (->> status
       (m/<$> (aid/if-then-else (partial = :all)
                                (constantly identity)
                                #(partial filter (comp (partial = %)
                                                       val))))))

(defn get-direction
  [f g]
  ((aid/lift-a (fn [current filter-status* progress*]
                 (->> progress*
                      (f (comp (partial not= current)
                               key))
                      g
                      filter-status*
                      keys)))
    current-behavior
    filter-status
    progress-behavior))

(def above
  (get-direction take-while identity))

(def below
  (get-direction drop-while rest))

(def state
  (->> ((aid/lift-a (comp (partial zipmap [:progress :current :status])
                          vector))
         progress-behavior
         current-behavior
         status)
       (frp/snapshot source-progress)
       (m/<$> last)))

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
               :selectors ["div#ad_contentslot_1"
                           "#ox-header"
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
   [:button {:on-click #(save)} "Save"]
   [:button {:on-click #(clear)} "Clear"]])

(def get-text-decoration
  #(case %
     :wrong "underline"
     :delete "line-through"
     "initial"))

(def get-status-style
  (comp (partial array-map :text-decoration)
        get-text-decoration))

(defn direction-component
  [justification direction* progress]
  (->> direction*
       (map (fn [word]
              [:li
               {:style (-> word
                           progress
                           get-status-style)}
               word]))
       (concat [:ul {:style {:display         "flex"
                             :flex-direction  "column"
                             :height          "45%"
                             :margin          0
                             :justify-content (str "flex-" justification)}}])
       vec))

(def above-view
  ((aid/lift-a (partial vector direction-component "end"))
    above
    progress-behavior))

(def below-view
  ((aid/lift-a (partial vector direction-component "start"))
    below
    progress-behavior))

(defn review-component
  [above* current below* progress]
  [:div
   {:on-double-click #(edit)
    :style           {:height "100%"
                      :width  "100%"}}
   above*
   [:div
    {:style (->> current
                 progress
                 get-status-style
                 (merge {:height          "10%"
                         :display         "flex"
                         :flex-direction  "column"
                         :justify-content "center"}))}
    current]
   below*])

(def review-view
  ((aid/lift-a (partial vector review-component))
    above-view
    current-behavior
    below-view
    progress-behavior))

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

(loop-event {source-current  sink-current
             source-progress sink-progress})

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
  (try (-> window
           (.loadURL (-> config
                         :path
                         (str word)))
           (.then #(-> config
                       :selectors
                       get-css
                       window.webContents.insertCSS))
           ;the catch clause works around the following error.
           ;Uncaught (in promise) Error: ERR_ABORTED (-3) loading
           (.catch aid/nop))
       ;the catch clause works around the following error.
       ;Uncaught Error: Could not call remote function 'loadURL'. Check that the function signature is correct. Underlying error: Object has been destroyed
       (catch js/Error _)))

(def focus-window
  #(.focus (electron.remote.getCurrentWindow)))

(frp/run (juxt (comp (partial (aid/flip run!) contents)
                     render-content)
               (fn [_]
                 (focus-window)))
         sink-current)

(frp/run (partial assoc! local-storage :state) state)

(frp/run (partial clear! local-storage) clear)

(defn bind
  [s e]
  (js/Mousetrap.bind s #(e)))

(def bind-keymap
  (partial run! (partial apply bind)))

(def keymap
  {"alt+a"  all-filter
   "alt+r"  right-filter
   "alt+d"  delete-filter
   "alt+w"  wrong-filter
   "ctrl+r" redo
   "j"      down
   "k"      up
   "r"      right
   "u"      undo
   "w"      wrong})

(bind-keymap keymap)

(focus-window)

(frp/activate)
