(ns user
  (:require [s-exp.hirundo :as hirundo]))

(def state (atom {}))

(defn server []
  (hirundo/start!
   { ;; Must be using the graphcentric hirundo fork
    :http-handler
    (fn [req]
      {:status 200
       :header {"content-type" "text/plain;charset=utf-8"}
       :body "Hello World!\r\n"})
    :port 9090}))

(defn start []
  (swap! state assoc :server (server)))

(defn stop []
  (swap! state update :server (fn [server] (.stop server) nil)))
