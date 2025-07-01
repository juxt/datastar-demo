(ns user
  (:require
   clojure.pprint
   [clojure.string :as str]
   [s-exp.hirundo :as hirundo]
   [starfederation.datastar.clojure.protocols :as p]
   [starfederation.datastar.clojure.api :as d*])
  (:import
   (io.helidon.http.sse SseEvent)
   (io.helidon.webserver.sse SseSink)))

(deftype HelidonSseGenerator [sse-sink]
  p/SSEGenerator
  (send-event! [_ event-type data-lines opts]
    (.emit sse-sink
           (.. (SseEvent/builder)
               (name event-type)
               (data (str/join " " data-lines))
               build)))
  (get-lock [_] nil)
  (close-sse! [_] (.close sse-sink))
  (sse-gen? [_] true))

(defn sse-generator [{:keys [:s-exp.hirundo.http.request/server-response] :as req}]
  (let [sse-sink (.sink server-response SseSink/TYPE)]
    (->HelidonSseGenerator sse-sink)))

(defn handler [{:keys [request-method] :as req}]
  (case request-method
    :get
    {:status 200
     :headers {"content-type" "text/html;charset=utf-8"}
     :body (slurp "index.html")}

    :post
    (let [sse-gen (sse-generator req)]
      (doseq [i (reverse (range 10))]
        (d*/merge-fragments! sse-gen [(format "<div id='foo'>Starting in %d seconds !!!</div>" i)])
        (Thread/sleep 1000))
      (p/close-sse! sse-gen))))

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
