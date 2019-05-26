(defproject spike-memory "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.520"]
                 [cljs-node-io "1.1.2"]
                 [frankiesardo/linked "1.3.0"]
                 [frp "0.1.3"]
                 [garden "1.3.9"]
                 ;alandipert.storage-atom  doesn't retain linked/map
                 ;=> (type @(-> (linked/map)
                 ;              atom
                 ;              (local-storage :state)))
                 ;cljs.core/PersistentArrayMap
                 [hodgepodge "0.1.3"]
                 [reagent "0.8.1"]]
  :plugins [[lein-ancient "0.6.15"]
            [lein-cljsbuild "1.1.7"]]
  :source-paths ["src/helpers"]
  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.10"]
                                  [figwheel-sidecar "0.5.18"]]}}
  :cljsbuild {:builds
              {:builder  {:source-paths ["src/builder" "src/helpers"]
                          :compiler     {:output-to "target/main.js"
                                         :main      spike-memory.core
                                         :target    :nodejs}}
               :main     {:source-paths ["src/helpers" "src/main"]
                          :compiler     {:output-to       "resources/main.js"
                                         :optimizations   :simple
                                         :main            spike-memory.core
                                         :closure-defines {goog.DEBUG false}}}
               :renderer {:source-paths ["src/helpers" "src/renderer"]
                          :compiler     {:output-to       "resources/public/js/main.js"
                                         :optimizations   :simple
                                         :main            spike-memory.core
                                         :closure-defines {goog.DEBUG false}}}}})
