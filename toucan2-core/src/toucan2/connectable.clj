(ns toucan2.connectable
  (:require [clojure.spec.alpha :as s]
            [methodical.core :as m]
            [toucan2.connectable.current :as conn.current]
            [toucan2.util :as u]))

(m/defmethod conn.current/default-options-for-connectable* :default
  [connectable]
  (assert (some? connectable) "connectable should be non-nil, make sure you use ensure-connection")
  nil)

(m/defmulti connection*
  {:arglists '([connectableᵈᵗ options])}
  u/dispatch-on-first-arg
  :combo (m/thread-first-method-combination))

;; TODO -- not sure if `include-connection-info-in-exceptions?` should be something in the options map or be its own
;; dynamic variable for debug purposes.

(m/defmethod connection* :around :default
  [connectable {:keys [include-connection-info-in-exceptions?]
                :or   {include-connection-info-in-exceptions? false}
                :as   options}]
  (try
    (let [m (next-method connectable options)]
      (when-not (and (map? m)
                     (contains? m :connection)
                     (contains? m :new?)
                     (let [{:keys [connection]} m]
                       (or (not connection)
                           (instance? java.lang.AutoCloseable connection))))
        (throw (ex-info "connection* should return a map with `:connection` and `:new?`."
                        {:returned m})))
      m)
    (catch Throwable e
      (throw (ex-info (format "Error creating connection: %s" (ex-message e))
                      (if include-connection-info-in-exceptions?
                        {:connectable connectable
                         :options     options}
                        {})
                      e)))))

(m/defmethod connection* :default
  [connectable options]
  (assert (some? connectable) "connectable cannot be nil")
  (assert (not= connectable :default) "connectable should not be :default. Use :toucan2/default for the default connection.")
  (when (= connectable :toucan2/default)
    (throw (ex-info (format "No default connectable is defined. Pass an explicit connectable or define an implementation of connection* for :toucan2/default")
                    {})))
  (when (keyword? connectable)
    (throw (ex-info (format "Unknown connectable %s. Did you define a connection* method for it?" connectable)
                    {:k connectable})))
  (throw (ex-info (format "Don't know how to get a connection from %s. Does it derive from :toucan2/jdbc or another connectable backend?"
                          (binding [*print-meta* true]
                            (pr-str connectable)))
                  {:connectable connectable, :options options})))

(defn connection
  ([]
   (connection (conn.current/current-connectable)))
  ([k]
   (connection* k nil))

  ([k options]
   (connection* k options)))

(defn do-with-connection [connectable tableable options f]
  (let [connectable (or connectable (conn.current/current-connectable tableable options))]
    (if (and conn.current/*current-connection*
             (= connectable conn.current/*current-connectable*))
      (f conn.current/*current-connection*)
      (let [options                                        (u/recursive-merge
                                                            (conn.current/default-options-for-connectable* connectable) options)
            {:keys [^java.lang.AutoCloseable connection new?]} (connection connectable options)]
        (binding [conn.current/*current-connectable* connectable
                  conn.current/*current-connection*  connection]
          (if new?
            (with-open [connection connection]
              (f connection))
            (f connection)))))))

;; this can't go in the specs namespace with everything else because it creates a circular reference.
(s/def ::with-connection-arg
  (s/or :connectable (complement vector?)
        :vector      (s/cat :binding     (s/? any?)
                            :connectable (s/? any?)
                            :tableable-options (s/?
                                                (s/alt
                                                 :options (s/cat :options any?)
                                                 :tableable-options (s/cat :tableable any? :options any?))))))

(defn- parse-with-connection-arg [arg]
  (let [parsed (s/conform ::with-connection-arg arg)]
    (when (= parsed :clojure.spec.alpha/invalid)
      (throw (ex-info (format "Don't know how to interpret with-connection arg: %s"
                              (s/explain-str ::with-connection-arg arg))
                      {:arg arg})))
    (let [[arg-type arg] parsed
          args (-> (if (= arg-type :connectable)
                     {:connectable arg}
                     arg)
                   (merge (-> arg :tableable-options last))
                   (dissoc :tableable-options))]
      (-> args
          (update :connectable (fn [connectable]
                                 (when (and connectable
                                            (not= connectable '_))
                                   connectable)))
          (update :binding #(or % '_))))))

;; TODO -- not sure about this syntax.
(defmacro with-connection
  {:style/indent 1
   :arglists     '([[conn-binding connectable? tableable? options?] & body]
                   [connectable & body])}
  [x & body]
  (let [{:keys [binding connectable tableable options]} (parse-with-connection-arg x)]
    `(do-with-connection
      ~connectable
      ~tableable
      ~options
      (fn [~(vary-meta (or binding '_) assoc :tag 'java.lang.AutoCloseable)]
        ~@body))))

(s/def ::connectable-tableable
  (s/or :connectable-tableable (s/cat :connectable any?
                                      :tableable   any?)
        :tableable (complement sequential?)))

(defn parse-connectable-tableable
  "Parse the `connectable-tableable` argument for various functions. Returns a tuple of `[connectable tableable]`."
  [connectable-tableable]
  (let [parsed (s/conform ::connectable-tableable connectable-tableable)]
    (when (= parsed :clojure.spec.alpha/invalid)
      (throw (ex-info (format "Don't know how to interpret connectable-tableable arg: %s"
                              (s/explain-str ::connectable-tableable connectable-tableable))
                      {:connectable-tableable connectable-tableable})))
    (let [[arg-type arg]                  parsed
          {:keys [connectable tableable]} (case arg-type
                                            :connectable-tableable arg
                                            :tableable             {:tableable arg})]
      [(or connectable
           (conn.current/current-connectable tableable))
       tableable])))

(m/defmulti do-with-transaction*
  {:arglists '([connectableᵈᵗ options f])}
  u/dispatch-on-first-arg
  :combo (m/thread-first-method-combination))

(defn do-with-transaction [connectable options f]
  (let [[connectable options] (conn.current/ensure-connectable connectable nil options)]
    (do-with-transaction* connectable options f)))

(defmacro with-transaction
  {:style/indent 1
   :arglists     '([[conn-binding connectable options] & body]
                   [connectable & body])}
  [x & body]
  (let [{:keys [binding connectable options]} (parse-with-connection-arg x)]
    `(do-with-transaction
      ~connectable
      ~options
      (fn [~(vary-meta (or binding '_) assoc :tag 'java.lang.AutoCloseable)]
        ~@body))))