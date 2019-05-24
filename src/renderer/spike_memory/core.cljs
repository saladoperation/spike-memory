(ns spike-memory.core
  (:require [clojure.string :as str]
            [aid.core :as aid]
            [cats.core :as m]
            cljsjs.mousetrap
            [frp.core :as frp]
            [linked.core :as linked]
            [reagent.core :as r]))

(frp/defe cancel save edit typing all correct wrong deleted)

(def review
  (->> edit
       (aid/<$ false)
       (m/<> (aid/<$ true (m/<> cancel save)))
       (frp/stepper true)))

(def words
  (->> typing
       (frp/stepper "")
       (frp/snapshot save)
       (m/<$> (comp linked/set
                    str/split-lines
                    last))))

(def progress
  ;TODO implement this event
  (m/<$> (comp (partial apply linked/map)
               (partial (aid/flip interleave) (repeat :right)))
         words))

(def current
  ;TODO implement this event
  (m/<$> first words))

(def filtered-words
  (->> progress
       (frp/stepper {})
       (frp/snapshot (->> (m/<> (aid/<$ :correct correct)
                                (aid/<$ :deleted deleted)
                                (aid/<$ :wrong wrong))
                          (m/<$> #(partial filter (comp %
                                                        val)))
                          (m/<> (aid/<$ identity all))
                          (m/<$> #(comp (aid/if-then-else empty?
                                                          (constantly [])
                                                          keys)
                                        %))))
       (m/<$> (partial apply aid/funcall))
       (m/<> words)))

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

(defn bind
  [s e]
  (js/Mousetrap.bind s #(e)))

(def bind-keymap
  (partial run! (partial apply bind)))

(def keymap
  {"alt+a" all
   "alt+c" correct
   "alt+d" deleted
   "alt+w" wrong})

(bind-keymap keymap)

(frp/activate)
