(ns thornydev.go-lightly.boring.generator
  (:refer-clojure :exclude [peek take])  
  (:require [thornydev.go-lightly :refer :all]))

;; ---[ Use the go macro that requires a stop ]--- ;;

(defn- boring [msg]
  (let [ch (channel)]
    (go (loop [i 0]
          (put ch (str msg " " i))
          (Thread/sleep (rand-int 1000))
          (recur (inc i))))
    ch))

(defn single-generator []
  (let [ch (boring "boring!")]
    (dotimes [_ 5] (println "You say:" (take ch))))
  (println "You're boring: I'm leaving.")
  (stop))

(defn multiple-generators []
  (let [joe (boring "Joe")
        ann (boring "Ann")]
    (dotimes [_ 10]
      (println (take joe))
      (println (take ann))))
  (println "You're boring: I'm leaving.")
  (stop))



;; ---[ Use the fire-and-forget go& macro ]--- ;;

(defn- boring& [msg]
  (let [ch (channel)]
    (go& (loop [i 0]
           (put ch (str msg " " i))
           (Thread/sleep (rand-int 1000))
           (recur (inc i))))
    ch))

(defn multiple-generators& []
  (let [joe (boring& "Joe&")
        ann (boring& "Ann&")]
    (dotimes [_ 10]
      (println (take joe))
      (println (take ann))))
  (println "You're boring: I'm leaving."))





;; ---[ sandbox for learning LinkedTransferQueue ]--- ;;

(defn prf [& vals]
  (println (apply str (interpose " " (map #(if (nil? %) "nil" %) vals))))
  (flush))
