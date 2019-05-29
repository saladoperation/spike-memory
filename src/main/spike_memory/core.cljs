(ns spike-memory.core
  (:require [aid.core :as aid]
            [cljs-node-io.fs :as fs]
            [cuerdas.core :as cuerdas]
            [frp.core :as frp]
            [goog.object :as object]
            [oops.core :refer [oget+]]
            [spike-memory.helpers :as helpers]))

(def path
  (js/require "path"))

(def window-state-keeper
  (js/require "electron-window-state"))

(def electron
  (js/require "electron"))

(def app
  (.-app electron))

(def memoized-keyword
  (memoize cuerdas/keyword))

(defn convert-keys
  [ks x]
  ;Doing memoization is visibly faster.
  (->> ks
       (mapcat (juxt memoized-keyword
                     #(case (-> x
                                (oget+ %)
                                goog/typeOf)
                        "function" (partial js-invoke x %)
                        (oget+ x %))))
       (apply hash-map)))

;TODO move this function to aid
(def convert-object
  (aid/build convert-keys
             object/getKeys
             identity))

(frp/defe file-path)

(.on app
     "ready"
     (fn [_]
       (let [window-state (window-state-keeper. {})
             window (-> window-state
                        convert-object
                        (merge {:webPreferences {:nodeIntegration true}})
                        clj->js
                        electron.BrowserWindow.)]
         (doto
           window
           (.webContents.on "did-finish-load"
                            (fn []
                              (frp/run #(.webContents.send window
                                                           helpers/channel
                                                           %)
                                       file-path)
                              (frp/activate)))
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

(.on app "will-finish-launching" #(.on app "open-file" (comp file-path
                                                             last
                                                             vector)))
