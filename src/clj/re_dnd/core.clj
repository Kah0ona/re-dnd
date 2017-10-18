(ns re-dnd.core
  (:require [taoensso.timbre :as timbre]))

(def log-config
  {:level          (keyword (env :log-level))
   :ns-whitelist   [] #_ []
   :ns-blacklist   [] #_ ["taoensso.*"]
   :middleware     []
   :timestamp-opts timbre/default-timestamp-opts
   :output-fn      timbre/default-output-fn
   :appenders      {:debug (appenders/println-appender
                            {:level  :debug
                             :stream :auto})}})


(timbre/set-config! log-config)
