(ns spike-memory.core
  (:require [cljs-node-io.fs :as fs]))

(def builder
  (js/require "electron-builder"))

(def output
  (if (-> js/process.argv
          last
          fs/file?)
    "dist"
    (last js/process.argv)))

(def target
  ["zip"])

(def config
  {:config  {:directories      {:output output}
             :fileAssociations {:ext "spike-memory"}}
   :linux   target
   :mac     target
   :publish "always"})

(-> config
    clj->js
    builder.build
    (.then #(js/process.exit))
    (.catch (fn [e]
              (println e)
              (js/process.exit 1))))
