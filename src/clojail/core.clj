(ns clojail.core
  (:use clojure.stacktrace
        [clojure.walk :only [walk postwalk-replace]]
        clojail.jvm)
  (:require [clojail.testers :as tester]) ;; only for debug, really
  (:import (java.util.concurrent TimeoutException TimeUnit FutureTask)
           (clojure.lang LispReader$ReaderException)))

(defn enable-security-manager
  "Enable the JVM security manager. The sandbox can do this for you."
  [] (System/setSecurityManager (SecurityManager.)))

(def uglify-time-unit
  (into {} (for [[enum aliases] {TimeUnit/NANOSECONDS [:ns :nanoseconds]
                                 TimeUnit/MICROSECONDS [:us :microseconds]
                                 TimeUnit/MILLISECONDS [:ms :milliseconds]
                                 TimeUnit/SECONDS [:s :sec :seconds]}
                 alias aliases]
             {alias enum})))

;; postwalk is like a magical recursive doall, to force lazy-seqs
;; within the timeout context; but since it doesn't maintain perfect
;; structure for *every* data type, we want to actually return the
;; original value after we force it, not the result of postwalk
;; replacement
(defn eagerly-consume
  "Recursively force all lazy-seqs in val."
  [val]
  (try
    (postwalk-replace {} val)
    (catch Throwable _))
  val)

(defn thunk-timeout
  "Takes a function and an amount of time in ms to wait for the function to finish
  executing. The sandbox can do this for you."
  ([thunk ms]
     (thunk-timeout thunk ms :ms))
  ([thunk time unit]
     (thunk-timeout thunk time unit identity))
  ([thunk time unit transform]
     (let [task (FutureTask. (comp transform thunk))
           thr (Thread. task)]
       (try
         (.start thr)
         (.get task time (or (uglify-time-unit unit) unit))
         (catch TimeoutException e
           (.cancel task true)
           (.stop thr) 
           (throw (TimeoutException. "Execution timed out.")))
         (catch Exception e
           (.cancel task true)
           (.stop thr) 
           (throw e))))))

(defn- separate [s]
  (set
   (flatten
    (map #(if (symbol? %)
            (let [resolved-s (resolve %)]
              (if-let [s-meta (meta resolved-s)]
                ((juxt (comp symbol str :ns) :name) s-meta)
                (if (= Class (class resolved-s))
                  resolved-s
                  (-> % str (.split "/") (->> (map symbol))))))
            %)
         (flatten s)))))

(defn- collify [form] (if (coll? form) form [form]))

(defn macroexpand-most [form]
  (if (or
       (not (coll? form)) 
       (and (seq? form) 
            (= 'quote (first form))))
    form
    (walk macroexpand-most identity (macroexpand form))))

(defn dotify [form]
  (if-not (coll? form)
    form
    (let [recurse #(walk dotify identity %)]
      (if-not (seq? form)
        (recurse form)
        (let [f (first form)]
          (case f
                quote form
                . (cons 'dot (recurse (rest form)))
                (recurse form)))))))

(def ensafen (comp dotify macroexpand-most))
(def ^{:private true} mutilate (comp separate collify macroexpand-most))

(defn check-form
  "Check a form to see if it trips a tester."
  [form tester]
  (let [mutilated (mutilate form)]
    (if (set? tester)
      (some tester mutilated)
      (let [{:keys [whitelist blacklist]} tester]
        (or (some #(and (symbol? %) whitelist (not (whitelist %)) %) mutilated)
            (and blacklist (some blacklist mutilated)))))))

(defmethod print-dup java.lang.Package
  ([p out]
     (.write out (str "#=(java.lang.Package/getPackage \""
                      (.getName p)
                      "\")"))))

(defn sandbox*
  "This function creates a sandbox function that takes a tester. A tester can either be a plain set of symbols,
   in which case it'll be treated as a blacklist. Otherwise, you can provide a map of :whitelist and
   :blacklist bound to sets. In this case, the whitelist and blacklist will both be used. If you only
   want a whitelist, just supply :whitelist in the map.

   Optional arguments are as follows:
   :timeout, default is 10000 MS or 10 seconds. If the expression evaluated in the sandbox takes
   longer than the timeout, an error will be thrown and the thread running the code will be stopped.
   :namespace, the namespace of the sandbox. The default is (gensym \"sandbox\").
   :context, the context for the JVM sandbox to run in. Only relevant if :jvm? is true. It has a sane
   default, so you shouldn't need to worry about this.
   :jvm?, if set to true, the JVM sandbox will be employed. It defaults to true.
   :transform a function to call on the result returned from the sandboxed code, before returning it,
   while still within the timeout context.

   This function will return a new function that you should bind to something. You can call this
   function with code and it will be evaluated in the sandbox. The function also takes an optional
   second parameter which is a hashmap of vars to values that will be passed to push-thread-bindings.

   Example: (def sb (sandbox #{'alter-var-root 'java.lang.Thread} :timeout 5000))
            (let [writer (java.io.StringWriter.)]
              (sb '(println \"blah\") {#'*out* writer}) (str writer))
   The above example returns \"blah\\n\""
  [& {:keys [timeout namespace context jvm? transform init]
      :or {timeout 10000
           namespace (gensym "sandbox")
           context (-> (empty-perms-list) domain context)
           jvm? true
           transform eagerly-consume}}]
  (when jvm? (enable-security-manager))
  (fn [tester code & [bindings]]
    (let [tester-str (with-out-str
                        (binding [*print-dup* true]
                          (pr tester)))]
      (if-let [problem (check-form code tester)]
        (throw (SecurityException. (str "You tripped the alarm! " problem " is bad!")))
        (thunk-timeout
         (fn []
           (binding [*ns* (create-ns namespace)
                     *read-eval* false]
             (refer 'clojure.core)
             (when init (eval init))
             (let [bindings (or bindings {})
                   code
                   `(do
                      (defmacro ~'dot [object# method# & args#]
                        `(let [~'tester-obj# (binding [*read-eval* true]
                                               (read-string ~~tester-str))
                               ~'tester-fn# (if (map? ~'tester-obj#)
                                              (let [{~'blacklist# :blacklist,
                                                     ~'whitelist# :whitelist} ~'tester-obj#]
                                                (fn [~'target#]
                                                  (or (and ~'whitelist# (not (~'whitelist# ~'target#)) ~'target#)
                                                      (and ~'blacklist# (~'blacklist# ~'target#)))))
                                              ~'tester-obj#)
                               ~'obj# ~object#
                               ~'obj-class# (class ~'obj#)]
                           (if-let [~'bad#
                                    (some ~'tester-fn#
                                          [~'obj-class#
                                           ~'obj#
                                           (.getPackage ~'obj-class#)])]
                             (throw (SecurityException. (str "You tripped the alarm! " ~'bad# " is bad!")))
                             (. ~object# ~method# ~@args#))))
                      ~(doseq [[var new-var] bindings]
                         (alter-var-root var (constantly new-var)))
                      ~(ensafen code))]
               (jvm-sandbox #(eval code) context))))
         timeout :ms transform)))))

(defn sandbox
  "Convenience wrapper function around sandbox* to create a sandbox function out of a tester.
   Takes the same arguments as sandbox* with the addition of the tester argument. Returns a
   sandbox function like sandbox* returns, the difference being that the tester is hardcoded
   and doesn't need to be passed to the created function."
  [tester & args]
  (let [sb (apply sandbox* args)]
    (partial sb tester)))

;; install a default sandbox for testing
(def ^{:private true} sb (sandbox tester/secure-tester))

(defn safe-read
  "Read a string from an untrusted source. Mainly just disables read-eval,
but also repackages thrown exceptions to make it easier to
discriminate among them. read-eval errors will be thrown as
IllegalStateException; other exceptions will be thrown unchanged."
  ([]
     (binding [*read-eval* false]
       (let [repackage (fn [e]
                         (let [msg (str (.getName (class e))
                                        ": "
                                        (.getMessage (root-cause e)))]
                           (if (.contains msg "EvalReader")
                             (IllegalStateException. msg)
                             e)))]
         (try
           (read)
           (catch LispReader$ReaderException e
             (throw (repackage e)))
           (catch Throwable e
             (let [cause (.getCause e)]
               (cond
                (not cause) (throw e)
                (not (instance? LispReader$ReaderException cause)) (throw e)
                :else (throw (repackage cause)))))))))
  ([str]
     (with-in-str str
       (safe-read))))
