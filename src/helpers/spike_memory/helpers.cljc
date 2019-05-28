(ns spike-memory.helpers
  (:require [clojure.string :as str]))

(def get-path
  (comp (partial str/join "/")
        vector))

(def resources
  "resources")

(def public
  "public")
