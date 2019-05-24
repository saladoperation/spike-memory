(ns spike-memory.core
  (:require [aid.core :as aid]
            [cljs-node-io.fs :as fs]
            [spike-memory.helpers :as helpers]))

(def path
  (js/require "path"))

(def app
  (.-app helpers/electron))

(.on app
     "ready"
     (fn [_]
         (.loadURL (helpers/electron.BrowserWindow.)
                   (->> "index.html"
                        (helpers/get-path helpers/public)
                        (path.join (aid/if-else (comp (partial =
                                                               helpers/resources)
                                                      fs/basename)
                                                (comp fs/dirname
                                                      fs/dirname)
                                                js/__dirname))
                        (str "file://")))))
