(ns spike-memory.core
  (:require [cljs.tools.reader.edn :as edn]
            [cljs.pprint :as pprint]
            [clojure.string :as str]
            [aid.core :as aid]
            [cats.core :as m]
            [cljs-node-io.core :refer [slurp spit]]
            [cljs-node-io.fs :as fs]
            cljsjs.mousetrap
            [com.rpl.specter :as s]
            [frp.core :as frp]
            [garden.core :refer [css]]
            [linked.core :as linked]
            [reagent.core :as r]
            [spike-memory.helpers :as helpers]))

(def electron
  (js/require "electron"))

(def window-state-keeper
  (js/require "electron-window-state"))

(def child-process
  (js/require "child_process"))

(def path
  (js/require "path"))

(def remote
  electron.remote)

(def app
  remote.app)

(def config-path
  (-> "userData"
      app.getPath
      (path.join "config.edn")))

(def default-config
  {:path    (->> (app.getName)
                 (str "new.")
                 (path.join (app.getPath "documents")))
   ;TODO use linked/set
   :windows [{:url       "https://www.oxfordlearnersdictionaries.com/definition/english/"
              :selectors ["div#ad_contentslot_1"
                          "#ox-header"
                          "#header"
                          ".menu_button"
                          "#ad_topslot_a"
                          ".entry-header"
                          ".btn"
                          "#rightcolumn"
                          "span.dictlinks"
                          ".pron-link"
                          ".social-wrap"
                          "#rightcolumn"
                          "#ox-footer"
                          "a.go-to-top"]}
             {:url       "https://duckduckgo.com/?ia=images&iax=images&q="
              :selectors ["#header_wrapper"]}]})

(def default-config-text
  (with-out-str (pprint/pprint default-config)))

(aid/if-else fs/fexists?
             (partial (aid/flip spit) default-config-text)
             config-path)

(def edn-read-string
  (partial edn/read-string
           {:readers {'linked/map (partial into (linked/map))
                      'linked/set (partial into (linked/set))}}))

(def slurp-read
  (comp edn-read-string
        slurp))

(def config
  (slurp-read config-path))

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
          delete
          save
          yank
          say
          undo
          redo
          source-current
          source-progress)

(def file-path
  (frp/event (:path config)))

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

(def file
  (m/<$> (aid/if-then-else fs/fexists?
                           slurp-read
                           (constantly {:progress (linked/map)
                                        :current  ""
                                        :status   :all}))
         file-path))

(def current-behavior
  (->> file
       (m/<$> :current)
       (m/<> source-current)
       (frp/stepper "")))

(def size
  10)

(def sink-progress
  (frp/undoable size
                undo
                redo
                [right wrong delete words file]
                (->> current-behavior
                     (frp/snapshot (m/<> (aid/<$ :right right)
                                         (aid/<$ :wrong wrong)
                                         (aid/<$ :delete delete)))
                     (m/<$> (comp (aid/flip (aid/curry 2 merge))
                                  (partial apply array-map)
                                  reverse))
                     (m/<> (->> words
                                (m/<$> (comp (partial apply linked/map)
                                             (partial (aid/flip interleave)
                                                      (repeat :right))))
                                (m/<> (m/<$> :progress file))
                                (m/<$> constantly)))
                     (frp/accum (linked/map)))))

(def progress-behavior
  (frp/stepper (linked/map) sink-progress))

(def status
  (->> file
       (m/<$> :status)
       (m/<> (aid/<$ :all all-filter)
             (aid/<$ :right right-filter)
             (aid/<$ :delete delete-filter)
             (aid/<$ :wrong wrong-filter))
       (frp/stepper :all)))

(def filter-status
  (->> status
       (m/<$> (aid/if-then-else (partial = :all)
                                (constantly identity)
                                #(partial filter (comp (partial = %)
                                                       val))))))

(defn get-part
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
  (get-part take-while identity))

(def below
  (get-part drop-while rest))

(def copy
  (->> (get-part (comp last
                       vector)
                 identity)
       (m/<$> (partial str/join "\n"))
       (frp/snapshot yank)
       (m/<$> last)))

(def command
  (->> current-behavior
       (frp/snapshot say)
       (m/<$> (comp (partial str "say ")
                    last))))

(def modification
  (m/<$> rest (frp/snapshot source-progress
                            (frp/stepper (:path config) file-path)
                            ((aid/lift-a (comp (partial zipmap [:progress
                                                                :current
                                                                :status])
                                               vector))
                              progress-behavior
                              current-behavior
                              status))))

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
  [:div
   [:textarea {:on-change #(-> %
                               .-target.value
                               typing)}]
   [:button {:on-click #(cancel)} "Cancel"]
   [:button {:on-click #(save)} "Save"]])

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

(def loop-event
  (partial run! (partial apply frp/run)))

(def get-window
  #(let [window-state (window-state-keeper. #js{:file (str "window-state/"
                                                           %
                                                           ".json")})]
     (doto
       (remote.BrowserWindow. window-state)
       window-state.manage)))

(def get-css
  (comp css
        (partial s/setval*
                 s/AFTER-ELEM
                 {:display "none !important"})))

(def focus-window
  #(.focus (remote.getCurrentWindow)))

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
   ;TODO save state before quitting
   "ctrl+w" electron.remote.app.quit
   "j"      down
   "k"      up
   "r"      right
   "s"      say
   "u"      undo
   "w"      wrong
   "d d"    delete
   "y y"    yank})

(def menu-item
  (-> {:label   "Focus"
       :submenu [{:accelerator "Esc"
                  :label       ""
                  :click       focus-window}]}
      clj->js
      remote.MenuItem.))

(def bind-escape
  #(doto
     (remote.Menu.getApplicationMenu)
     (.append menu-item)
     remote.Menu.setApplicationMenu))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app"))
         app-view)

(loop-event {source-current  sink-current
             source-progress sink-progress})

(defonce contents
  (->> config
       :windows
       (map-indexed (fn [k v]
                      [(get-window k) v]))
       (into (linked/map))))

(aid/defcurried render-content
  [word [window config]]
  (try (-> window
           (.loadURL (-> config
                         :url
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

(frp/run (juxt (comp (partial (aid/flip run!) contents)
                     render-content)
               (fn [_]
                 (focus-window)))
         sink-current)

(frp/run (partial apply spit) modification)

(frp/run electron.clipboard.writeText copy)

(frp/run child-process.exec command)

(.ipcRenderer.on electron helpers/channel (comp file-path
                                                last
                                                vector))

(bind-keymap keymap)

(bind-escape)

(focus-window)

(frp/activate)
