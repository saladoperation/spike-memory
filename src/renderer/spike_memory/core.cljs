(ns spike-memory.core
  (:require [aid.core :as aid]
            [cats.core :as m]
            [frp.core :as frp]
            [reagent.core :as r]
            [clojure.string :as str]))

(frp/defe cancel save edit typing)

(def review
  (->> edit
       (aid/<$ false)
       (m/<> (aid/<$ true (m/<> cancel save)))
       (frp/stepper true)))

(def words
  (->> typing
       (frp/stepper "")
       (frp/snapshot save)
       (m/<$> (comp str/split-lines
                    last))))

(def edit-component
  [:form
   [:textarea {:on-change #(-> %
                               .-target.value
                               typing)}]
   [:button {:on-click #(cancel)} "Cancel"]
   [:button {:on-click #(save)} "Save"]])

(def review-view
  [:div {:on-double-click #(edit)
         :style           {:height   "100%"
                           :overflow "hidden"
                           :width    "150px"}}])

(def app-component
  #(if %
     review-view
     edit-component))

(def app-view
  (m/<$> app-component review))

(frp/run (partial (aid/flip r/render) (js/document.getElementById "app"))
         app-view)

(frp/activate)
