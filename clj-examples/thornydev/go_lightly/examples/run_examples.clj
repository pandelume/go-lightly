(ns thornydev.go-lightly.examples.run-examples
  (:require
   [thornydev.go-lightly.examples.boring.boringv1 :as v1]
   [thornydev.go-lightly.examples.boring.generator-kachayev :as genk]
   [thornydev.go-lightly.examples.boring.generator-sq :as gensq]
   [thornydev.go-lightly.examples.boring.generator-tq :as gentq]
   [thornydev.go-lightly.examples.boring.generator-lamina :as genlam]
   [thornydev.go-lightly.examples.boring.multiplex-kachayev :as mk]
   [thornydev.go-lightly.examples.boring.multiplex :as plex]
   [thornydev.go-lightly.examples.boring.multiplex-lamina :as plam]
   [thornydev.go-lightly.examples.boring.multiseq-sq :as ssq]
   [thornydev.go-lightly.examples.search.google-lamina :as googlam]
   [thornydev.go-lightly.examples.search.google :as goog]
   [thornydev.go-lightly.examples.primes.conc-prime-sieve :refer [sieve-main]]
   [thornydev.go-lightly.examples.webcrawler.webcrawler :as crawl])
  (:gen-class))

(declare run-programs)

(defn run-programs [args]
  (doseq [arg args]
    (case (keyword (subs arg 1))
      ;; ---[ "boring" variations ]--- ;;
      :gen-tq1 (gentq/single-generator)
      :gen-tq2 (gentq/multiple-generators)
      :gen-amp (gentq/multiple-generators&)

      :gen-sq1 (gensq/single-generator)
      :gen-sq2 (gensq/multiple-generators)
      :gen-lam1 (genlam/single-generator)
      :gen-lam2 (genlam/multiple-generators)
      
      :plex (plex/multiplex)
      :plex-lam (plam/multiplex)

      :seq-sq (ssq/multiseq)
      
      ;; --- [ kachayev's code ] --- ;;
      :k11 (genk/k1-main1)
      :k12 (genk/k1-main2)
      :k13 (genk/k1-main3)
      :k14 (genk/k1-main4)
      :k15 (genk/k1-main5)
      :k21 (mk/k2-multiplex-any)
      :k22 (mk/k2-multiplex-join)
      
      ;; ---[ original simple Pike go examples before go-lightly library ]--- ;;
      :one (v1/one)
      :two (v1/two)
      :three (v1/three)
      :four (v1/four)
      :five (v1/five)
      :six (v1/six-two-separate-channels)
      :seven (v1/seven-fan-in)
      :eight (v1/eight-wait-channel)
      :nine (v1/nine-two-wait-channels)
      :ten (v1/ten-forked-wait-channel)

      ;; ---[ google search ]--- ;;
      ;; go-lightly version
      :goog1.0  (goog/google-main :goog1.0)
      :goog2.0  (goog/google-main :goog2.0)
      :goog2.1  (goog/google-main :goog2.1)
      :goog2.1b (goog/google-main :goog2.1b)
      :goog3.0  (goog/google-main :goog3.0)

      ;; (clunky) lamina version
      :googlam1.0 (googlam/google-main :googlam1.0)
      :googlam2.0f (googlam/google-main :googlam2.0f)
      :googlam2.0c (googlam/google-main :googlam2.0c)
      :googlam2.1 (googlam/google-main :googlam2.1)
      :googlam3-alpha (googlam/google-main :googlam3-alpha)
      :googlam3.0 (googlam/google-main :googlam3.0)
      :googlam3.0b (googlam/google-main :googlam3.0b)

      ;; ---[ concurrency prime sieve ]--- ;;
      :primes (sieve-main)
      
      ;; CPU usages is about 4.5% when sleeps are set between
      ;; 10 microseconds up to (and including) 1 millisecond
      ;; 5 millis uses about 1% CPU
      ;; 10 millis uses about 0.7% CPU
      :sleep (do (println "starting")
                 (dotimes [i 14500] (Thread/sleep 0 10000)))

      (println "WARN: argument not recognized"))
    (println "------------------"))
  )

(defn -main [& args]
  (if (= ":webcrawler" (first args))
    ;; ---[ concurrency prime sieve ]--- ;;
    ;; this one should only be run by itself (not with other examples in this case stmt)
    ;; and can take up to three optional args after :webcrawler
    ;;  arg1: number of crawler go threads (defaults to 1)
    ;;  arg2: duration (in millis) to run crawling (defaults to 2000)
    ;;  arg3: initial url to crawl (defaults to http://golang.org/ref/)
    ;; example: lein run :webcrawler 16 30000
    (apply crawl/-main (rest args))
    (run-programs args))
  
  (shutdown-agents))
