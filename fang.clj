(require '[clojure.edn     :as edn]
         '[clojure.java.io :as io]
         '[clojure.string  :as str])

(import 'java.io.ByteArrayInputStream)
(import 'java.io.InputStream)
(import 'java.io.SequenceInputStream)
(import 'java.security.MessageDigest)
(import 'java.util.Enumeration)

(defn base64-encode
  "Non-standard base64 to avoid name munging"
  [^InputStream in]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_$"
        sb    (StringBuilder.)]
    (loop [shift 4
           buf   0]
      (let [got (.read in)]
        (if (neg? got)
          (do
            (when-not (= shift 4)
              (let [n (bit-and (bit-shift-right buf 6) 63)]
                (.append sb (.charAt table n))))
            (str sb))
          (let [buf (bit-or buf (bit-shift-left got shift))
                n (bit-and (bit-shift-right buf 6) 63)]
            (.append sb (.charAt table n))
            (let [shift (- shift 2)]
              (if (neg? shift)
                (do
                  (.append sb (.charAt table (bit-and buf 63)))
                  (recur 4 0))
                (recur shift (bit-shift-left buf 6))))))))))

(defn >enum
  [s]
  (let [s (atom s)]
    (reify
      Enumeration
      (hasMoreElements [this] (boolean (seq @s)))
      (nextElement [this]
        (let [x (first @s)] (swap! s rest) x)))))

(defn input-for
  [f]
  (io/file (str "server/" f)))

(defn output-for
  [f marker]
  (-> f
    (str/replace #"vimpire" (fn [s] (str "venom/" s "/" marker)))
    io/file))

(defn marker
  [files]
  (with-open [input (-> files
                      (->> (map input-for) (map io/input-stream))
                      >enum
                      SequenceInputStream.
                      io/reader)]
    (-> input
      ^String slurp
      ^bytes (.getBytes "UTF-8")
      (->> (.digest (MessageDigest/getInstance "SHA-1")))
      ByteArrayInputStream.
      base64-encode
      (->> (str "vv_")))))

(defn encode-sources
  [files m mns]
  (doseq [f files]
    (let [inputf  (input-for f)
          outputf (output-for f m)]
      (.mkdirs (.getParentFile outputf))
      (with-open [input  (io/reader inputf)
                  output (io/writer outputf)]
        (-> input
          slurp
          (str/replace #"(?<!:)vimpire\." #(str % mns "."))
          (->> (spit output)))))))

(defn encode-actions
  [mns]
  (-> "actions.clj"
    slurp
    (str/replace #"(?<!:)vimpire" #(str % "." mns))
    (->> (spit "actions_poisoned.clj"))))

(defn main
  []
  (let [files ["vimpire/backend.clj"
               "vimpire/nails.clj"
               "vimpire/repl.clj"
               "vimpire/util.clj"]
        m     (marker files)
        mns   (str/replace m #"_" "-")]
    (encode-sources files m mns)
    (encode-actions mns)))
