(ns clj-drone.core
  (:import (java.net DatagramPacket DatagramSocket InetAddress))
  (:require  [ clj-logging-config.log4j :as log-config]
             [ clojure.tools.logging :as log]
             [clj-drone.at :refer :all]
             [clj-drone.navdata :refer :all]
             [clj-drone.goals :refer :all]))


(def default-drone-ip "192.168.1.1")
(def default-at-port 5556)
(def default-navdata-port 5554)
(def drones (atom {}))

(def socket-timeout (atom 9000))
(declare mdrone)
(declare drone-init-navdata)


(defn init-logger []
  (log-config/set-logger! :level :debug
                          :out "logs/drone.log"))
(init-logger)


(defn drone-initialize
  ([] (drone-initialize :default default-drone-ip default-at-port default-navdata-port))
  ([name ip at-port navdata-port]
     (swap! drones assoc name {:host (InetAddress/getByName ip)
                               :at-port at-port
                               :navdata-port navdata-port
                               :counter (atom 0)
                               :at-socket (DatagramSocket. )
                               :navdata-socket (DatagramSocket. )
                               :nav-agent (agent {})})
     (mdrone name :flat-trim)))

(defn drone-ip [drone-name]
   (.getHostName (:host (drone-name @drones))))

(defn send-command [name data]
  (let [host (:host (name @drones))
        at-port (:at-port (name @drones))
        at-socket (:at-socket (name @drones))]
   (.send at-socket
          (new DatagramPacket (.getBytes data) (.length data) host at-port))))

(defn mdrone [name command-key & [w x y z]]
  (let [counter (:counter (name @drones))
        seq-num (swap! counter inc)
        data (build-command command-key seq-num w x y z)]
    (send-command name data)))

(defn drone [command-key & [w x y z]]
  (mdrone :default command-key w x y z))

(defn mdrone-do-for [name seconds command-key & [w x y z]]
  (when (> seconds 0)
    (mdrone name command-key w x y z)
    (Thread/sleep 30)
    (recur name (- seconds 0.03) command-key [w x y z])))

(defn drone-do-for [seconds command-key & [w x y z]]
  (mdrone-do-for :default seconds command-key w x y z))

(defn drone-stop-navdata []
  (reset! stop-navstream true))

(defn communication-check []
  (when (= :problem (@nav-data :com-watchdog))
    (log/info "Watchdog Reset")
    (drone :reset-watchdog)))

(defn stream-navdata [_ socket packet]
  (do
    (receive-navdata socket packet)
    (parse-navdata (get-navdata-bytes packet))
    (log/info (str "navdata: "(log-flight-data)))
    (communication-check)
    (eval-current-goals @nav-data)
    (log/info (log-goal-info))
    (if @stop-navstream
      (log/info "navstream-ended")
      (recur nil socket packet))))

(defn start-streaming-navdata [navdata-socket host port nav-agent]
  (let [receive-data (byte-array 2048)
        nav-datagram-receive-packet (new-datagram-packet receive-data host port)]
    (do
      (log/info "Starting navdata stream")
      (swap! nav-data {})
      (.setSoTimeout navdata-socket @socket-timeout)
      (send nav-agent stream-navdata navdata-socket nav-datagram-receive-packet)
      (log/info "Creating navdata stream" ))))


(defn init-streaming-navdata [navdata-socket host port]
  (let [send-data (byte-array (map byte [1 0 0 0]))
        nav-datagram-send-packet (new-datagram-packet send-data host port)]
    (do
      (reset-navstream)
      (.setSoTimeout navdata-socket @socket-timeout)
      (send-navdata navdata-socket nav-datagram-send-packet))))

(defn navdata-error-handler [ag ex]
  (do
    (println "evil error occured: " ex " and we still have value " @ag)
    (when (= (.getClass ex) java.net.SocketTimeoutException)
      (println "Reststarting nav stream")
      (swap! drones assoc :default (assoc (:default @drones) :navdata-socket (DatagramSocket. )))
      (println "redef navdata-socket")
      (.setSoTimeout (:navdata-socket (:default @drones)) @socket-timeout)
      (println "reset socket timout")
      (def nav-agent (agent {}))
      (swap! drones assoc :default (assoc (:default @drones) :nav-agent (agent {})))
      (println (str "agent now is " (:nav-agent (:default @drones))))
      (when-not  @stop-navstream
        (drone-init-navdata)))))

(defn drone-init-navdata []
  (let [host (:host (:default @drones))
        navdata-port (:navdata-port (:default @drones))
        navdata-socket (:navdata-socket (:default @drones))
        nav-agent (:nav-agent (:default @drones))]
    (do
      (init-logger)
      (log/info "Initializing navdata")
      (log/info nav-agent)
      (reset! nav-data {})
      (set-error-handler! nav-agent navdata-error-handler)
      (init-streaming-navdata navdata-socket host navdata-port)
      (drone :init-navdata)
      (drone :control-ack)
      (init-streaming-navdata navdata-socket host navdata-port)
      (start-streaming-navdata navdata-socket host navdata-port nav-agent))))
