(ns user
  (:require [s-exp.hirundo :as hirundo]))

(defn handler [req]
  {:status 201
   :headers {"content-type" "text/event-stream"}
   :body "Goodbye World!!!!\r\n"})

(defonce state (atom {}))

(defn server []
  (hirundo/start!
   { ;; Must be using the graphcentric hirundo fork
    :http-handler #'handler
    :port 9090}))

(defn start []
  (swap! state update :server (fn [existing] (if existing existing (server)))))

(defn stop []
  (swap! state update :server (fn [s] (when s (.stop s)) nil)))
