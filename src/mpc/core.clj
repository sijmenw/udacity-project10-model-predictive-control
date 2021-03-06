(ns mpc.core
  (:require [chord.http-kit :refer [with-channel wrap-websocket-handler]]
            [org.httpkit.server :refer [run-server]]
            [clojure.core.async :refer [<! >! go-loop chan dropping-buffer]]
            [clojure.data.json :as json]
            [clojure.string :refer [index-of last-index-of]]
            [figurer.core :as figurer]
            [incanter.distributions :refer [uniform-distribution]]
            [frenet.core :as frenet])
  (:gen-class))

(def steering-pid-parameters
     {:proportional-factor 0.12
      :derivative-factor 1.8
      :integral-factor 0.005})

;Set target car speed
(def max-speed 70)

(defn initial-pid
  "Set PID errors using only the first measurement."
  [measured-error]
  {:proportional-error measured-error
   :derivative-error 0.0
   :integral-error 0.0})

(defn pid-actuation
  "Use PID to select actuation (such as steering angle)."
  [{:keys [proportional-error derivative-error integral-error] :as pid}
   {:keys [proportional-factor derivative-factor integral-factor] :as pid-parameters}]
  (- (+ (* proportional-factor proportional-error)
        (* derivative-factor derivative-error)
        (* integral-factor integral-error))))

(defn update-pid
  "Use new error measurement to update PID errors."
  [{:keys [proportional-error derivative-error integral-error] :as pid}
   measured-error time-passed]
  {:proportional-error measured-error
   :derivative-error (/ (- measured-error proportional-error) time-passed)
   :integral-error (+ integral-error (* measured-error time-passed))})

(defn format-actuation
  "Format actuation (:steering-angle and :throttle) for transmission to simulator."
  [{:keys [steering-angle throttle waypoints plan] :as actuation}]
  (let [[way-x way-y] (apply mapv vector waypoints)
        [plan-x plan-y] (apply mapv vector plan)]
    (str "42"
      (json/write-str
        ["steer"
         {"steering_angle" steering-angle
          "throttle" throttle
          "next_x" way-x
          "next_y" way-y
          "mpc_x" plan-x
          "mpc_y" plan-y}]))))

(defn parse-message
  "Parse message from Udacity's SDC term 2 simulator for the PID project."
  [msg]
  (if (and msg
           (> (.length msg) 2)
           (= (subs msg 0 2) "42"))
    (let [json-start (index-of msg "[")
          json-end (last-index-of msg "]")
          json-str (subs msg json-start (inc json-end))
          json-msg (json/read-str json-str)]
      (if (= (get json-msg 0) "telemetry")
        (let [data (get json-msg 1)]
          (if data
            {:type :telemetry
             :ptsx (get data "ptsx")
             :ptsy (get data "ptsy")
             :x (get data "x")
             :y (get data "y")
             :speed (get data "speed")
             :psi (get data "psi")
             :psi-unity (get data "psi_unity")
             :steering-angle (get data "steering_angle")
             :throttle (get data "throttle")}
            {:type :manual}))
        json-msg))
    nil))

(defn convert-point-to-vehicle-frame
  "Convert a point from absolute coordinates to vehicle reference frame"
  [absxy carxy carpsi]
  (let [distance (Math/sqrt (+ (Math/pow (- (absxy 0) (carxy 0)) 2)
                               (Math/pow (- (absxy 1) (carxy 1)) 2)))
        direction-abs (Math/atan2 (- (absxy 1) (carxy 1)) (- (absxy 0) (carxy 0)))
        direction-rel (- direction-abs carpsi)
        relx (* distance (Math/cos direction-rel))
        rely (* distance (Math/sin direction-rel))]
    [relx rely]))

(defn convert-points-to-vehicle-frame
  "Convert list of x and list of y from absolute coordinates to vehicle reference frame"
  [absx-list absy-list carxy carpsi]
  (mapv #(convert-point-to-vehicle-frame [%1 %2] carxy carpsi) absx-list absy-list))

(defn constrain
  "Return value if between min-value and max-value.
   If below min-value return min-value. If above
   max-value return max-value."
  [value min-value max-value]
  (cond
    (and min-value (< value min-value)) min-value
    (and max-value (> value max-value)) max-value
    :else value))

; State variable definitions
;
; x,y : position coordinates
; psi : direction of car in radians
; v   : speed of the car
; vx  : speed in x direction (negative if car moves in -x direction)
; vy  : speed in y direction (negative if car moves in -y direction)
; s   : how far car has moved along the road (positive if car has moved forward)
; d   : how far car is to the right of center of the road
;       (0.0 is center, -3.0 is left side, 3.0 is right side)
; vs  : how fast car is moving along the road
; vd  : how fast car is moving to the right
;       (negative is moving to the left, positive is moving to the right)

; Actuation variable definitions
;
; steering : 0.0 is forward, -1.0 is hard left turn, 1.0 is hard right turn
; throttle : 1.0 is flooring the gas pedal, -1.0 is flooring the brake, 0.0 is coasting

(defn pd-steering-estimate
  "Given a state, what steering angle does
   a PD controller recommend?"
  [state]
  (let [[x y psi v vx vy s d vs vd] state]
    (constrain
      (pid-actuation
        {:proportional-error d
         :derivative-error (/ vd
                              (Math/sqrt
                                (+ (* vd vd)
                                   (* vs vs)
                                   0.1)))
         :integral-error 0.0}
        steering-pid-parameters)
      -1.0 1.0)))

(defn policy
  "Given current state, determine next actuation.
   Each element of the result vector is a probability
   distribution to represent the uncertainty in which
   actuation would be the best."
  [state]
  (let [[x y psi v vx vy s d vs vd] state
        steering-best-guess (pd-steering-estimate state) ; Use pd-steering-estimate for better guess.
        steering-uncertainty 0.2 ; Uncertainty between 0.01 and 1.0.
        throttle (if (< v max-speed) 0.95 0.05)]
    [(uniform-distribution
       (- steering-best-guess steering-uncertainty)
       (+ steering-best-guess steering-uncertainty))
     (uniform-distribution (- throttle 0.05) (+ throttle 0.05))]))

(defn predict
  "Given current state and actuation, determine how
   the state will change."
  [state actuation coord dt]
  (let [[x0 y0 psi0 v0 vx0 vy0 s0 d0 vs0 vd0] state
        [steering throttle] actuation
        Lf 2.67
        steer_radians (* 25 steering (/ Math/PI 180))
        ; Physics
        x (+ x0 (* vx0 dt)) ; Calculate new value of x based on x0
        y (+ y0 (* vy0 dt)) ; Calculate new value of y based on y0
        psi (- psi0 (* v0 dt (/ steer_radians Lf)))
        v (+ v0 (* throttle dt)) ; Calculate new value of v based on v0
        ; Derived parts of state
        vx (* v (Math/cos psi))
        vy (* v (Math/sin psi))
        [s d vs vd] (frenet/xyv->sdv coord x y vx vy)]
    [x y psi v vx vy s d vs vd]))

(defn value
  "Measure of how 'good' a state is. A plan will
   be chosen that maximizes the average result of
   this function across each state in the plan."
  [state]
  (let [[x y psi v vx vy s d vs vd] state
        distance-from-center (Math/abs d)
        on-road (< distance-from-center 3.0)]
    (+ (* vs 1.0) ; Increase result based on how quickly car is moving forward
       (if on-road
         10.0  ; Choose bonus for staying on road
         -1000.0) ; Subtract penalty for driving off the road
       (* -15.0 distance-from-center)    ; Subtract penalty for distance from center of road
       (* vd -0.2)))) ; Penalty for driving sideways

; Udacity requires 100 ms delay when submitting.
; Feel free to experiment with other values until then.
(def actuation-period-milliseconds 100)

(defn controller
  "Given telemetry (information about vehicle's situation)
   decide actuation (steering angle and throttle)."
  [telemetry]
  (let [rel-waypoints (convert-points-to-vehicle-frame
                                (:ptsx telemetry) (:ptsy telemetry)
                                [(:x telemetry) (:y telemetry)]
                                (:psi telemetry))
        [x y] [0 0]
        [vx vy] [(:speed telemetry) 0]
        coord (frenet/track rel-waypoints)
        [s d vs vd] (frenet/xyv->sdv coord x y vx vy)
        state [x y 0.0 vx vx vy s d vs vd]
        delay-seconds (* 0.001 actuation-period-milliseconds)
        state (predict state
                [(:steering-angle telemetry) (:throttle telemetry)]
                coord
                delay-seconds)
        problem (figurer/define-problem {:policy policy
                                         :predict (fn [state actuation]
                                                    (predict state actuation coord delay-seconds))
                                         :value value
                                         :initial-state state
                                         :depth 11})  ; Number of time steps to calculate
        solution (figurer/figure problem
                   {:max-seconds delay-seconds})
        plan (figurer/sample-plan solution)
        plan-value (figurer/expected-value solution)
        [steering throttle] (first (:actuations plan))
        plan-xy (mapv #(vec (take 2 %)) (:states plan))
        result {:steering-angle steering
                :throttle throttle
                :waypoints rel-waypoints
                :plan plan-xy}]
    result))

(defn handler
  "Called in response to websocket connection. Handles sending and receiving messages."
  [{:keys [ws-channel] :as req}]
  (go-loop []
    (let [{:keys [message]} (<! ws-channel)
          parsed (parse-message message)]
      (when parsed
        (when (= :telemetry (:type parsed))
          (let [start-millis (.getTime (java.util.Date.))
                response (format-actuation (controller parsed))
                end-millis (.getTime (java.util.Date.))
                millis-remaining (- actuation-period-milliseconds
                                    (- end-millis start-millis))]
            (when (> millis-remaining 0)
              (Thread/sleep millis-remaining))
            (>! ws-channel response))))
      (when (= :manual (:type parsed))
        (Thread/sleep actuation-period-milliseconds)
        (>! ws-channel "42[\"manual\",{}]")))
    (recur)))

(defn -main
  "Run websocket server to communicate with Udacity MPC simulator."
  [& args]
  (println "Starting server")
  (run-server (-> #'handler
                  (wrap-websocket-handler
                    {:read-ch (chan (dropping-buffer 10))
                     :format :str}))
              {:port 4567}))

