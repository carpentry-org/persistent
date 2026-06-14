;; Mirror of the Carp head-to-head benches (carp/all_bench.carp).
;; Plain persistent operations only — no transients, no type hints.

(defn bench [label f]
  ;; warmup ~2s for JIT
  (let [t0 (System/nanoTime)]
    (while (< (- (System/nanoTime) t0) 2000000000) (f)))
  ;; size each sample to >= ~1ms, take 50 samples
  (let [t1 (System/nanoTime)
        _ (f)
        single (max 1 (- (System/nanoTime) t1))
        reps (max 1 (long (Math/ceil (/ 1000000.0 single))))
        samples (mapv (fn [_]
                        (let [t (System/nanoTime)]
                          (dotimes [_ reps] (f))
                          (/ (double (- (System/nanoTime) t)) reps)))
                      (range 50))
        sorted (sort samples)]
    (printf "%-22s best %12.1f ns   median %12.1f ns%n"
            label (first sorted) (nth sorted 25))
    (flush)))

(defn build-vec [n]
  (loop [v [] i 0] (if (< i n) (recur (conj v i) (inc i)) v)))
(defn vec-get-sum [v n]
  (loop [acc 0 i 0] (if (< i n) (recur (+ acc (nth v i)) (inc i)) acc)))
(defn vec-assoc [v n]
  (loop [v v i 0] (if (< i n) (recur (assoc v i 7) (inc i)) v)))

(defn build-map [n]
  (loop [m {} i 0] (if (< i n) (recur (assoc m i i) (inc i)) m)))
(defn map-get-sum [m n]
  (loop [acc 0 i 0] (if (< i n) (recur (+ acc (get m i)) (inc i)) acc)))

(defn build-sorted [n]
  (loop [m (sorted-map) i 0] (if (< i n) (recur (assoc m i i) (inc i)) m)))

(defn build-hash-set [n]
  (loop [s #{} i 0] (if (< i n) (recur (conj s i) (inc i)) s)))
(defn build-sorted-set [n]
  (loop [s (sorted-set) i 0] (if (< i n) (recur (conj s i) (inc i)) s)))
(defn set-member-count [s n]
  (loop [acc 0 i 0] (if (< i n) (recur (if (contains? s i) (inc acc) acc) (inc i)) acc)))

(defn build-list [n]
  (loop [l '() i 0] (if (< i n) (recur (cons i l) (inc i)) l)))
(defn list-sum [l] (reduce + 0 l))

(defn queue-cycle [n]
  (let [q (loop [q clojure.lang.PersistentQueue/EMPTY i 0]
            (if (< i n) (recur (conj q i) (inc i)) q))]
    (loop [q q acc 0]
      (if (seq q) (recur (pop q) (+ acc (peek q))) acc))))

;; --- mixed interaction ---
(defn churn-vec [base rounds]
  (loop [v base i 0 acc 0]
    (if (< i rounds)
      (let [k (mod i 1000)]
        (recur (assoc v k i) (inc i) (+ acc (nth v k))))
      acc)))

(defn churn-map [base rounds]
  (loop [m base i 0 acc 0]
    (if (< i rounds)
      (let [k (mod i 1000)]
        (recur (assoc m k i) (inc i) (+ acc (get m k))))
      acc)))

(defn branch-vec [base k]
  (let [versions (mapv (fn [i] (assoc base i (* i 2))) (range k))]
    (reduce + 0 (map (fn [i] (nth (nth versions i) i)) (range k)))))

(defn branch-map [base k]
  (let [versions (mapv (fn [i] (assoc base i (* i 2))) (range k))]
    (reduce + 0 (map (fn [i] (get (nth versions i) i)) (range k)))))

(def vec1k (build-vec 1000))
(def map1k (build-map 1000))
(def ord1k (build-sorted 1000))
(def hset1k (build-hash-set 1000))
(def oset1k (build-sorted-set 1000))
(def list1k (build-list 1000))

(println "=== Clojure persistent collections ===")
(bench "vector push 1k" #(build-vec 1000))
(bench "vector push 10k" #(build-vec 10000))
(bench "vector get 1k" #(vec-get-sum vec1k 1000))
(bench "vector assoc 1k" #(vec-assoc vec1k 1000))
(bench "hash-map insert 1k" #(build-map 1000))
(bench "hash-map insert 10k" #(build-map 10000))
(bench "hash-map get 1k" #(map-get-sum map1k 1000))
(bench "ord-map insert 1k" #(build-sorted 1000))
(bench "ord-map get 1k" #(map-get-sum ord1k 1000))
(bench "hash-set insert 1k" #(build-hash-set 1000))
(bench "hash-set member 1k" #(set-member-count hset1k 1000))
(bench "ord-set insert 1k" #(build-sorted-set 1000))
(bench "ord-set member 1k" #(set-member-count oset1k 1000))
(bench "list prepend 1k" #(build-list 1000))
(bench "list sum 1k" #(list-sum list1k))
(bench "queue cycle 1k" #(queue-cycle 1000))
(bench "churn vec 2k" #(churn-vec vec1k 2000))
(bench "churn map 2k" #(churn-map map1k 2000))
(bench "branch vec x64" #(branch-vec vec1k 64))
(bench "branch map x64" #(branch-map map1k 64))

;; transient build, compared to Carp's push-back-owned
(defn build-vec-transient [n]
  (persistent! (loop [v (transient []) i 0] (if (< i n) (recur (conj! v i) (inc i)) v))))
(bench "vector push 1k (transient)" #(build-vec-transient 1000))
(bench "vector push 10k (transient)" #(build-vec-transient 10000))
