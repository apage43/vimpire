;-
; Copyright 2009-2017 © Meikel Brandmeyer.
; All rights reserved.
;
; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:
;
; The above copyright notice and this permission notice shall be included in
; all copies or substantial portions of the Software.
;
; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
; THE SOFTWARE.

(ns vimpire.repl
  (:require
    [vimpire.util
     :refer
     [resolve-and-load-namespace safe-var-get stream->seq
      pretty-print pretty-print-causetrace]]
    [clojure.java.io :as io]
    clojure.test)
  (:import
    clojure.lang.Var
    clojure.lang.Compiler
    clojure.lang.LineNumberingPushbackReader))

(def
  ^{:dynamic true :doc
  "A map holding the references to all running repls indexed by their repl id."}
  *repls*
  (atom {}))

(let [id (atom 0)]
  (defn repl-id
    "Get a new Repl id."
    []
    (swap! id inc)))

(def
  ^{:dynamic true :doc
  "Set to true in the Repl if you want pretty printed results. Has no effect
  if clojure.contrib.pprint is not available."}
  *print-pretty*
  false)

(defn add-binding
  [bindings sym]
  (if-let [v (resolve sym)]
    (assoc bindings v (safe-var-get v))
    bindings))

(def bindable-vars
  `[*warn-on-reflection* *print-meta* *print-length*
    *print-level* *compile-path* *command-line-args*
    *unchecked-math* *math-context* *1 *2 *3 *e
    ; Vimpire specific.
    *print-pretty*])

(defn make-repl
  "Create a new Repl."
  ([id] (make-repl id "user" nil nil))
  ([id namespace] (make-repl id namespace nil nil))
  ([id namespace file line]
   {:id        id
    :ns        (resolve-and-load-namespace namespace)
    :test-out  nil
    :file      (or file (str "REPL-" id))
    :line      (or line 0)
    :bindings  (-> (reduce add-binding {} bindable-vars)
                 (assoc #'*compile-path* (System/getProperty
                                           "clojure.compile.path"
                                           "classes")))}))

(defn start
  "Start a new Repl and register it in the system."
  [{:strs [nspace] :or {nspace "user"}}]
  (let [id       (repl-id)
        the-repl (make-repl id nspace)]
    (swap! *repls* assoc id the-repl)
    id))

(defn stop
  "Stop the Repl with the given id."
  [{:strs [id]}]
  (when-not (@*repls* id)
    (throw (ex-info "Not Repl of that id or Repl currently active" {:id id})))
  (swap! *repls* dissoc id)
  nil)

(defn root-cause
  "Drill down to the real root cause of the given Exception."
  [cause]
  (if-let [cause (.getCause cause)]
    (recur cause)
    cause))

(defn make-reader
  "Create a proxy for a LineNumberingsPushbackReader, which delegates
  everything, but allows to specify an offset as initial line."
  [reader offset]
  (proxy [LineNumberingPushbackReader] [reader]
    (getLineNumber [] (+ offset (proxy-super getLineNumber)))))

(defn with-repl*
  "Calls thunk in the context of the Repl with the given id. id may be -1
  to use a one-shot context. Sets the file line accordingly."
  [id nspace file line thunk]
  (let [the-repl (if id
                   (locking *repls*
                     (if-let [the-repl (get @*repls* id)]
                       (do
                         (swap! *repls* dissoc id)
                         the-repl)
                       (throw (ex-info "No Repl of this id" {:id id}))))
                   (make-repl nil nspace file line))]
    (with-bindings
      (merge (:bindings the-repl)
             ; #64: Unbox to ensure int.
             {Compiler/LINE        (Integer. (.intValue (:line the-repl)))
              Compiler/SOURCE      (.getName (io/file (:file the-repl)))
              Compiler/SOURCE_PATH (:file the-repl)
              #'*in*               (make-reader *in* (:line the-repl))
              #'*ns*               (:ns the-repl)
              #'clojure.test/*test-out* (if-let [test-out (the-repl :test-out)]
                                          test-out
                                          *out*)})
      (try
        (thunk)
        (finally
          (when id
            (swap! *repls* assoc id
                   {:id        id
                    :ns        *ns*
                    :test-out  (let [test-out clojure.test/*test-out*]
                                 (when-not (identical? test-out *out*)
                                   test-out))
                    :file      @Compiler/SOURCE_PATH
                    :line      (dec (.getLineNumber *in*))
                    :bindings  (reduce add-binding {} bindable-vars)})))))))

(defmacro with-repl
  "Executes body in the context of the Repl with the given id. id may be -1
  to use a one-shot context. Sets the file line accordingly."
  [id nspace file line & body]
  `(with-repl* ~id ~nspace ~file ~line (fn [] ~@body)))

(defn run
  "Reads from *in* and evaluates the found expressions. The state of the
  Repl is retrieved using the given id. Output goes to *out* and *err*.
  The initial input line and the file are set to the supplied values.
  Ignore flags whether the evaluation result is saved in the star Vars."
  [{:strs [id nspace file line ignore?]
    :or   {nspace "user" file "REPL" line 0 ignore? false}}]
  (with-repl id nspace file line
    (try
      (doseq [form (stream->seq *in*)]
        (let [result (eval form)]
          ((if *print-pretty* pretty-print prn) result)
          (when-not ignore?
            (set! *3 *2)
            (set! *2 *1)
            (set! *1 result))))
      (catch Throwable e
        (binding [*out* *err*]
          (if id
            (pretty-print-causetrace e)
            (prn e)))
        (set! *e e)
        nil))))
