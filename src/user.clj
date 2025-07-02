(ns user
  (:require
   clojure.pprint
   [clojure.tools.logging :as log]
   [clojure.string :as str] 
   [hiccup2.core :as h]
   [ring.middleware.session :refer [wrap-session]]
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
                       :turn "X"
                       :turnSession nil
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

(defn player-name-by-id [players session-id]
  (some (fn [{:keys [id name]}] (when (= id session-id) name)) players))

(defn handler [{:keys [request-method session] :as req}]
  (case request-method
    :get
    (cond-> {:status 200
             :headers {"content-type" "text/html;charset=utf-8"}
             :body (slurp "index.html")}
      (nil? (:id session))
      (assoc :session {:id (random-uuid)}))

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
                        [:h1
                         (get-in state [:players 0 :name]) " versus " (get-in state [:players 1 :name]) "!"]
                        [:h1#title "Tic Tac Toe Multiplayer with Datastar! ⭐️"])

                      [:h2 (get state :status)]

                      (if (or (= (:status state) "")
                             (let [status (:status @game-state)]
                               (and (string? status) (str/includes? status "wins"))))
                        [:div
                         [:h2 "You are " (player-name-by-id (:players state) (:id session))]
                         [:h2 "Current turn: " (player-name-by-id (:players state) (:turnSession state))]
                         ])

                      [:h3 "Players"]
                      [:ol
                       (for [{:keys [id name]} (:players state)]
                         [:li name])]

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
                        [:div])
                      
                      [:h3 "Game frame:" (:frame (swap! game-state update :frame inc))]

                      [:h2 "Debug"]
                      [:p "session is" (:id session)]
                      [:ul
                       (for [sub @subscribers]
                         [:li (pr-str sub)])]]))]))

        (.take subscription))
      (p/close-sse! sse-gen))

    :put
    (let [body (:body req)
          json (json/read-value body)
          cell (get json "cell")
          action (get json "action")
          session-id (:id session)]

      ;; We must now update the game state and status
      (clojure.pprint/pprint json) 
      (println "PUT from session" (:id session))

      (case action
        "move"
        (do
          (let [board (:board @game-state)
                turn (:turn @game-state)]
            (when (and (nil? (get board cell))
                       (= (:turnSession @game-state) session-id)
                       (let [status (:status @game-state)]
                         (not (and (string? status) (str/includes? status "wins")))))

              (swap! game-state update :board assoc cell turn)

              ; win check
              
              (let [updated-board (:board @game-state)]
                (let [winning-combinations [[1 2 3] [4 5 6] [7 8 9] 
                                            [1 4 7] [2 5 8] [3 6 9] 
                                            [1 5 9] [3 5 7]]]
                  (doseq [comb winning-combinations]
                    (when (let [a (get updated-board (first comb))
                                b (get updated-board (second comb))
                                c (get updated-board (last comb))]
                            (or (and (= a "X") (= b "X") (= c "X"))
                                (and (= a "O") (= b "O") (= c "O"))))
                      (if (= turn "X")
                        (swap! game-state assoc :status (str "Player " (get-in @game-state [:players 0 :name]) " wins!"))
                        (swap! game-state assoc :status (str "Player " (get-in @game-state [:players 1 :name]) " wins!")))))))

              (if (= turn "X")
                (swap! game-state assoc :turn "O")
                (swap! game-state assoc :turn "X"))

              (swap! game-state assoc :turnSession
                     (if (= turn "X")
                       (get-in @game-state [:players 1 :id])
                       (get-in @game-state [:players 0 :id])))))
              (publish :ok))
        "join"
        (do (case (:status @game-state)
              "Awaiting players"
              (let [player (get json "player")]
                (println player)
                (swap! game-state update :players conj {:id session-id :name player})
                (when (= (count (:players @game-state)) 1)
                  (swap! game-state assoc :turnSession session-id))
                (when (= (count (:players @game-state)) 2)
                  (swap! game-state assoc :status "Start game"))))
            ;; Trigger the game to re-render
            (publish :ok)
            (clojure.pprint/pprint @game-state)))

      {:status 200})))

(defonce state (atom {}))

(defn server []
  (hirundo/start!
   {;; Must be using the graphcentric hirundo fork
    :http-handler (wrap-session #'handler)
    :port 9090}))

(defn start []
  (swap! state update :server (fn [existing] (if existing existing (server)))))

(defn stop []
  (swap! state update :server (fn [s] (when s (.stop s)) nil)))
