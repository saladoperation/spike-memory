(ns spike-memory.core
  (:require [aid.core :as aid]
            [cljs-node-io.fs :as fs]
            [spike-memory.helpers :as helpers]))

(def path
  (js/require "path"))

(def window-state-keeper
  (js/require "electron-window-state"))

(def electron
  (js/require "electron"))

(def app
  (.-app electron))

(.on app
     "ready"
     (fn [_]
       (let [window-state (window-state-keeper. {})
             menu (electron.Menu.getApplicationMenu)
             window (-> {:height         window-state.height
                         :webPreferences {:nodeIntegration true}
                         :width          window-state.width
                         :x              window-state.x
                         :y              window-state.y}
                        clj->js
                        electron.BrowserWindow.)]
         (doto
           window
           (.loadURL
             (->> "index.html"
                  (helpers/get-path helpers/public)
                  (path.join (aid/if-else (comp (partial =
                                                         helpers/resources)
                                                fs/basename)
                                          (comp fs/dirname
                                                fs/dirname)
                                          js/__dirname))
                  (str "file://")))
           window-state.manage)
         (doto
           menu
           (.append (-> {:label   "Focus"
                         :submenu [{:accelerator "Esc"
                                    :label       ""
                                    :click       #(.focus window)}]}
                        clj->js
                        electron.MenuItem.))
           electron.Menu.setApplicationMenu))))
