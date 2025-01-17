(ns toucan2.select-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [honey.sql :as hsql]
   [methodical.core :as m]
   [toucan2.connection :as conn]
   [toucan2.instance :as instance]
   [toucan2.log :as log]
   [toucan2.model :as model]
   [toucan2.pipeline :as pipeline]
   [toucan2.protocols :as protocols]
   [toucan2.query :as query]
   [toucan2.realize :as realize]
   [toucan2.select :as select]
   [toucan2.test :as test]
   [toucan2.test.track-realized-columns :as test.track-realized]
   [toucan2.tools.compile :as tools.compile]
   [toucan2.tools.named-query :as tools.named-query]
   [toucan2.util :as u])
  (:import
   (java.time LocalDateTime OffsetDateTime)))

(set! *warn-on-reflection* true)

(derive ::people ::test/people)

(deftest ^:parallel parse-args-test
  (are [args expected] (= expected
                          (query/parse-args :toucan.query-type/select.* args))
    [:model 1]                               {:modelable :model, :queryable 1}
    [:model :id 1]                           {:modelable :model, :kv-args {:id 1}, :queryable {}}
    [:model {:where [:= :id 1]}]             {:modelable :model, :queryable {:where [:= :id 1]}}
    [:model :name "Cam" {:where [:= :id 1]}] {:modelable :model, :kv-args {:name "Cam"}, :queryable {:where [:= :id 1]}}
    [:model ::my-query]                      {:modelable :model, :queryable ::my-query}
    [:conn :db :model]                       {:connectable :db, :modelable :model, :queryable {}}
    [:conn :db :model ::my-query]            {:connectable :db, :modelable :model, :queryable ::my-query}
    [:conn :db :model :id 1]                 {:connectable :db, :modelable :model, :kv-args {:id 1}, :queryable {}}
    [[:model :col] :query]                   {:modelable :model, :columns [:col], :queryable :query}
    [[:model [:%max.id]] :query]             {:modelable :model, :columns [[:%max.id]], :queryable :query}
    [[:model [:%max.id :max-id]] :query]     {:modelable :model, :columns [[:%max.id :max-id]], :queryable :query}
    [[:model [[:max :id]]] :query]           {:modelable :model, :columns [[[:max :id]]], :queryable :query}
    [[:model [[:max :id] :max-id]] :query]   {:modelable :model, :columns [[[:max :id] :max-id]], :queryable :query}
    ;; Yoda condition
    [:model :id 1 "Cam" :name]               {:modelable :model, :kv-args {:id 1, "Cam" :name}, :queryable {}}))

(deftest ^:parallel default-build-query-test
  (is (= {:select [:*]
          :from   [[:default]]}
         (pipeline/build :toucan.query-type/select.* :default {} {})))
  (testing "don't override existing"
    (is (= {:select [:a :b]
            :from   [[:my_table]]}
           (pipeline/build :toucan.query-type/select.* :default {} {:select [:a :b], :from [[:my_table]]}))))
  (testing "columns"
    (is (= {:select [:default/a :default/b]
            :from   [[:default]]}
           (pipeline/build :toucan.query-type/select.* :default {:columns [:a :b]} {})))
    (testing "existing"
      (is (= {:select [:a]
              :from   [[:default]]}
             (pipeline/build :toucan.query-type/select.* :default {:columns [:a :b]} {:select [:a]})))))
  (testing "conditions"
    (is (= {:select [:*]
            :from   [[:default]]
            :where  [:= :id 1]}
           (pipeline/build :toucan.query-type/select.* :default {:kv-args {:id 1}} {})))
    (testing "merge with existing"
      (is (= {:select [:*]
              :from   [[:default]]
              :where  [:and [:= :a :b] [:= :id 1]]}
             (pipeline/build :toucan.query-type/select.* :default {:kv-args {:id 1}} {:where [:= :a :b]}))))))

(deftest ^:parallel build-query-dont-override-union-test
  (testing "Honey SQL backend of build-query should not splice in defaults when `:union` or `:union-all` are in the query"
    (are [query] (= query
                    (pipeline/build :toucan.query-type/select.* :default {} query))
      {:union []}
      {:union-all []})))

(m/defmethod query/apply-kv-arg [#_model :default #_query :toucan.map-backend/honeysql2 #_k ::custom.limit]
  [_model honeysql-form _k limit]
  (assoc honeysql-form :limit limit))

(deftest ^:parallel custom-condition-test
  (is (= {:select [:*]
          :from   [[:default]]
          :limit  100}
         (pipeline/build :toucan.query-type/select.* :default {:kv-args {::custom.limit 100}} {})
         (pipeline/build :toucan.query-type/select.* :default {:kv-args {::custom.limit 100}} {:limit 1}))))

(deftest ^:parallel built-in-pk-condition-test
  (is (= {:select [:*], :from [[:default]], :where [:= :id 1]}
         (pipeline/build :toucan.query-type/select.* :default {:kv-args {:toucan/pk 1}} {})))
  (is (= {:select [:*], :from [[:default]], :where [:and
                                                    [:= :name "Cam"]
                                                    [:= :id 1]]}
         (pipeline/build :toucan.query-type/select.* :default {:kv-args {:toucan/pk 1}} {:where [:= :name "Cam"]}))))

(deftest ^:parallel select-test
  (let [expected [(instance/instance ::test/people {:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56Z")})]]
    (testing "plain SQL"
      (is (= expected
             (select/select ::test/people "SELECT * FROM people WHERE id = 1;"))))
    (testing "sql-args"
      (is (= expected
             (select/select ::test/people ["SELECT * FROM people WHERE id = ?;" 1]))))
    (testing "HoneySQL"
      (is (= expected
             (select/select ::test/people {:select [:*], :from [[:people]], :where [:= :id 1]})))
      (testing "Existing :select"
        (is (= [{:id 1, :name "Cam"}]
               (select/select ::test/people {:select [:id :name], :from [[:people]], :where [:= :id 1]})))
        (is (= [{:id 1, :name "Cam"}]
               (select/select ::test/people {:select-distinct [:id :name], :from [[:people]], :where [:= :id 1]})))))
    (testing "PK"
      (is (= expected
             (select/select ::test/people 1))))
    (testing "conditions"
      (is (= expected
             (select/select ::test/people :id 1))))
    (testing "columns"
      (is (= [(instance/instance ::test/people {:id 1})]
             (select/select [::test/people :id] :id 1)))
      (testing "With alias"
        (is (= [(instance/instance ::test/people {:person-id 1})]
               (select/select [::test/people [:id :person-id]] :id 1))))
      (testing "Honey SQL expression"
        (doseq [expr [:%max.id
                      [:max :id]]]
          (testing expr
            (testing "with alias"
              (is (= [(instance/instance ::test/people {:person-id 4})]
                     (select/select [::test/people [expr :person-id]]))))
            (testing "no alias"
              (let [expected-key (case (test/current-db-type)
                                   :h2       (keyword "max(-id)")
                                   :postgres :max
                                   :mariadb  (keyword "max(`id`)"))]
                (is (= [(instance/instance ::test/people {expected-key 4})]
                       (select/select [::test/people [expr]]))))))))
      (testing "[expr identifier]"
        (is (= [(instance/instance ::test/people {:person-number 1})]
               (select/select [::test/people [:id :person-number]] :id 1)))))))

(deftest ^:parallel select-test-2
  (let [all-rows [{:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56:00Z")}
                  {:id 2, :name "Sam", :created-at (OffsetDateTime/parse "2019-01-11T23:56:00Z")}
                  {:id 3, :name "Pam", :created-at (OffsetDateTime/parse "2020-01-01T21:56:00Z")}
                  {:id 4, :name "Tam", :created-at (OffsetDateTime/parse "2020-05-25T19:56:00Z")}]]
    (testing "no args"
      (is (= all-rows
             (sort-by :id (select/select ::people))))
      (is (= all-rows
             (sort-by :id (select/select ::test/people))))
      (is (every? (partial instance/instance-of? ::test/people)
                  (select/select ::test/people {:order-by [[:id :asc]]})))))
  (testing "one arg (id)"
    (is (= [{:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56:00Z")}]
           (select/select ::people 1))))
  (testing "one arg (query)"
    (is (= [{:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56:00Z")}]
           (select/select ::test/people {:where [:= :id 1]})))
    (is (= [{:id 1, :name "Tempest", :category "bar"}]
           (select/select ::test/venues {:select [:id :name :category], :limit 1, :where [:= :id 1]}))))
  (testing "two args (k v)"
    (is (= [{:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56:00Z")}]
           (select/select ::test/people :id 1)))
    (testing "sequential v"
      (is (= [{:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56:00Z")}]
             (select/select ::test/people :id [:= 1])))))
  (testing "k v conditions + query"
    (is (= [(instance/instance ::test/venues {:id 3, :name "BevMo"})]
           (select/select ::test/venues :id [:>= 3] {:select [:id :name], :order-by [[:id :asc]]})))))

;;; don't actually use this in real life since it doesn't guard against SQL injection.
(hsql/register-fn! ::literal (fn [_literal [s]]
                               [(str \' s \')]))

(deftest ^:parallel weird-selects-test
  (testing "Yoda conditions"
    (is (= (select/select ::test/people :id 1 :name "Cam")
           (select/select ::test/people :id 1 "Cam" :name))))
  (testing "arbitrary Honey SQL"
    (is (= (select/select ::test/people :id 1 :name "Cam")
           (select/select ::test/people :id 1 :name [:= [::literal "Cam"]]))))
  (testing "arbitrary Honey SQL × Yoda conditions"
    (is (= (select/select ::test/people :id 1 :name [:= [::literal "Cam"]])
           (select/select ::test/people :id 1 [::literal "Cam"] :name)))))

(tools.named-query/define-named-query ::count-query
  {:select [[:%count.* :count]]
   :from   [(keyword (model/table-name &model))]})

(deftest ^:parallel named-query-test
  (testing "venues"
    (is (= [{:count 3}]
           (select/select ::test/venues ::count-query))))
  (testing "people"
    (is (= [{:count 4}]
           (select/select ::test/people ::count-query))))
  (testing "with additional conditions"
    (is (= [{:count 1}]
           (select/select ::test/venues :id 1 ::count-query)))))

(deftest ^:parallel reducible-select-test
  (is (= ["Cam" "Sam" "Pam" "Tam"]
         (into [] (map :name) (select/reducible-select ::test/people {:order-by [[:id :asc]]}))))
  (are [form] (= [{:id         1
                   :name       "Cam"
                   :created-at (OffsetDateTime/parse "2020-04-21T23:56Z")}
                  {:id         2
                   :name       "Sam"
                   :created-at (OffsetDateTime/parse "2019-01-11T23:56Z")}
                  {:id         3
                   :name       "Pam"
                   :created-at (OffsetDateTime/parse "2020-01-01T21:56Z")}
                  {:id         4
                   :name       "Tam"
                   :created-at (OffsetDateTime/parse "2020-05-25T19:56Z")}]
                 form)
    (into [] (map realize/realize) (select/reducible-select ::test/people {:order-by [[:id :asc]]}))
    (into [] (eduction
              (map realize/realize)
              (select/reducible-select ::test/people {:order-by [[:id :asc]]})))
    (transduce
     (map identity)
     conj
     (eduction
      (map realize/realize)
      (select/reducible-select ::test/people {:order-by [[:id :asc]]})))))

(derive ::people.name-is-pk ::people)

(m/defmethod model/primary-keys ::people.name-is-pk
  [_model]
  :name)

(derive ::people.composite-pk ::people)

(m/defmethod model/primary-keys ::people.composite-pk
  [_model]
  [:id :name])

(deftest ^:parallel select-non-integer-pks-test
  (testing "non-integer PK"
    (is (= [{:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56:00Z")}]
           (select/select ::people.name-is-pk :toucan/pk "Cam"))))

  (testing "composite PK"
    (is (= [{:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56:00Z")}]
           (select/select ::people.composite-pk :toucan/pk [1 "Cam"])))
    (is (= []
           (select/select ::people.composite-pk :toucan/pk [2 "Cam"])
           (select/select ::people.composite-pk :toucan/pk [1 "Sam"])))))

(derive ::people.no-timestamps ::people)

(m/defmethod pipeline/build [#_query-type     :toucan.query-type/select.*
                             #_model          ::people.no-timestamps
                             #_resolved-query :default]
  [query-type model parsed-args resolved-query]
  (let [parsed-args (update parsed-args :columns (fn [columns]
                                                   (or columns [:id :name])))]
    (next-method query-type model parsed-args resolved-query)))

(m/defmethod pipeline/results-transform [#_query-type :toucan.query-type/select.instances
                                         #_model      ::people.no-timestamps]
  [query-type model]
  (comp (map (fn [person]
               (testing (format "\nperson = ^%s %s" (some-> person class .getCanonicalName) (pr-str person))
                 ;; (testing "\nreducing function should see Toucan 2 instances"
                 ;;   (is (instance/instance? person)))
                 (testing "\ninstance table should be a ::people.no-timestamps"
                   (is (isa? (protocols/model person) ::people.no-timestamps))))
               (assoc person :after-select? true)))
        (next-method query-type model)))

(deftest ^:parallel default-query-test
  (testing "Should be able to set some defaults by implementing transduce-with-model"
    (is (= [(instance/instance ::people.no-timestamps {:id 1, :name "Cam", :after-select? true})]
           (select/select ::people.no-timestamps 1)))))

(deftest ^:parallel post-select-test
  (testing "Should be able to do cool stuff in reducing function"
    (testing (str \newline '(ancestors ::people.no-timestamps) " => " (pr-str (ancestors ::people.no-timestamps)))
      (is (= [(instance/instance ::people.no-timestamps {:id 1, :name "Cam", :after-select? true})
              (instance/instance ::people.no-timestamps {:id 2, :name "Sam", :after-select? true})
              (instance/instance ::people.no-timestamps {:id 3, :name "Pam", :after-select? true})
              (instance/instance ::people.no-timestamps {:id 4, :name "Tam", :after-select? true})]
             (select/select ::people.no-timestamps {:order-by [[:id :asc]]}))))))

(derive ::people.limit-2 ::people)

;; TODO this is probably not the way you'd want to accomplish this in real life -- I think you'd probably actually want
;; to implement [[toucan2.pipeline/build]] instead. But it does do a good job of letting us test that combining aux
;; methods work like we'd expect.
(m/defmethod pipeline/compile :before [#_query-type :toucan.query-type/select.* #_model ::people.limit-2 #_query :toucan.map-backend/honeysql2]
  [_query-type _model built-query]
  (assoc built-query :limit 2))

(deftest ^:parallel pre-select-test
  (testing "Should be able to do cool stuff in pre-select (select* :before)"
    (is (= [(instance/instance ::people.limit-2 {:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56Z")})
            (instance/instance ::people.limit-2 {:id 2, :name "Sam", :created-at (OffsetDateTime/parse "2019-01-11T23:56Z")})]
           (select/select ::people.limit-2)))))

(derive ::people.no-timestamps-limit-2 ::people.no-timestamps)
(derive ::people.no-timestamps-limit-2 ::people.limit-2)

(deftest ^:parallel combine-aux-methods-test
  (testing (str \newline '(ancestors ::people.no-timestamps-limit-2) " => " (pr-str (ancestors ::people.no-timestamps-limit-2)))
    (is (= [(instance/instance ::people.no-timestamps-limit-2 {:id 1, :name "Cam", :after-select? true})
            (instance/instance ::people.no-timestamps-limit-2 {:id 2, :name "Sam", :after-select? true})]
           (select/select ::people.no-timestamps-limit-2)))))

(deftest ^:parallel select-one-test
  (is (= (instance/instance ::test/people {:id 1, :name "Cam", :created-at (OffsetDateTime/parse "2020-04-21T23:56Z")})
         (select/select-one ::test/people 1)))
  (is (= nil
         (select/select-one ::test/people :id 1000))))

;;; TODO -- a test to make sure this doesn't fetch a second row even if query would return multiple rows. A test with a
;;; SQL query.

(deftest ^:parallel select-fn-test
  (testing "Equivalent of Toucan select-field"
    (is (= #{1 2 3 4}
           (select/select-fn-set :id ::test/people))))
  (testing "Return vector instead of a set"
    (is (= [1 2 3 4]
           (select/select-fn-vec :id ::test/people {:order-by [[:id :asc]]}))))
  (testing "Arbitrary function instead of a key"
    (is (= [2 3 4 5]
           (select/select-fn-vec (comp inc :id) ::test/people {:order-by [[:id :asc]]}))))
  (testing "Should work with magical keys"
    (are [k] (= [(OffsetDateTime/parse "2020-04-21T23:56Z")
                 (OffsetDateTime/parse "2019-01-11T23:56Z")
                 (OffsetDateTime/parse "2020-01-01T21:56Z")
                 (OffsetDateTime/parse "2020-05-25T19:56Z")]
                (select/select-fn-vec k ::test/people {:order-by [[:id :asc]]}))
      :created-at
      :created_at))
  (testing "Should return nil if the result is empty"
    (is (nil? (select/select-fn-set :id ::test/people :id 100)))
    (is (nil? (select/select-fn-vec :id ::test/people :id 100))))
  (testing "Only realize the columns we actually fetch."
    (testing "simple function"
      (test.track-realized/with-realized-columns [realized-columns]
        (is (= #{1 2 3}
               (select/select-fn-set :id ::test.track-realized/venues)))
        (is (= #{:venues/id}
               (realized-columns)))))
    (testing `juxt
      (test.track-realized/with-realized-columns [realized-columns]
        (is (= #{[1 "Tempest"]
                 [2 "Ho's Tavern"]
                 [3 "BevMo"]}
               (select/select-fn-set (juxt :id :name) ::test.track-realized/venues)))
        (is (= #{:venues/id :venues/name}
               (realized-columns)))))
    (testing `comp
      (test.track-realized/with-realized-columns [realized-columns]
        (is (= #{0 1 2}
               (select/select-fn-set (comp dec :id) ::test.track-realized/venues)))
        (is (= #{:venues/id}
               (realized-columns)))))
    (testing "fancy function"
      (test.track-realized/with-realized-columns [realized-columns]
        (is (= #{{:id 1, :x true} {:id 2, :x true} {:id 3, :x true}}
               (select/select-fn-set (fn [m]
                                       (-> (select-keys m [:id])
                                           (assoc :x true)))
                                     ::test.track-realized/venues)))
        (is (= #{:venues/id}
               (realized-columns)))))))

(deftest ^:parallel select-one-fn-test
  (is (= 1
         (select/select-one-fn :id ::test/people :name "Cam"))))

(deftest ^:parallel select-pks-test
  (is (= #{1 2 3 4}
         (select/select-pks-set ::test/people)))
  (is (= [1 2 3 4]
         (select/select-pks-vec ::test/people)))
  (testing "non-integer PK"
    (is (= #{"Cam" "Sam" "Pam" "Tam"}
           (select/select-pks-set ::people.name-is-pk)))
    (is (= ["Cam" "Sam" "Pam" "Tam"]
           (select/select-pks-vec ::people.name-is-pk))))
  (testing "Composite PK -- should return vectors"
    (is (= #{[1 "Cam"] [2 "Sam"] [3 "Pam"] [4 "Tam"]}
           (select/select-pks-set ::people.composite-pk)))
    (is (= [[1 "Cam"] [2 "Sam"] [3 "Pam"] [4 "Tam"]]
           (select/select-pks-vec ::people.composite-pk))))
  (testing "Should return nil if the result is empty"
    (is (nil? (select/select-pks-set ::test/people :id 100)))
    (is (nil? (select/select-pks-vec ::test/people :id 100)))))

(deftest ^:parallel select-one-pk-test
  (is (= 1
         (select/select-one-pk ::test/people :name "Cam")))
  (testing "non-integer PK"
    (is (= "Cam"
           (select/select-one-pk ::people.name-is-pk :id 1))))
  (testing "Composite PK -- should return vector"
    (is (= [1 "Cam"]
           (select/select-one-pk ::people.composite-pk :id 1)))))

(deftest ^:parallel select-fn->fn-test
  (is (= {1 "Cam", 2 "Sam", 3 "Pam", 4 "Tam"}
         (select/select-fn->fn :id :name ::test/people)))
  (is (= {2 "cam", 3 "sam", 4 "pam", 5 "tam"}
         (select/select-fn->fn (comp inc :id) (comp u/lower-case-en :name) ::test/people)))
  (testing "Should return empty map if the result is empty"
    (is (= {}
           (select/select-fn->fn :id :name ::test/people :id 100)))))

(deftest ^:parallel select-pk->fn-test
  (is (= {1 "Cam", 2 "Sam", 3 "Pam", 4 "Tam"}
         (select/select-pk->fn :name ::test/people)))
  (is (= {1 "cam", 2 "sam", 3 "pam", 4 "tam"}
         (select/select-pk->fn (comp u/lower-case-en :name) ::test/people)))
  (testing "Composite PKs"
    (is (= {[1 "Cam"] "Cam", [2 "Sam"] "Sam", [3 "Pam"] "Pam", [4 "Tam"] "Tam"}
           (select/select-pk->fn :name ::people.composite-pk))))
  (testing "Should return empty map if the result is empty"
    (is (= {}
           (select/select-pk->fn :name ::test/people :id 100)))))

(deftest ^:parallel select-fn->pk-test
  (is (= {"Cam" 1, "Sam" 2, "Pam" 3, "Tam" 4}
         (select/select-fn->pk :name ::test/people)))
  (is (= {"cam" 1, "sam" 2, "pam" 3, "tam" 4}
         (select/select-fn->pk (comp u/lower-case-en :name) ::test/people)))
  (testing "Composite PKs"
    (is (= {"Cam" [1 "Cam"], "Sam" [2 "Sam"], "Pam" [3 "Pam"], "Tam" [4 "Tam"]}
  (testing "Should return empty map if the result is empty"
           (select/select-fn->pk :name ::people.composite-pk))))
    (is (= {}
           (select/select-fn->pk :name ::people.composite-pk :id 100)))))

(deftest ^:parallel count-test
  (is (= 4
         (select/count ::test/people)))
  (is (= 1
         (select/count ::test/people 1)))
  (is (= 3
         (select/count ::test/venues)))
  (is (= 2
         (select/count ::test/venues :category "bar")))
  (testing "Should build an efficient query"
    (is (= (case (test/current-db-type)
             :mariadb  ["SELECT COUNT(*) AS `count` FROM `venues` WHERE `id` = ?" 1]
             :postgres ["SELECT COUNT(*) AS \"count\" FROM \"venues\" WHERE \"id\" = ?" 1]
             :h2       ["SELECT COUNT(*) AS \"COUNT\" FROM \"VENUES\" WHERE \"ID\" = ?" 1])
           (tools.compile/compile
             (select/count ::test/venues 1)))))
  (testing "Should be possible to do count with a raw SQL query"
    (is (= 3
           (select/count ::test/venues ["SELECT count(*) AS count FROM venues;"])))
    (testing "should not log a warning, since this query returns :count"
      (let [s (with-out-str (binding [log/*level* :warn]
                              (select/count ::test/venues ["SELECT count(*) AS count FROM venues;"])))]
        (is (not (str/includes? s "inefficient count query")))))
    (testing "Query that returns multiple rows"
      (is (= 3
             (select/count ::test/venues ["(SELECT 1 AS count) UNION ALL (SELECT 2 AS count);"]))))
    (testing "(inefficient query)"
      (is (= 3
             (select/count ::test/venues ["SELECT * FROM venues;"])))
      (testing "should log a warning"
        (let [s (with-out-str (binding [log/*level* :warn]
                                (select/count ::test/venues ["SELECT * FROM venues;"])))]
          (is (str/includes? s "inefficient count query. See documentation for toucan2.select/count."))))))
  (testing "Hairy query"
    (is (= 2
           (select/count ::test/venues :category [:not= "saloon"]
                         {:where [:not= :name "BevMo"]
                          :limit 1})))))

(deftest ^:parallel exists?-test
  (is (true? (select/exists? ::test/people :name "Cam")))
  (is (false? (select/exists? ::test/people :name "Cam Era")))
  (testing "Should build an efficient query"
    (is (= (case (test/current-db-type)
             :mariadb  ["SELECT EXISTS (SELECT 1 FROM `venues` WHERE `id` = ?) AS `exists`" 1]
             :postgres ["SELECT EXISTS (SELECT 1 FROM \"venues\" WHERE \"id\" = ?) AS \"exists\"" 1]
             :h2       ["SELECT EXISTS (SELECT 1 FROM \"VENUES\" WHERE \"ID\" = ?) AS \"EXISTS\"" 1])
           (tools.compile/compile
             (select/exists? ::test/venues 1)))))
  (testing "Should be possible to do count with a raw SQL query"
    (let [exists-identifier (case (test/current-db-type)
                              :mariadb        "`exists`"
                              (:h2 :postgres) "\"exists\"")]
      (is (true? (select/exists? ::test/venues  [(format "SELECT exists(SELECT 1 FROM venues WHERE id = 1) AS %s;"
                                                         exists-identifier)])))
      (is (false? (select/exists? ::test/venues  [(format "SELECT exists(SELECT 1 FROM venues WHERE id < 1) AS %s;"
                                                          exists-identifier)])))
      (testing "query that returns an integer"
        (is (true? (select/exists? ::test/venues  [(format "SELECT 1 AS %s;" exists-identifier)])))
        (is (false? (select/exists? ::test/venues [(format "SELECT 0 AS %s;" exists-identifier)]))))
      (testing "query that returns multiple rows"
        (is (true? (select/exists? ::test/venues  [(format "(SELECT false AS %s) UNION ALL (SELECT true AS %s);"
                                                           exists-identifier
                                                           exists-identifier)])))
        (is (false? (select/exists? ::test/venues  [(format "(SELECT false AS %s) UNION ALL (SELECT false AS %s);"
                                                            exists-identifier
                                                            exists-identifier)]))))
      (testing "should not log a warning, since this query returns :exists"
        (let [s (with-out-str
                  (binding [log/*level* :warn]
                    (select/exists? ::test/venues [(format "SELECT exists(SELECT 1 FROM venues WHERE id = 1) AS %s;"
                                                           exists-identifier)])
                    (select/exists? ::test/venues [(format "SELECT exists(SELECT 1 FROM venues WHERE id < 1) AS %s;"
                                                           exists-identifier)])))]
          (is (not (str/includes? s "inefficient exists? query"))))))
    (testing "(inefficient query)"
      (is (true? (select/exists? ::test/venues ["SELECT * FROM venues;"])))
      (is (true? (select/exists? ::test/venues ["SELECT * FROM venues LIMIT 1;"])))
      (is (false? (select/exists? ::test/venues ["SELECT * FROM venues WHERE id < 1;"])))
      (is (false? (select/exists? ::test/venues ["SELECT * FROM venues LIMIT 0;"])))
      (testing "should log a warning"
        (let [s (with-out-str (binding [log/*level* :warn]
                                (select/exists? ::test/venues ["SELECT * FROM venues;"])))]
          (is (str/includes? s "inefficient exists? query. See documentation for toucan2.select/exists?.")))))))

(deftest ^:parallel dont-add-from-if-it-already-exists-test
  (testing "Select shouldn't add a :from clause if one is passed in explicitly already"
    (is (= (instance/instance ::test/people {:id 1})
           (select/select-one ::test/people {:select [:p.id], :from [[:people :p]], :where [:= :p.id 1]})))
    (is (= [(case (test/current-db-type)
              :h2       "SELECT \"P\".\"ID\" FROM \"PEOPLE\" AS \"P\" WHERE \"P\".\"ID\" = ?"
              :postgres "SELECT \"p\".\"id\" FROM \"people\" AS \"p\" WHERE \"p\".\"id\" = ?"
              :mariadb  "SELECT `p`.`id` FROM `people` AS `p` WHERE `p`.`id` = ?")
            1]
           (tools.compile/compile
             (select/select-one :people {:select [:p.id], :from [[:people :p]], :where [:= :p.id 1]}))))))

(deftest ^:parallel select-nil-test
  (testing "(select model nil) should basically be the same as (select model :toucan/pk nil)"
    (let [parsed-args (query/parse-args :toucan.query-type/select.* [::test/venues nil])]
      (is (= {:modelable ::test/venues, :queryable nil}
             parsed-args))
      (let [query (pipeline/resolve :toucan.query-type/select.* ::test/venues (:queryable parsed-args))]
        (is (= nil
               query))
        (is (= {:select [:*], :from [[:venues]], :where [:= :id nil]}
               (pipeline/build :toucan.query-type/select.* ::test/venues parsed-args query)))))
    (is (= [(case (test/current-db-type)
              :h2       "SELECT * FROM \"VENUES\" WHERE \"ID\" IS NULL"
              :postgres "SELECT * FROM \"venues\" WHERE \"id\" IS NULL"
              :mariadb  "SELECT * FROM `venues` WHERE `id` IS NULL")]
           (tools.compile/compile
             (select/select ::test/venues nil))))
    (is (= []
           (select/select ::test/venues nil)))
    (is (= nil
           (select/select-one ::test/venues nil)
           (select/select-one-fn :id ::test/venues nil)
           (select/select-one-fn int ::test/venues nil)))))

(deftest ^:parallel select-join-test
  (testing "Extra columns from joined tables should come back"
    ;; we should fetch `venues.name` first and skip fetching `category.name` since we already have a `:name`
    (is (= (instance/instance ::test/venues
                              {:id              1
                               :name            "Tempest"
                               :category        "bar"
                               :created-at      (LocalDateTime/parse "2017-01-01T00:00")
                               :updated-at      (LocalDateTime/parse "2017-01-01T00:00")
                               :slug            "bar_01"
                               :parent-category nil})
           (select/select-one ::test/venues
                              {:left-join [[:category :c] [:= :venues.category :c.name]]
                               :order-by  [[:id :asc]]})))))

(deftest ^:parallel automatically-qualifiy-model-fields-test
  (testing "the fields in [model & fields] forms should get automatically qualified if not already qualified"
    (let [built (atom [])]
      (binding [pipeline/*build* (comp (fn [result]
                                         (swap! built conj result)
                                         result)
                                       pipeline/*build*)]
        ;; the ID we're getting back in the results is ambiguous, but it doesn't really matter here I guess.
        (is (= {:id 1}
               (select/select-one [::test/venues :id :people.id]
                                  {:left-join [:people [:= :venues.id :people.id]]
                                   :order-by  [[:venues.id :asc]]})))
        (is (= [{:select    [:venues/id :people.id]
                 :from      [[:venues]]
                 :left-join [:people [:= :venues.id :people.id]]
                 :order-by  [[:venues.id :asc]]}]
               @built))))))

(derive ::venues.with-category ::test.track-realized/venues)

(m/defmethod pipeline/build [#_query-type :toucan.query-type/select.*
                             #_model      ::venues.with-category
                             #_query      :toucan.map-backend/honeysql2]
  [query-type model parsed-args resolved-query]
  (let [model-ns-str    (some-> (model/namespace model) name)
        venues-category (keyword
                         (str
                          (when model-ns-str
                            (str model-ns-str \.))
                          "category"))
        resolved-query  (assoc resolved-query :left-join [:category [:= venues-category :category.name]])]
    (next-method query-type model parsed-args resolved-query)))

(deftest ^:parallel joined-model-test
  (test.track-realized/with-realized-columns [realized-columns]
    (is (= (instance/instance ::venues.with-category
                              {:id              1
                               :name            "Tempest"
                               :category        "bar"
                               :created-at      (LocalDateTime/parse "2017-01-01T00:00")
                               :updated-at      (LocalDateTime/parse "2017-01-01T00:00")
                               :slug            "bar_01"
                               :parent-category nil})
           (select/select-one ::venues.with-category
                              {:order-by [[:id :asc]]})))
    (testing "We should fetch venues.name first, and skip fetching category.name entirely since we already have a `:name`"
      (is (= #{:venues/id
               :venues/name
               :venues/category
               :venues/created-at
               :venues/updated-at
               :category/slug
               :category/parent-category}
             (realized-columns))))))

(derive ::venues.namespaced ::test/venues)

(m/defmethod model/model->namespace ::venues.namespaced
  [_model]
  {::venues.namespaced :venue})

(deftest ^:parallel namespaced-test
  (is (= {"venues" :venue}
         (model/table-name->namespace ::venues.namespaced)))
  (is (= {:select    [:*]
          :from      [[:venues :venue]]
          :order-by  [[:id :asc]]}
         (tools.compile/build
           (select/select-one ::venues.namespaced {:order-by [[:id :asc]]}))))
  (testing "When selecting a model with a namespace, keys should come back in that namespace."
    (is (= (instance/instance ::venues.namespaced
                              {:venue/id         1
                               :venue/name       "Tempest"
                               :venue/category   "bar"
                               :venue/created-at (LocalDateTime/parse "2017-01-01T00:00")
                               :venue/updated-at (LocalDateTime/parse "2017-01-01T00:00")})
           (select/select-one ::venues.namespaced {:order-by [[:id :asc]]}))))
  (testing `select/select-fn-set
    (is (= #{"bar" "store"}
           (select/select-fn-set :venue/category ::venues.namespaced)))))

(derive ::venues.short-namespace ::test/venues)

(m/defmethod model/model->namespace ::venues.short-namespace
  [_model]
  {::venues.short-namespace :v})

(deftest ^:parallel namespaced-qualify-columns-test
  (testing "Automatically qualify unqualified columns in [model & columns] using model namespace"
    (let [built (atom [])]
      (binding [pipeline/*build* (comp (fn [result]
                                         (swap! built conj result)
                                         result)
                                       pipeline/*build*)]
        ;; the ID we're getting back in the results is ambiguous, but it doesn't really matter here I guess.
        (is (= {:v/id 1}
               (select/select-one [::venues.short-namespace :id] :id 1)))
        (is (= [{:select [:v/id]
                 :from   [[:venues :v]]
                 :where  [:= :id 1]}]
               @built))))))

(doto ::venues.namespaced.with-category
  (derive ::venues.namespaced)
  (derive ::venues.with-category))

(m/defmethod model/model->namespace ::venues.namespaced.with-category
  [_model]
  {::test/venues     :venue
   ::test/categories :category})

(deftest ^:parallel namespaced-with-joins-test
  (is (= {:select    [:*]
          :from      [[:venues :venue]]
          :order-by  [[:id :asc]]
          :left-join [:category [:= :venue.category :category.name]]}
         (tools.compile/build
           (select/select-one ::venues.namespaced.with-category {:order-by [[:id :asc]]}))))
  (is (= (toucan2.instance/instance
          ::venues.namespaced.with-category
          {:venue/id                 1
           :venue/name               "Tempest"
           :venue/category           "bar"
           :venue/created-at         (LocalDateTime/parse "2017-01-01T00:00")
           :venue/updated-at         (LocalDateTime/parse "2017-01-01T00:00")
           :category/name            "bar"
           :category/slug            "bar_01"
           :category/parent-category nil})
         (select/select-one ::venues.namespaced.with-category {:order-by [[:id :asc]]})))
  (testing `select/select-fn-set
    (is (= #{"Tempest" "BevMo" "Ho's Tavern"}
           (select/select-fn-set :venue/name ::venues.namespaced.with-category)))
    (is (= #{"bar" "store"}
           (select/select-fn-set :category/name ::venues.namespaced.with-category)))))

(deftest ^:parallel namespaced-with-joins-columns-test
  (is (= :venue
         (model/namespace ::venues.namespaced.with-category)))
  (is (= {:select    [:venue/id :venue/name :category/name]
          :from      [[:venues :venue]]
          :order-by  [[:id :asc]]
          :left-join [:category [:= :venue.category :category.name]]}
         (tools.compile/build
           (select/select-one [::venues.namespaced.with-category :venue/id :venue/name :category/name]
                              {:order-by [[:id :asc]]}))))
  (is (= (instance/instance ::venues.namespaced.with-category
                            {:venue/id 1, :venue/name "Tempest", :category/name "bar"})
         (select/select-one [::venues.namespaced.with-category :venue/id :venue/name :category/name]
                            {:order-by [[:id :asc]]}))))

(deftest ^:parallel positional-connectable-test
  (testing "Support :conn positional connectable arg"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No default Toucan connection defined"
         (select/select-one [:venues :id :name] 1)))
    (is (= [{:id 1, :name "Tempest"}]
           (select/select :conn ::test/db [:venues :id :name] 1)))
    (is (= [{:id 1, :name "Tempest"}]
           (select/select :conn ::test/db [:venues :id :name] 1)))
    (is (= {:id 1, :name "Tempest"}
           (select/select-one :conn ::test/db [:venues :id :name] 1)))
    (testing `select/count
      (is (= 1
             (select/count :conn ::test/db :venues 1)
             (select/count :conn ::test/db :venues :id 1)
             (select/count :conn ::test/db :venues {:where [:= :id 1]})
             (select/count :conn ::test/db [:venues :id :name] 1))))
    (testing `select/exists?
      (is (= true
             (select/exists? :conn ::test/db :venues 1)
             (select/exists? :conn ::test/db :venues :id 1)
             (select/exists? :conn ::test/db :venues {:where [:= :id 1]})
             (select/exists? :conn ::test/db [:venues :id :name] 1))))
    (testing "nil :conn should not override current connectable"
      (binding [conn/*current-connectable* ::test/db]
        (is (= [{:id 1, :name "Tempest"}]
               (select/select :conn nil [:venues :id :name] 1)))))
    (testing "Explicit connectable should override current connectable"
      (binding [conn/*current-connectable* :fake-db]
        (is (= true
               (select/exists? :conn ::test/db :venues 1)))))
    (testing "Explicit connectable should override model default connectable"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Don't know how to get a connection from .* :fake-db"
           (select/exists? :conn :fake-db [::test/venues :id :name] 1))))))
