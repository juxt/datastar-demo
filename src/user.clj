(ns user
  (:require
   clojure.pprint
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [jsonista.core :as json]
   [s-exp.hirundo :as hirundo]
   [starfederation.datastar.clojure.protocols :as p]
   [starfederation.datastar.clojure.api :as d*])
  (:import
   (java.util.concurrent LinkedBlockingQueue)
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

(def game-state (atom {:status "Awaiting players"}))

(def message-queue (LinkedBlockingQueue/new))

(def counter (atom 0))

(defn handler [{:keys [request-method] :as req}]
  (case request-method
    :get
    {:status 200
     :headers {"content-type" "text/html;charset=utf-8"}
     :body (slurp "index.html")}

    :post
    (let [sse-gen (sse-generator req)]
      (log/infof "POST")
      (while true
        (d*/merge-fragments!
         sse-gen
         [(str
           (h/html [:main#main
                    [:h1#title "Tic Tac Toe Multiplayer with Datastar! ⭐️"]
                    [:h2 "Status: " (get @game-state :status)]

                    [:div.grid
                     (for [cell (map inc (range 9))]
                       [:button {:id cell}])]
                    (case (:status @game-state)
                      "Awaiting players"
                      [:div {:data-signals "{player: '', action: ''}"}
                       [:label "Player, enter your name: "]
                       [:input {:type "text" :data-bind "player"}]
                       [:button {:data-on-click "@setAll('action','join');@put(window.location.pathname)"} "Join"]])
                    [:h3 "Game frame:" (swap! counter inc)]]))])
        (.take message-queue))
      (p/close-sse! sse-gen))

    :put
    (let [body (:body req)
          {:strs [symbol message] :as json} (json/read-value body)]
      (println json)
      (println "Message was" message)
      (.put message-queue :ok)
      {:status 200})))

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
