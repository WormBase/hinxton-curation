(ns down.trace
  (:require
   [cemerick.friend :as friend]
   [clojure.string :as str]
   [clojure.walk :refer [postwalk]]
   [compojure.core :refer [defroutes GET POST context wrap-routes]]
   [compojure.handler :as handler]
   [compojure.route :as route]
   [datomic.api :as d]
   [down.common :refer [head identity-header]]
   [environ.core :refer [env]]
   [hiccup.core :refer [html]]
   [pseudoace.utils :refer [conj-if]]
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
   [ring.util.http-response :as resp]))

;;
;; Back-end for the TrACe tree-viewer/editor.
;;Current TrACe uses the "obj2" protocol.
;; Any references to the original "obj" protocol are vestigial.
;;

(declare touch-link)
(declare obj2)

(def ^{:dynamic true
       :doc (str "If bound to a map of :class/id -> attributes, "
                 "use those attributes as possible object labels.")}
  *class-titles* nil)

(defn class-titles-for-user
  "Return the default *class-titles* map for a user."
  [db username]
  (if username
    (->> (d/q '[:find ?class-id ?attr-id
                :in $ ?username
                :where [?user :user/name ?username]
                       [?t :wormbase.title/user ?user]
                       [?t :wormbase.title/class ?class]
                       [?t :wormbase.title/attribute ?attr]
                       [?class :db/ident ?class-id]
                       [?attr :db/ident ?attr-id]]
              db username)
         (into {}))))

(defn- object-link
  "Create a lookup-ref or labelled lookup-ref to object `v`
  of class `cls`."
  [cls v]
  (let [ref [cls (cls v)]]
    (or (some->> (get *class-titles* cls)
                 (get v)
                 (conj ref))
        ref)))

(defn touch-link-ref [ke v]
  (cond
    (:db/isComponent ke) (touch-link v)
    (:pace/obj-ref ke) (object-link (:pace/obj-ref ke) v)
    (:db/ident v) (:db/ident v)
    :default
    (if-let [class (first (filter #(= (name %) "id") (keys v)))]
      (object-link class v)
      v)))

(defn touch-link [ent]
  (let [db (d/entity-db ent)]
    (into {}
          (for [k (keys ent)
                :let [v (k ent)
                      ke (d/entity db k)]]
            [k
             (if (= (:db/valueType ke) :db.type/ref)
               (if (= (:db/cardinality ke) :db.cardinality/one)
                 (touch-link-ref ke v)
                 (set (for [i v]
                        (touch-link-ref ke i))))
               v)]))))

(defn obj2-attr [db maxcount exclude datoms]
  (let [attr (d/entity db (:a (first datoms)))
        ident (:db/ident attr)]
    (if (not (or (exclude ident)
                 (= :importer/temp ident)))
      {:key ident
       :group (if-let [tags (:pace/tags attr)]
                (if (= (namespace (:db/ident attr)) "locatable")
                  "Locatable"
                  (first (str/split tags #" ")))
                (if (= (name ident) "id")
                  (pr-str ident)
                  "Locatable"))
       :type (:db/valueType attr)
       :class (:pace/obj-ref attr)
       :comp (or (:db/isComponent attr) false)
       :count (count datoms)
       :values
       (if (or (not maxcount)
               (< (count datoms) maxcount))
         (for [d datoms]
           {:txn (:tx d)
            :id (if (:db/isComponent attr)
                  (str (:v d)))
            :val (cond
                   (:db/isComponent attr)
                   (obj2 db (:v d) maxcount)
                   (= (:db/valueType attr) :db.type/ref)
                   (touch-link-ref attr (d/entity db (:v d)))
                   :default
                   (:v d))}))})))


(defn obj2
  ([db ent maxcount]
   (obj2 db ent maxcount #{}))
  ([db ent maxcount exclude]
   (->> (d/datoms db :eavt ent)
        (seq)
        (sort-by :a)
        (partition-by :a)
        (map (partial obj2-attr db maxcount exclude))
        (filter identity))))

(defn- xref-component-parent [db ent obj-ref]
  (let [[e a v t] (first (d/datoms db :vaet ent))
        attrent (d/entity db a)
        attr (:db/ident attrent)
        attr-ns (namespace attr)
        attr-name (name attr)
        revattr (keyword attr-ns (str "_" attr-name))
        ent (d/entity db e)]
    ;; if no parent, we'll return nil
    (cond
      (obj-ref ent)
      {:key     attr
       :type    :db.type/ref
       :comp    false
       :count   1
       :values [{:txn t
                 :val (object-link obj-ref ent)}]}

      ent
      (xref-component-parent db e obj-ref))))

(defn xref-obj2-attr [db ent xref maxcount]
  (let [attr (:pace.xref/attribute xref)
        obj-ref (:pace.xref/obj-ref xref)
        attr-ns (namespace attr)
        attr-name (name attr)
        comp? (not= attr-ns (namespace obj-ref))
        revattr (keyword attr-ns
                         (str "_" attr-name))
        val-datoms (seq (d/datoms db :vaet ent attr))]
    (when (and val-datoms
               (not (.startsWith attr-ns "2")))
      {:key revattr
       :group "XREFs"
       :type :db.type/ref
       :comp comp?
       :count (count val-datoms)
       :values
       (if (or (not maxcount)
               (< (count val-datoms) maxcount))
         (for [[val _ _ txn] val-datoms]
           {:txn txn
            :val (if comp?
                   (conj-if (obj2 db val maxcount #{attr})
                            (xref-component-parent db val obj-ref))
                   (object-link obj-ref (d/entity db val)))
            }))})))

(defn xref-obj2
  "Make obj2-format records of all inbound attributes to `ent`."
  [db clid ent maxcount]
  (for [xref (:pace/xref (d/entity db clid))
        :let [vm         (xref-obj2-attr db ent xref maxcount)]
        :when vm]
    vm))

(defn find-txids [props]
  (mapcat
   (fn [{:keys [key values comp]}]
     (mapcat
      (fn [v]
        (let [txn [(:txn v)]]
          (if comp
            (concat txn (find-txids (:val v)))
            txn)))
      values))
   props))

(defn get-raw-txns [db txids]
  (for [t txids
        :let [te (as-> (d/entity db t) $
                   (d/touch $)
                   (into {} $)
                   (assoc $ :db/id t))]]
    (if-let [curator (:wormbase/curator te)]
      (assoc te :wormbase/curator
             {:person/id (:person/id curator)
              :person/standard-name (:person/standard-name curator)})
      te)))

(defn get-raw-obj2 [db cls id max-out max-in txns?]
  (binding [*class-titles*
            (class-titles-for-user
             db
             (:username (friend/current-authentication)))]
    (let [cls-id  (keyword cls "id")
          entity (d/entity db [cls-id id])
          entid (:db/id entity)]
      (if entid
        (let [props (concat (obj2 db entid max-out #{cls-id})
                            (xref-obj2 db cls-id entid max-in))
              txids (set (find-txids props))]
          {:status 200
           :headers {"Content-Type" "text/plain"}
           :body (pr-str {:props props
                          :id (str entid)
                          :txns (if txns?
                                  (get-raw-txns db txids))})})
        {:status 404
         :body "Not found"}))))

(defn get-raw-attr2-out [db entid attr txns?]
  (let [prop (obj2-attr
              db
              nil
              nil
              (seq (d/datoms db :eavt entid attr)))
        txids (set (find-txids [prop]))]
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (pr-str (assoc
                    prop
                    :txns (if txns? (get-raw-txns db txids))))}))

(defn get-raw-attr2-in [db entid attr txns?]
  (let [xref (d/entity db (d/q '[:find ?x .
                                  :in $ ?a
                                  :where [?x :pace.xref/attribute ?a]]
                                db attr))
        prop (xref-obj2-attr db entid attr nil)
        txids (set (find-txids [prop]))]
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (pr-str (assoc
                    prop
                    :txns (if txns? (get-raw-txns db txids))))}))

(defn get-raw-attr2 [db entid attr-name txns?]
  (binding [*class-titles* (class-titles-for-user
                            db
                            (:username (friend/current-authentication)))]
    (let [attr (keyword (.substring attr-name 1))]
      (if (.startsWith (name attr) "_")
        (get-raw-attr2-in db entid (keyword (namespace attr)
                                             (.substring (name attr) 1))
                          txns?)
        (get-raw-attr2-out db entid attr txns?)))))

(defn get-raw-history2 [db entid attr]
  (let [hdb    (d/history db)
        schema (d/entity db attr)
        valmap (cond
                 (:pace/obj-ref schema)
                 (comp (:pace/obj-ref schema) (partial d/entity db))

                 (:db/isComponent schema)
                 identity

                 (= (:db/valueType schema) :db.type/ref)
                 (fn [eid]
                   (let [e (d/entity db eid)]
                     (if-let [ident (:db/ident e)]
                       (name ident)
                       eid)))

                 :default
                 identity)
        datoms (sort-by :tx (seq (d/datoms hdb :eavt entid attr)))]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str {:datoms (map (fn [[e a v tx a]]
                                   {:e e
                                    :a a
                                    :v (valmap v)
                                    :txid tx
                                    :added? a})
                                 datoms)
                    :endid entid
                    :attr attr
                    :txns (get-raw-txns db (set (map :tx datoms)))})}))

(defn get-transaction-notes [db id]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (d/q '[:find ?d .
                :in $ ?tx
                :where [?tx :db/doc ?d]]
              db id)})


(defn get-raw-ent [db id]
  {:status 200
   :headers {"Content-Type" "application/edn"}
   :body (pr-str (d/touch (d/entity db id)))})

(def cljs-symbol (re-pattern "^[:]?([^0-9/]*/)?([^0-9/][^/]*)$"))

(defn get-schema-classes [db]
  (->> (d/q '[:find ?cid
              :where [?cid :pace/identifies-class _]]
            db)
       (map (fn [[cid]]
              (let [ent (into {} (d/touch (d/entity db cid)))]
                (assoc
                 ent
                 :pace/xref
                 (for [x (:pace/xref ent)
                       :let [x (d/touch x)]]
                   x)))))))

(defn get-schema-attributes [db]
  (->> (d/q '[:find ?attr
              :where [?attr :pace/tags _]]
            db)
       (map (fn [[attr]]
              (d/touch (d/entity db attr))))))

(defn get-schema [db]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (pr-str {:classes    (get-schema-classes db)
                  :attributes (get-schema-attributes db)})})

(defn viewer-page [{:keys [db] :as req}]
  (html
   [:html
    head
    [:body#trace
     [:div.root
      [:div.header
       (identity-header db)
       [:div.header-main
        [:h1#page-title "TrACeView"]
        [:div#header-content]]]
      [:div.container-fluid
       [:div#tree]]
      [:script {:type "text/javascript" :src "/compiled/js/site.min.js"}]
      [:script {:type "text/javascript"}
       (str "/* " (friend/current-authentication req) " */")
       (if-let [id (friend/identity req)]
         (if (:wbperson (friend/current-authentication req))
           (str "trace_logged_in = '" (:current id) "';")
           "trace_logged_in = null;")
         "trace_logged_in = null;")
       (str "var csrf_token = '" *anti-forgery-token* "';\n")
       "thomas.core.init_trace();"]]]]))

(defn- id-report [db datoms]
  (for [d datoms
        :let [i (:db/ident (d/entity db (.a d)))]
        :when (= (name i) "id")]
    (.v d)))

(defn transact [{:keys [edn-params con] :as req}]
  (try
    (let [txr
          @(d/transact
            con
            (conj (postwalk
                   (fn [x]
                     (if (and (coll? x)
                              (= (count x) 2)
                              (= (first x) :db/id))
                       (let [v (second x)]
                         (if (string? v)
                           (Long/parseLong v)
                           x))
                       x))
                   (:tx edn-params))
                  {:db/id (d/tempid :db.part/tx)
                   :wormbase/curator
                   [:person/id
                    (:wbperson (friend/current-authentication req))]}))]
      {:status 200
       :body (pr-str {:status "OK"
                      :ids (id-report (:db-after txr) (:tx-data txr))})})
    (catch Exception e {:status 500
                        :body (.getMessage e)})))

(defn get-prefix-search [db cls prefix]
  (let [names (->> (d/seek-datoms db :avet (keyword cls "id") prefix)
                   (map :v)
                   (take-while (fn [^String s]
                                 (.startsWith s prefix))))]
  {:status 200
   :headers {"Content-Type" "text/plain"}  ; for now
   :body (pr-str
          {:count (count names)
           :names (take 10 names)})}))

(defn get-raw-txns2 [db ids]
  (let [txns (get-raw-txns db ids)]
    (-> (pr-str {:txns txns})
        (resp/ok)
        (resp/content-type "application/edn"))))

(defn in-transaction [log tx]
  (->> (d/tx-range log tx (inc tx))
       (mapcat :data)
       (map :e)
       (set)))
