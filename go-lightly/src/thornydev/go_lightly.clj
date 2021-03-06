(ns thornydev.go-lightly
  (:refer-clojure :exclude [peek take])
  (:import (java.io Closeable)
           (java.util ArrayList)
           (java.util.concurrent LinkedTransferQueue TimeUnit
                                 LinkedBlockingQueue TimeoutException)))

;; ---[ go routines ]--- ;;

(def inventory (atom []))

(defmacro go
  "Launches a Clojure future as a 'go-routine' and returns the future.
  It is not necessary to keep a reference to this future, however.
  Instead, you can call the accompanying stop function to
  shutdown (cancel) all futures created by this function."
  [& body]
  `(let [fut# (future ~@body)]
     (swap! inventory conj fut#)
     fut#))

(defn stop
  "Stop (cancel) all futures started via the go macro.
  This should only be called when you are finished with
  all go routines running in your app, ideally at the end
  of the program.  It can be reused on a new set of go
  routines, as long as they were started after this stop
  fn returned, as it clears an cached of remembered go
  routines that could be subject to a race condition."
  []
  (doseq [f @inventory] (future-cancel f))
  (reset! inventory [])
  nil)

(defn shutdown
  "Stop (cancel) all futures started via the go macro and
  then call shutdown-agents to close down the entire Clojure
  agent/future thread pool."
  []
  (stop)
  (shutdown-agents))


(defmacro gox
  "Form of go macro that wraps the body in a try/catch that ignores
  InterruptedException and prints the stack trace for any other exception
  that is thrown.  Useful, since exceptions in Clojure futures do not
  get printed out.  The InterruptedException is present to allow you to
  write never-ending go routines that can be cancelled with stop and
  and print out an InterruptedException to the console/REPL when it is
  cancelled."
  [& body]
  `(let [fut# (future (~'try ~@body
                            ~'(catch InterruptedException ie)
                            ~'(catch Exception e (.printStackTrace e))))]
     (swap! inventory conj fut#)
     fut#))

(defmacro go&
  "Launch a 'go-routine' like deamon Thread to execute the body.
  This macro does not yield a future so it cannot be dereferenced.
  Instead it returns the Java Thread itself.

  It is intended to be used with channels for communication
  between threads.  This thread is not part of a managed Thread
  pool so cannot be directly shutdown.  It will stop either when
  all non-daemon threads cease or when you stop it some ad-hoc way."
  [& body]
  `(doto (Thread. (fn [] (do ~@body))) (.setDaemon true) (.start)))


;; ---[ channels and channel fn ]--- ;;

(declare closed?)

(defprotocol GoChannel
  (put [this val] "Put a value on a channel. May or may not block depending on type and circumstances")
  (poll [this] "Take the first value from a channel, but return nil if none present rather than block")
  (take [this] "Take the first value from a channel")
  (size [this] "Returns the number of values on the channel")
  (peek [this] "Retrieve, but don't remove, the first element on the channel")
  (clear [this] "Remove all elements from the channel without returning them"))

(deftype Channel [^LinkedTransferQueue q open? prefer?]
  GoChannel
  (put [this val]
    (if @(.open? this)
      (.transfer q val)
      (throw (IllegalStateException. "Channel is closed. Cannot 'put'."))))

  (take [this] (.take q))
  (poll [this] (.poll q))
  (peek [this] (.peek q))
  (size [this] 0)
  (clear [this] (.clear q))

  Object
  (toString [this]
    (let [stat-str (when-not @(.open? this) ":closed ")]
      (if-let [sq (seq (.toArray q))]
        (str stat-str "<=[ ..." sq "] ")
        (str stat-str "<=[] "))))

  Closeable
  (close [this]
    (reset! (.open? this) false)
    nil))

(deftype BufferedChannel [^LinkedBlockingQueue q open? prefer?]
  GoChannel
  (put [this val]
    (if @(.open? this)
      (.put q val)
      (throw (IllegalStateException. "Channel is closed. Cannot 'put'."))))
  (take [this] (.take q))
  (poll [this] (.poll q))
  (peek [this] (.peek q))
  (size [this] (.size q))
  (clear [this] (.clear q))

  Object
  (toString [this]
    (let [stat-str (when-not @(.open? this) ":closed ")]
      (str stat-str "<=[" (apply str (interpose " " (seq (.toArray q)))) "] ")))

  Closeable
  (close [this]
    (reset! (.open? this) false)
    nil))

(deftype TimeoutChannel [^LinkedBlockingQueue q open? prefer?]
  GoChannel
  (put [this val] (throw (UnsupportedOperationException.
                          "Cannot put values onto a TimeoutChannel")))
  (take [this] (.take q))
  (poll [this] (.poll q))
  (peek [this] (.peek q))
  (size [this] (.size q))
  (clear [this] (throw (UnsupportedOperationException.
                        "Cannot clear a TimeoutChannel")))

  Object
  (toString [this]
    (if (closed? this)
      ":closed <=[:go-lightly/timeout] "
      "<=[] "))

  Closeable
  (close [this]
    (reset! (.open? this) false)
    nil))

(defn close!
  "Modifies the channel to be closed so that producers can no
  longer put any data on the channel.  Receivers can take any
  existing values off the channel after it is closed."
  [channel] (.close channel))

(defn closed?
  "Predicate check of whether a channel has closed status or not."
  [channel]
  (not @(.open? channel)))

(defn prefer!
  "Modifies the channel to have preferred status in a select
  statement, so it will be preferentially read from over a
  non-preferred channel if multiple channels have values ready."
  [channel]
  (reset! (.prefer? channel) true)
  channel)

(defn unprefer!
  "Modifies the channel to remove preferred status in a select
  statement, so it will be no longer be preferentially read
  from over a non-preferred channel."
  [channel]
  (reset! (.prefer? channel) false)
  channel)

(defn preferred?
  "Predicate check of whether a channel has preferred status in
  a select statement or not."
  [channel]
  @(.prefer? channel))

(defn channel
  "If no size is specified, returns a synchronous blocking channel.
   If a size is passed is in, returns a bounded asynchronous channel."
  ([] (->Channel (LinkedTransferQueue.) (atom true) (atom false)))
  ([^long capacity]
     (->BufferedChannel (LinkedBlockingQueue. capacity)
                        (atom true) (atom false))))

(defn preferred-channel
  "Create a channel with preferred status. Has the same API
  as the channel function."
  ([] (prefer! (channel)))
  ([capacity] (prefer! (channel capacity))))

(defn timeout-channel
  "Create a channel that after the specified duration (in
   millis) will have the :go-lightly/timeout sentinel value"
  [duration-ms]
  (let [ch (->TimeoutChannel (LinkedBlockingQueue. 1) (atom true) (atom true))]
    (go& (do (Thread/sleep duration-ms)
             (.put (.q ch) :go-lightly/timeout)
             (close! ch)))
    ch))


;; ---[ select and helper fns ]--- ;;

;; specified by the version of select you call
;; select wants choose-val
;; selectf wants choose-tuple, which returns the channel
;; with the val taken off it
(def ^:private ^:dynamic *choose-fn*)

(defn- now [] (System/currentTimeMillis))

(defn- timed-out? [start duration]
  (when duration
    (> (now) (+ start duration))))

(defn- filter-ready
  "Filters the list of channels passed in to find all that have a
  ready value and returns a seq of 'ready channels'."
  [chans]
  (seq (doall (filter #(not (nil? (peek %))) chans))))

;; NOTE: the choose methods have a race condition issue.
;; Tey are assuming these ready chans are still ready but if another thread
;; grabs the value between peek and take, it may incorrectly block waiting
;; for another value to go on.
;; The chosen solution is to use poll rather than take. If nothing is found
;; then return nil and then the calling method try again (possiblity with
;; a different channel).
(defn- choose-val
  "From the list of 'ready channels' passed in, selects one at random,
  takes its ready value and returns it."
  [ready-chans]
  (poll (nth ready-chans (rand-int (count ready-chans)))))

(defn- choose-tuple
  "From the list of 'ready channels' passed in, selects one at random,
  takes its ready value and returns a tuple (vector) of the
  channel-read-from and the value read:  [chan val]"
  [ready-chans]
  (let [ch (nth ready-chans (rand-int (count ready-chans)))]
    [ch (poll ch)]))

(defn- attempt-select
  "First attempts to take a value from a ready preferred channel.
  If not successful, attempts to take a value from a ready non-preferred
  channel. Returns the value it took from a channel or nil if none ready."
  [pref-chans reg-chans]
  (if-let [ready-chans (filter-ready pref-chans)]
    (*choose-fn* ready-chans)
    (when-let [ready-chans (filter-ready reg-chans)]
      (*choose-fn* ready-chans))))

(defn- probe-til-ready [pref-chans reg-chans timeout]
  (let [start (now)]
    (loop [chan-val nil mcsec 200]
      (cond
       chan-val chan-val
       (timed-out? start timeout) nil
       :else (do (Thread/sleep 0 mcsec)
                 (recur (attempt-select pref-chans reg-chans)
                        (min 1500 (+ mcsec 25))))))))

(defn- separate-preferred [channels]
  (loop [chans channels pref [] reg []]
    (if (seq chans)
      (if (preferred? (first chans))
        (recur (rest chans) (conj pref (first chans)) reg)
        (recur (rest chans) pref (conj reg (first chans))))
      [pref reg])))

(defn- doselect [channels timeout nowait]
  (let [[pref-chans reg-chans] (separate-preferred channels)]
    (if-let [ready (attempt-select pref-chans reg-chans)]
      ready
      (when-not nowait
        (probe-til-ready pref-chans reg-chans timeout)))))

(defn- doselect-nowait
  ([chan] (poll chan))

  ([chan & channels]
     (doselect (conj channels chan) nil :nowait)))

(defn- parse-nowait-args [channels]
  (if (keyword? (last channels))
    (split-at (dec (count channels)) channels)
    [channels nil]))


;; ---[ public select fns ]--- ;;

(defn partition-bifurcate
  "Partition a collection into two vectors. The first passes
  the predicate test of fn +f+, the second fails it.  Returns
  a vector of two vectors.  Non-lazy."
  [f coll]
  (reduce (fn [[vecyes vecno] value]
            (if (f value)
              [(conj vecyes value) vecno]
              [vecyes (conj vecno  value)])) [[] []] coll))

(defn selectf
  "Control structure variable arity fn. Must be an even number of arguments where
  the first is either a GoChannel to read from or the keyword :default. The second
  arg is a function to call if the channel is read from.  Handler fns paired with
  channels should accept one argument - the value read from the channel.  The
  handler function paired with :default takes no args.  If no :default clause is
  provided, it blocks until a value is read from a channel (which could include
  a TimeoutChannel). Returns the value returned by the handler fn."
  [& args]
  (binding [*choose-fn* choose-tuple]
    (let [chfnmap (apply array-map args)
          [keywords chans] (partition-bifurcate
                            keyword?
                            (reduce #(conj % %2) [] (keys chfnmap)))
          choice (doselect chans nil (first keywords))]

      ;; invoke the associated fn
      (if choice
        ((chfnmap (nth choice 0)) (nth choice 1))
        ((chfnmap (first keywords)))))))

(defn select
  "Select one message from the channels passed in. Blocks until a
  message can be read from a channel."
  ([chan] (take chan))

  ([chan & channels]
     (binding [*choose-fn* choose-val]
       (doselect (conj channels chan) nil nil))))

(defn select-timeout
  "Like select, selects one message from the channel(s) passed in
  with the same behavior except that a timeout is in place that
  if no message becomes available before the timeout expires, nil
  will be returned."
  ([timeout chan]
     (.poll (.q chan) timeout TimeUnit/MILLISECONDS))

  ([timeout chan & channels]
     (binding [*choose-fn* choose-val]
       (doselect (conj channels chan) timeout nil))))

(defn select-nowait
  "Like select, selects one message from the channel(s) passed in
  with the same behavior except that if no channel has a message
  ready, it immediately returns nil or the sentinel keyword value
  passed in as the last argument."
  [& channels-or-sentinel]
  (binding [*choose-fn* choose-val]
    (let [[chans sentinel] (parse-nowait-args channels-or-sentinel)
          result (apply doselect-nowait chans)]
      (if (and (nil? result) (seq? sentinel))
        (first sentinel)
        result))))


;; ---[ channels to collection/sequence conversions ]--- ;;

(defn channel->seq
  "Takes a snapshot of all values on a channel *without* removing
  the values from the channel. Returns a (non-lazy) seq of the values.
  Generally recommended for use with a buffered channel, but will return
  return a single value if a producer is waiting to put one on."
  [ch]
  (seq (.toArray (.q ch))))

(defn channel->vec
  "Takes a snapshot of all values on a channel *without* removing
  the values from the channel. Returns a vector of the values.
  Generally recommended for use with a buffered channel, but will return
  return a single value if a producer is waiting to put one on."
  [ch]
  (vec (.toArray (.q ch))))

(defn drain
  "Removes all the values on a channel and returns them as a non-lazy seq.
  Generally recommended for use with a buffered channel, but will return
  a pending transfer value if a producer is waiting to put one on."
  [ch]
  (let [al (ArrayList.)]
    (.drainTo (.q ch) al)
    (seq al)))

(defn lazy-drain
  "Lazily removes values from a channel. Returns a Cons lazy-seq until
  it reaches the end of the channel.
  Generally recommended for use with a buffered channel, but will return
  on or more values one or more producer(s) is waiting to put a one or
  more values on.  There is a race condition with producers when using."
  [ch]
  (if-let [v (poll ch)]
    (cons v (lazy-seq (lazy-drain ch)))
    nil))

;; ---[ helper macros ]--- ;;

;; Credit to mikera
;; from: http://stackoverflow.com/a/6697469/871012
;; TODO: is there a way to do this where the future can
;;       return something or do something before being
;;       cancelled?  Would require an abstraction around future ...
(defmacro with-timeout [millis & body]
  `(let [fut# (future ~@body)]
     (try
       (.get fut# ~millis TimeUnit/MILLISECONDS)
       (catch TimeoutException x#
         (do
           (future-cancel fut#)
           nil)))))
