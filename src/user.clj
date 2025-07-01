(ns user
  (:require
   clojure.pprint
   [s-exp.hirundo :as hirundo])
  (:import
   (io.helidon.http.sse SseEvent)
   (io.helidon.webserver.sse SseSink)))

(defn handler [{:keys [:s-exp.hirundo.http.request/server-response] :as req}]
  (let [sse-sink (.sink server-response SseSink/TYPE)]
    (doseq [i (range 10)]
      (.emit sse-sink (SseEvent/create "Hello!"))
      (Thread/sleep 500))
    (.close sse-sink))
  {:status 200
   :headers {"content-type" "text/event-stream"}
   })

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
