(ns toucan2.connectable.current
  (:require [methodical.core :as m]
            [toucan2.util :as u]))

;; The only reason these are in their own namespace is to prevent circular refs.

(def ^:dynamic *current-connectable*
  "The current connectable bound by `with-connection`, if there is one; you can also bind this yourself to define the
  connectable that should be used when no explicit connectable is specified.

  Don't use this value directly; instead, call `(current-connectable)` which will fall back to `:toucan2/default` if
  nothing is bound."
  nil)

(def ^:dynamic ^java.sql.Connection *current-connection* nil)

(m/defmulti default-connectable-for-tableable*
  {:arglists '([tableableᵈᵗ options])}
  u/dispatch-on-first-arg
  :combo (m/thread-first-method-combination))

(m/defmethod default-connectable-for-tableable* :default
  [_ _]
  :toucan2/default)

(defn current-connectable
  "Return the connectable that should be used if no explicit connectable is specified:

  * If current connectable (`conn.current/*current-connectable*`) if bound, returns that

  * If `tableable` was passed, returns `default-connectable-for-tableable*` (by default `:toucan2/default`)

  * Otherwise returns `:toucan2/default`."
  ([]
   (current-connectable nil nil))

  ([tableable]
   (current-connectable tableable nil))

  ([tableable options]
   {:post [(some? %)]}
   (or *current-connectable*
       (default-connectable-for-tableable* tableable options))))

(m/defmulti default-options-for-connectable*
  {:arglists '([connectableᵈᵗ])}
  u/dispatch-on-first-arg)

(m/defmethod default-options-for-connectable* :default
  [_]
  nil)

;; default impl for `default-options-for-connectable*` is in `toucan2.connectable` to avoid circular refs.

(m/defmulti default-options-for-tableable*
  {:arglists '([connectableᵈ tableableᵈᵗ])}
  u/dispatch-on-first-two-args)

(m/defmethod default-options-for-tableable* :default
  [_ _]
  nil)

(defn ensure-connectable [connectable tableable options]
  (let [options     (u/recursive-merge
                     (default-options-for-tableable* connectable tableable)
                     options)
        connectable (or connectable (current-connectable tableable options))
        options     (u/recursive-merge
                     (default-options-for-connectable* connectable)
                     options)]
    [connectable options]))