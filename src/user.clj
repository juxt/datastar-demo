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

(def game-state (atom {:status "Awaiting players"
                       :players []
                       :current-player nil
                       :turn "X"
                       :frame 0
                       :board (vec (repeat 9 nil))}))

(def subscribers (atom #{}))
(defn subscribe []
  (let [queue (java.util.concurrent.LinkedBlockingQueue.)]
    (swap! subscribers conj queue)
    queue))

(defn publish [msg]
  (doseq [sub @subscribers]
    (.put sub msg)))

(defn handler [{:keys [request-method] :as req}]
  (case request-method
    :get
    {:status 200
     :headers {"content-type" "text/html;charset=utf-8"}
     :body (slurp "index.html")}

    :post
    (let [sse-gen (sse-generator req)
          subscription (subscribe)]
      (log/infof "POST")
      (while true
        (let [state (deref game-state)] ; freeze the game state
          (d*/merge-fragments!
           sse-gen
           [(str
             (h/html [:main#main
                      (case (:status state)
                        "Start game"
                        [:h1 (get-in state [:players 0]) " versus " (get-in state [:players 1]) "!"]
                        [:h1#title "Tic Tac Toe Multiplayer with Datastar! ⭐️"])

                      [:h2 "Status: " (get state :status)]

                      [:h2 "Current Player: " (get state :current-player)]

                      [:h3 "Players"]
                      [:ol
                       (for [player (:players state)]
                         [:li player])]

                      [:div.grid {:data-signals "{cell: '', action: ''}"}
                       (for [cell (map inc (range 9))]
                         [:button {:id cell
                                   :data-on-click (str "$cell=" cell ";@setAll('action', 'move');@put(window.location.pathname)")}
                          (get (:board state) cell)])]

                      (case (:status state)
                        "Awaiting players"
                        [:div {:data-signals "{player: '', action: '', cell: ''}"}
                         [:label "Player, enter your name: "]
                         [:input {:type "text" :data-bind "player"}]
                         [:button {:data-on-click "@setAll('action','join');@put(window.location.pathname)"} "Join"]]
                        [:div "Error: Unknown State: " (:status state)])
                      [:h3 "Game frame:" (:frame (swap! game-state update :frame inc))]

                      [:h2 "Debug"]
                      [:ul
                       (for [sub @subscribers]
                         [:li (pr-str sub)])]]))]))

        (.take subscription))
      (p/close-sse! sse-gen))

    :put
    (let [body (:body req)
          json (json/read-value body)
          cell (get json "cell")
          action (get json "action")]

      ;; We must now update the game state and status
      (clojure.pprint/pprint json)

      (case action
        "move"
        (do
          (let [current-player (:current-player @game-state)
                board (:board @game-state)
                turn (:turn @game-state)]
            (when  (nil? (get board cell))
              (swap! game-state update :board assoc cell turn)
              (if (= turn "X")
                (swap! game-state assoc :turn "O")
                (swap! game-state assoc :turn "X"))))
          (publish :ok))
        "join"
        (do (case (:status @game-state)
              "Awaiting players"
              (let [player (get json "player")]
                (swap! game-state assoc :current-player player)
                (swap! game-state update :players conj player)
                (when (= (count (:players @game-state)) 2)
                  (swap! game-state assoc :status "Start game"))))
            ;; Trigger the game to re-render
            (publish :ok)))

      {:status 200})))

(defonce state (atom {}))

(defn server []
  (hirundo/start!
   {;; Must be using the graphcentric hirundo fork
    :http-handler #'handler
    :port 9090}))

(defn start []
  (swap! state update :server (fn [existing] (if existing existing (server)))))

(defn stop []
  (swap! state update :server (fn [s] (when s (.stop s)) nil)))
