(defproject re-dnd "0.1.11"

  :description "A configurable drag/drop widget + API for re-frame apps"
  :url "https://github.com/Kah0ona/re-dnd.git"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.439"]
                 [reagent "0.8.1"]
                 [re-frame "0.10.6"]
                 [com.taoensso/timbre "4.10.0"]
                 [re-frame-utils "0.1.0"]
                 [fipp "0.6.10"]]


  :plugins [[lein-cljsbuild "1.1.5"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj" "src/cljs"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target" "test/js"]

  :figwheel {:css-dirs ["resources/public/css"]}

  :repl-options {:timeout          220000
                 :nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

   ;;figwheel-main, if you want to run it from the commandline
  :aliases {"fig"       ["trampoline" "run" "-m" "figwheel.main"]
            "build-dev" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]}

;;  :jar-exclusions   [#"(?:^|\/)re_dnd_demo\/" #"(?:^|\/)demo\/" #"(?:^|\/)compiled.*\/" #"html$"]

  :profiles
  {:dev
   {:dependencies [
                   [cider/piggieback "0.4.0"]
                   [binaryage/devtools "0.9.7"]
                   [com.bhauman/rebel-readline-cljs "0.1.4"]
                   [com.bhauman/figwheel-main "0.2.1-SNAPSHOT"]
                   [org.clojure/tools.nrepl "0.2.13"]]

    :plugins [[lein-doo "0.1.11"]
              [lein-pdo "0.1.1"]]}}

  :cljsbuild
  {:builds
   [{:id           "min"
     :source-paths ["src/cljs"]
     :compiler     {:main            re-dnd.core
                    :output-to       "resources/public/js/compiled/app.js"
                    :optimizations   :advanced
                    :closure-defines {goog.DEBUG false}
                    :pseudo-names    false
                    :pretty-print    false}}

    {:id           "demo"
     :source-paths ["src/cljs"]
     :compiler     {:main            re-dnd-demo.core
                    :output-to       "resources/public/js/compiled/app-demo.js"
                    :output-dir      "resources/public/js/compiled/demo/out"
                    :optimizations   :advanced
                    :closure-defines {goog.DEBUG false}
                    :pseudo-names    false
                    :pretty-print    false}}

    {:id           "test"
     :source-paths ["src/cljs" "test/cljs"]
     :compiler     {:main          re-dnd.runner
                    :output-to     "resources/public/js/compiled/test.js"
                    :output-dir    "resources/public/js/compiled/test/out"
                    :optimizations :none}}]})
