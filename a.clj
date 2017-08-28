(ns a
  (:require [clojure.java.io :as io]
            [cljs.reader :as cljs-reader]
            [clojure.tools.reader :as tools-reader]
            [cider.nrepl.middleware.util.java.parser :as cider-java]
            [clojure.tools.reader.reader-types :as rt]
            [hiccup.core :as h])
  (:import [java.nio.file Files]))

(set! *warn-on-reflection* true)

(defn ns->source-string
  [ns]
  (when-let [path (some-> ns ns-publics first val meta :file)]
    (-> path io/resource slurp)))

(defn clj-whitespace
  [c]
  (case c
    ;; (\, \space \newline \tab)
    (\, \space  \tab) true
    false))

(defn clj-special
  [c]
  (case c
    (\( \) \{ \} \[ \] \, \space \newline \tab) true
    false))

(defn whitespace-string
  [{:keys [^String buf pos]}]
  (loop [idx pos]
    (if (and (< idx (.length buf)) (clj-whitespace (.charAt buf idx)))
      (recur (inc idx))
      (.substring ^String buf pos idx))))

(defn symbol-string
  [{:keys [^String buf pos]}]
  (loop [idx pos]
    (if (and (< idx (.length buf)) (not (clj-special (.charAt buf idx))))
      (recur (inc idx))
      (.substring ^String buf pos idx))))

(defrecord Substring [chars offset length])

(defn substring
  [^Substring parent ^long off ^long len]
  (->Substring
    (:chars parent)
    (+ off (:offset parent))
    len))

(defn lex
  [orig-ctx]
  (loop [{:keys [^String buf pos line] :as ctx} (-> orig-ctx (assoc :line 1) transient)
         tokens (transient [])]
    #_(when (< pos (.length buf))
      (println "char = " (.charAt buf pos))
      )
    (if (>= pos (.length buf))
      (persistent! tokens)
      (case (.charAt buf pos)
        \newline
        (recur (assoc! ctx :line (inc line) :pos (inc pos))
               (conj! tokens {:type :ws :text "\n" :line line}))

        (\, \space \tab)
        (let [^String whitespace (whitespace-string ctx)]
          (recur (assoc! ctx :pos (+ pos (.length whitespace)))
                 (conj! tokens {:type :ws :text whitespace})))
        
        (\( \) \{ \} \[ \] \` \~ \# \^ \')
        (recur (assoc! ctx :pos (inc pos))
               (conj! tokens (.charAt buf pos)))

        \" (let [string-end (loop [i (inc pos)
                                   escaped false]
                              (cond
                                (= i (.length buf))
                                i

                                (and (not escaped) (= \" (.charAt buf i)))
                                (inc i)

                                (= \\ (.charAt buf i))
                                (recur (inc i) true)

                                :else
                                (recur (inc i) false)))]
             (recur (assoc! ctx :pos string-end)
                    (conj! tokens {:type :string :text (.substring buf pos string-end)})))

        \; (let [comment-end (loop [i pos]
                               (if (and (< i (.length buf)) (not= \newline (.charAt buf i)))
                                 (recur (inc i))
                                 i))]
             (recur (assoc! ctx :pos comment-end)
                    (conj! tokens {:type :comment :text (.substring buf pos comment-end)})))
        
        \\ (recur (assoc! ctx :pos (+ 2 pos))
                  (conj! tokens {:type :char-literal :text (str (.charAt buf (inc pos)))}))

        (let [^String sym (symbol-string ctx)]
          (recur (assoc! ctx :pos (+ pos (.length sym)))
                 (conj! tokens {:type :symbol :text sym})))))))

#_(type (resolve (read-string "java.lang.String")))

(defn resolve?
  [s]
  (try (resolve s)
       (catch Exception e nil)))

(defn parse-symbol
  [{:keys [text] :as sym}]
  (try
    (let [rd (read-string text)]
      (if (keyword? rd)
        (assoc sym :val rd)
        (assoc sym :val rd :resolved (resolve? rd))))
    (catch Exception e
      sym)))

(defn javadoc-url
  [^Class cls]
  ;; https://docs.oracle.com/javase/8/docs/api/java/lang/String.html
  (let [^String clsname (.getName cls)]
   (str "https://docs.oracle.com/javase/8/docs/api/"
     (.replace clsname \. \/)
     ".html")))

(def ^:const clojure-core-ns (find-ns 'clojure.core))

(defn add-link
  [text meta]
  (let [props
        (cond
          (= clojure-core-ns (:ns meta))
          {:href (format "https://clojuredocs.org/clojure.core/%s" (:name meta))}
                 
          (:file meta)
          {:href (str (ns-name (:ns meta)) ".html#L" (:line meta))})]
    [:a
     (cond-> props
       (:doc meta)
       (assoc :title (:doc meta)))
     text]))

(def special-form? #{"def" "let" "if" "do" "fn" "loop" "recur" "try" "throw" "quote" "var"})

(class? (resolve (symbol (namespace 'clojure.lang.Util/equiv))))

(defn render-symbol
  [{:keys [val resolved ^String text]}]
  (cond
    (nil? val)
    [:span {:class :unparsed} text]

    (.startsWith text ".")
    [:span {:class :java-class} text]

    (special-form? text)
    [:span {:class :macro} text]

    (keyword? val)
    [:span {:class :keyword} text]

    (class? resolved)
    [:span {:class :java-class} text]

    (meta resolved)
    (let [m (meta resolved)
          core? (= clojure-core-ns (:ns m))
          text (add-link text m)
          classes (cond-> [:var-ref]
                    (:macro m) (conj :macro)
                    core? (conj :clojure-core))]
      [:span {:class (clojure.string/join " " (mapv name classes))} text])

    
    (symbol? val)
    (let [rns (some-> val namespace symbol)]
      (cond
        (some-> rns resolve? class?)
        [:span {:class :java-class} (name rns) "/" [:span {:class :symbol} (name val)]]

        :else
        [:span {:class :symbol} val]))

    :else
    (if-let [cls (and (.endsWith text ".")
                      (some-> text (.substring 0 (dec (.length text))) symbol resolve))]
      ;; TODO: fix this for java class names (instead of just java constructor calls)
      [:span {:class :java-class} [:a {:href (javadoc-url cls)} text]]
      [:span {:class :unknown} text])))

(defn render-lex
  [t]
  (cond
    (nil? t)
    []

    (char? t)
    [:span {:class :punctuation} (str t)]

    :else
    (case (:type t)
      :ws [:span {:class :whitespace} (:text t)]
      :symbol (render-symbol (parse-symbol t))
      :string [:span {:class :string} (:text t)]
      :comment [:span {:class :comment} (:text t)])))

(declare parse-list)

(def open->closed
  {\( \)
   \[ \]
   \{ \}
   "#{" \}})

(def closed->open
  {\) \(
   \] \[
   \} \{})

(defn parse-one
  [ts]
  (when-let [t (first ts)]
    (if-let [closing-delimiter (open->closed t)]
      (parse-list closing-delimiter (next ts))
      (case t
        \#
        (let [ts (next ts)
              nt (first ts)]
          ;; dispatch
          (case nt
            \_ (-> (parse-one nt) :rest parse-one)
            \( (let [{:keys [val rest]} (parse-one ts)]
                 {:val {:type :lambda :text val} :rest rest})
            \{ (update-in (parse-one ts) [:val :delim] (constantly"#{"))

            \'
            (let [{:keys [val rest]} (parse-one ts)]
              {:val {:type :var-quote :text (-> val :val :text)} :rest rest})

            {:type :symbol :text "?"}
            (let [{:keys [val rest]} (parse-one ts)]
              {:val {:type :reader-conditional :text val} :rest rest})

            (case (:type nt)
              :string
              (let [{:keys [val rest]} (parse-one ts)]
                {:val {:type :regex :text (:text val)} :rest rest})
              :symbol
              {:val {:type :data-reader :text (:text nt)} :rest (next ts)})))
        \^
        (let [{:keys [val rest]} (parse-one (next ts))]
          {:val {:type :meta :val val} :rest rest})

        \' (let [{:keys [val rest]} (parse-one (next ts))]
             {:val {:type :quote :val val} :rest rest})
        {:val t :rest (next ts)}))))

(defn parse-list
  [closer ots]
  (loop [forms []
         ts ots]
    (if-let [t (first ts)]
      (if-let [sub-closer (open->closed t)]
        (let [{:keys [val rest]} (parse-list sub-closer (next ts))]
          (recur (conj forms val) rest))
        (if (= closer t)
          {:val {:type :coll :delim (closed->open closer) :contents forms}
           :rest (next ts)}
          (let [{:keys [val rest]} (parse-one ts)]
            (recur (conj forms val) rest))
          #_(recur (conj forms t) (next ts))))
      (throw (ex-info "expecting closer" {:closer closer :ts ts})))))

(defn parse-many
  [ts]
  (loop [forms []
         ts ts]
    (if (some? ts)
      (let [{:keys [val rest]} (parse-one ts)]
        (recur (conj forms val) rest))
      forms)))


(defn render-parse
  [t]
  (case (:type t)
    :ws           [:span {:class :whitespace} (:text t)]
    :symbol       (render-symbol (parse-symbol t))
    :string       [:span {:class :string} (:text t)]
    :comment      [:span {:class :comment} (:text t)]
    :data-reader  [:span {:class :metadata} (str "#" (:text t))]
    :regex        [:span "#" [:span {:class :strign} (:text t)]]
    :char-literal [:span {:class :string} (:text t)]
    :meta         [:span "^" (render-parse (:val t))] 
    :quote        [:span "'" (render-parse (:val t))]
    :var-quote    [:span "#'" [:span {:class :var-ref} (:text t)]]

    :coll
    (into [:span (:delim t)]
          (conj (mapv render-parse (:contents t)) (open->closed (:delim t))))

    :lambda
    (into [:span "#("]
          (conj (mapv render-parse (:contents (:text t))) ")"))

    :reader-conditional
    (into [:span "#?"] (mapv render-parse (:contents (:text t))))
    
    (case t
      (\` \~) t
      [:span {:class :unparsed} (pr-str t)])))

(def ^:dynamic *timings-enabled* false)

(defmacro timing
  [name & body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)
         end# (System/nanoTime)]
     (when *timings-enabled*
      (printf "%s: %.02f ms\n" ~name (/ (double (- end# start#)) 1e6)))
     result#))

(defn render-code
  [src]
  (when src
    (let [lexed (timing "lex" (lex {:pos 0 :buf src}))
          forms (timing "parse" (parse-many lexed))] 
      (timing "render"
       (h/html
         [:head [:link {:rel :stylesheet :href "file:///home/russell/code.css"}]
          [:div {:class :container}
           [:pre (into [:code {:class "gutter"}]
                   (for [line (range 1 (inc (count (clojure.string/split-lines src))))]
                     [:span {:class :punctuation :id (format "L%d" line)} (format "%04d\n" line)]))]
           [:div {:style "width:1px;"}]
           [:pre {:class "source"} (into [:code] (mapv render-parse forms))]]])))))

(binding [*timings-enabled* true]
 (timing "total"
   (spit
     "/home/russell/asdf.html"
     (render-code (slurp (io/resource "clojure/core.clj"))))))

(parse-one (lex {:pos 0 :buf "'(a b c)"}))

