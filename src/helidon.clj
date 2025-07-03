(ns helidon
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [starfederation.datastar.clojure.protocols :as p])
  (:import
   (io.helidon.http.sse SseEvent)
   (io.helidon.webserver.sse SseSink)))

(deftype HelidonSseGenerator [sse-sink]
  p/SSEGenerator
  (send-event! [_ event-type data-lines opts]
    (try
      (.emit sse-sink
             (.. (SseEvent/builder)
                 (name event-type)
                 (data (str/join " " data-lines))
                 build))
      (catch Exception e
        (log/error "Error on emit: ")
        )))
  (get-lock [_] nil)
  (close-sse! [_] (.close sse-sink))
  (sse-gen? [_] true))

(defn sse-generator [{:keys [:s-exp.hirundo.http.request/server-response] :as req}]
  (let [sse-sink (.sink server-response SseSink/TYPE)]
    (->HelidonSseGenerator sse-sink)))
