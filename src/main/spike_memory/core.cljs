(ns spike-memory.core
  (:require [aid.core :as aid]
            [cljs-node-io.fs :as fs]
            [spike-memory.helpers :as helpers]))

(def path
  (js/require "path"))

(def window-state-keeper
  (js/require "electron-window-state"))

(def app
  (.-app helpers/electron))

(.on app
     "ready"
     (fn [_]
         (let [window-state (window-state-keeper. {})]
              (doto
                (helpers/electron.BrowserWindow. window-state)
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
                window-state.manage))))
