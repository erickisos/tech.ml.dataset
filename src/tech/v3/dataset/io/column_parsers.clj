(ns tech.v3.dataset.io.column-parsers
  "Per-column parsers."
  (:require [tech.v3.dataset.io.datetime :as parse-dt]
            [tech.v3.dataset.impl.column-base :as column-base]
            [tech.v3.datatype.packing :as packing]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.bitmap :as bitmap]
            [tech.v3.datatype.errors :as errors]
            [tech.v3.datatype.argops :as argops]
            [tech.v3.datatype.datetime :as dtype-dt]
            [tech.v3.datatype.protocols :as dtype-proto]
            [ham-fisted.api :as hamf])
  (:import [java.util UUID List]
           [tech.v3.dataset Text]
           [tech.v3.datatype Buffer]
           [ham_fisted IMutList Casts]
           [org.roaringbitmap RoaringBitmap]
           [clojure.lang IFn Indexed]
           [java.time.format DateTimeFormatter]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


;;For sequences of maps and spreadsheets
;; 1. No parser specified -  do not change datatype, do not change input value.
;; Promote container as necessary; promotion requires a static cast when possible or
;; all the way to object.
;; 2. datatype,parser specified - if input value is correct datatype, add to container.
;;    if input is incorrect datatype, call parse fn.


;;For string-based pathways
;; 1. If no parser is specified, try-parse where ::parse-failure means
;; 2. If parse is specified, use parse if


(def parse-failure :tech.v3.dataset/parse-failure)
(def missing :tech.v3.dataset/missing)


(defn make-safe-parse-fn
  [parser-fn]
  (fn [str-val]
    (try
      (parser-fn str-val)
      (catch Throwable _e
        parse-failure))))


(def default-coercers
  (merge
   {:bool #(if (string? %)
             (let [^String data %]
               (cond
                 (.equals "true" data) true
                 (.equals "false" data) false
                 :else parse-failure))
             (boolean %))
    :boolean #(if (string? %)
                (let [^String data %]
                  (cond
                    (or (.equalsIgnoreCase "t" data)
                        (.equalsIgnoreCase "y" data)
                        (.equalsIgnoreCase "yes" data)
                        (.equalsIgnoreCase "True" data)
                        (.equalsIgnoreCase "positive" data))
                    true
                    (or (.equalsIgnoreCase "f" data)
                        (.equalsIgnoreCase "n" data)
                        (.equalsIgnoreCase "no" data)
                        (.equalsIgnoreCase "false" data)
                        (.equalsIgnoreCase "negative" data))
                    false
                    :else
                    parse-failure))
                (boolean %))
    :int16 (make-safe-parse-fn #(if (string? %)
                                  (Short/parseShort %)
                                  (short %)))
    :int32 (make-safe-parse-fn  #(if (string? %)
                                   (Integer/parseInt %)
                                   (int %)))
    :int64 (make-safe-parse-fn #(if (string? %)
                                  (Long/parseLong %)
                                  (long %)))
    :float32 (make-safe-parse-fn #(if (string? %)
                                    (let [fval (Float/parseFloat %)]
                                      (if (Float/isNaN fval)
                                        missing
                                        fval))
                                    (float %)))
    :float64 (make-safe-parse-fn #(if (string? %)
                                    (let [dval (Double/parseDouble %)]
                                      (if (Double/isNaN dval)
                                        missing
                                        dval))
                                    (double %)))
    :uuid (make-safe-parse-fn #(if (string? %)
                                 (UUID/fromString %)
                                 (if (instance? UUID %)
                                   %
                                   parse-failure)))
    :keyword #(if-let [retval (keyword %)]
                retval
                parse-failure)
    :symbol #(if-let [retval (symbol %)]
               retval
               parse-failure)
    :string #(let [str-val (if (string? %)
                             %
                             (str %))]
               (if (< (count str-val) 1024)
                 str-val
                 parse-failure))
    :text #(Text. (str %))}
   (->> parse-dt/datatype->general-parse-fn-map
        (mapcat (fn [[k v]]
                  (let [unpacked-parser (make-safe-parse-fn
                                         #(if (= k (dtype/elemwise-datatype %))
                                            %
                                            (v %)))]
                    ;;packing is now done at the container level.
                    (if (packing/unpacked-datatype? k)
                      [[k unpacked-parser]
                       [(packing/pack-datatype k) unpacked-parser]]
                      [[k unpacked-parser]]))))
        (into {}))))


(definterface PParser
  (addValue [^long idx value])
  (finalize [^long rowcount]))

(defn add-value!
  [^PParser p ^long idx value]
  (.addValue p idx value))


(defn finalize!
  [^PParser p ^long rowcount]
  (.finalize p rowcount))


(defn add-missing-values!
  [^IMutList container ^RoaringBitmap missing
   missing-value ^long idx]
  (let [n-elems (.size container)]
    (when (< n-elems idx)
      (.add missing (long n-elems) idx)
      (.addAllReducible container (hamf/repeat (- idx n-elems) missing-value)))))


(defn finalize-parser-data!
  [container missing failed-values failed-indexes
   missing-value rowcount]
  (add-missing-values! container missing missing-value rowcount)
  (merge
   #:tech.v3.dataset{:data (or (dtype/as-array-buffer container)
                               (dtype/as-native-buffer container)
                               container)
                     :missing missing
                     :force-datatype? true}
   (when (and failed-values
              (not= 0 (dtype/ecount failed-values)))
     #:tech.v3.dataset{:metadata {:unparsed-data failed-values
                                  :unparsed-indexes failed-indexes}})))


(defn- missing-value?
  "Is this a missing value coming from a CSV file"
  [value]
  ;;fastpath for numbers
  (cond
    (or (instance? Double value) (instance? Float value))
    (Double/isNaN (Casts/doubleCast value))
    (not (instance? Number value))
    (or (nil? value)
        (.equals "" value)
        (identical? value :tech.v3.dataset/missing)
        (and (string? value) (.equalsIgnoreCase ^String value "na")))))


(deftype FixedTypeParser [^IMutList container
                          container-dtype
                          missing-value parse-fn
                          ^RoaringBitmap missing
                          ^IMutList failed-values
                          ^RoaringBitmap failed-indexes
                          column-name
                          ^:unsynchronized-mutable ^long max-idx]
  dtype-proto/PECount
  (ecount [_this] (inc max-idx))
  Indexed
  (nth [this idx] (.nth this idx nil))
  (nth [this idx dv]
    (let [cec (.size container)]
      (if (or (>= idx cec)
              (.contains missing idx))
        nil
        (.get container idx))))
  PParser
  (addValue [_this idx value]
    (let [idx (unchecked-long idx)]
      (set! max-idx (max idx max-idx))
      ;;First pass is to potentially parse the value.  It could already
      ;;be in the space of the container or it could require the parse-fn
      ;;to make it.
      (let [parsed-value (cond
                           (missing-value? value)
                           :tech.v3.dataset/missing
                           (and (identical? (dtype/datatype value) container-dtype)
                                (not (instance? String value)))
                           value
                           :else
                           (parse-fn value))]
        (cond
          ;;ignore it; we will add missing when we see the first valid value
          (identical? :tech.v3.dataset/missing parsed-value)
          nil
          ;;Record the original incoming value if we are parsing in relaxed mode.
          (identical? :tech.v3.dataset/parse-failure parsed-value)
          (if failed-values
            (do
              (.add failed-values value)
              (.add failed-indexes (unchecked-int idx)))
            (errors/throwf "Failed to parse value %s as datatype %s on row %d"
                           value container-dtype idx))
          :else
          (do
            (add-missing-values! container missing missing-value idx)
            (.add container parsed-value))))))
  (finalize [_p rowcount]
    (finalize-parser-data! container missing failed-values failed-indexes
                           missing-value rowcount)))


(defn- find-fixed-parser
  [kwd]
  (if (= kwd :string)
    str
    (if-let [retval (get default-coercers kwd)]
      retval
      identity)))


(defn- datetime-formatter-parser-fn
  [parser-datatype formatter]
  (let [unpacked-datatype (packing/unpack-datatype parser-datatype)
        parser-fn (parse-dt/datetime-formatter-parse-str-fn
                   unpacked-datatype formatter)]
    [(make-safe-parse-fn parser-fn) false]))


(defn parser-entry->parser-tuple
  [parser-kwd]
  (if (vector? parser-kwd)
    (do
      (assert (= 2 (count parser-kwd)))
      (let [[parser-datatype parser-fn] parser-kwd]
        (assert (keyword? parser-datatype))
        [parser-datatype
         (cond
           (= :relaxed? parser-fn)
           [(find-fixed-parser parser-datatype) true]
           (instance? IFn parser-fn)
           [parser-fn true]
           (and (dtype-dt/datetime-datatype? parser-datatype)
                (string? parser-fn))
           (datetime-formatter-parser-fn parser-datatype
                                         (DateTimeFormatter/ofPattern parser-fn))
           (and (dtype-dt/datetime-datatype? parser-datatype)
                (instance? DateTimeFormatter parser-fn))
           (datetime-formatter-parser-fn parser-datatype parser-fn)
           (= :text parser-datatype)
           [(find-fixed-parser parser-datatype)]
           :else
           (errors/throwf "Unrecoginzed parser fn type: %s" (type parser-fn)))]))
    [parser-kwd [(find-fixed-parser parser-kwd) false]]))


(defn make-fixed-parser
  ^PParser [cname parser-kwd options]
  (let [[dtype [parse-fn relaxed?]] (parser-entry->parser-tuple parser-kwd)
        [failed-values failed-indexes] (when relaxed?
                                         [(dtype/make-container :list :object 0)
                                          (bitmap/->bitmap)])
        container (column-base/make-container dtype options)
        missing-value (column-base/datatype->missing-value dtype)
        missing (bitmap/->bitmap)]
    (FixedTypeParser. container dtype missing-value parse-fn
                      missing failed-values failed-indexes
                      cname -1)))


(defn parser-kwd-list->parser-tuples
  [kwd-list]
  (mapv parser-entry->parser-tuple kwd-list))


(def default-parser-datatype-sequence
  [:bool :int16 :int32 :int64 :float64 :uuid
   :packed-duration :packed-local-date
   :zoned-date-time :string :text :boolean])


(defn- promote-container
  ^IMutList [old-container ^RoaringBitmap missing new-dtype options]
  (let [n-elems (dtype/ecount old-container)
        container (column-base/make-container new-dtype options)
        missing-value (column-base/datatype->missing-value new-dtype)
        ;;Ensure we unpack a container if we have to promote it.
        old-container (packing/unpack old-container)]
    (dotimes [idx n-elems]
      (if (.contains missing idx)
        (.add container missing-value)
        (.add container (casting/cast
                               (old-container idx)
                               new-dtype))))
    container))


(defn- find-next-parser
  ^long [value container-dtype ^List promotion-list]
  (let [start-idx (argops/index-of (mapv first promotion-list) container-dtype)
        n-elems (.size promotion-list)]
    (if (== start-idx -1)
      -1
      (long (loop [idx (inc start-idx)]
              (if (< idx n-elems)
                (let [[_container-datatype parser-fn]
                      (.get promotion-list idx)
                      parsed-value (parser-fn value)]
                  (if (= parsed-value parse-failure)
                    (recur (inc idx))
                    idx))
                -1))))))


(defn- resolve-parser-index
  "Resolve the next parser index returning a tuple of [parser-datatype new-parser-fn]"
  [next-idx ^List container container-dtype missing ^List promotion-list]
  (let [next-idx (long next-idx)]
    (if (== -1 next-idx)
      [:object nil]
      (let [n-missing (dtype/ecount missing)
            n-valid (- (dtype/ecount container) n-missing)
            parser-data (.get promotion-list next-idx)
            parser-datatype (first parser-data)]
        ;;Figure out if our promotion process will result in a valid container.
        (cond
          (== 0 n-valid)
          parser-data
          (and (or (identical? :bool container-dtype)
                   (identical? :boolean container-dtype)
                   (casting/numeric-type? container-dtype))
               (casting/numeric-type? (packing/unpack-datatype parser-datatype)))
          parser-data
          :else
          [:string (default-coercers :string)])))))


(defn- fast-dtype
  [value]
  (if (string? value)
    :string
    (dtype/datatype value)))


(deftype PromotionalStringParser [^{:unsynchronized-mutable true
                                    :tag IMutList} container
                                  ^{:unsynchronized-mutable true} container-dtype
                                  ^{:unsynchronized-mutable true} missing-value
                                  ^{:unsynchronized-mutable true} parse-fn
                                  ^RoaringBitmap missing
                                  ;;List of datatype,parser-fn tuples
                                  ^List promotion-list
                                  column-name
                                  ^:unsynchronized-mutable ^long max-idx
                                  options]
  dtype-proto/PECount
  (ecount [_this] (inc max-idx))
  Indexed
  (nth [this idx] (.nth this idx nil))
  (nth [this idx dv]
    (let [cec (.size container)]
      (if (or (>= idx cec)
              (.contains missing idx))
        nil
        (.get container idx))))
  PParser
  (addValue [_p idx value]
    (set! max-idx (max idx max-idx))
    (let [parsed-value
          (cond
            (missing-value? value)
            :tech.v3.dataset/missing


            (identical? (fast-dtype value) container-dtype)
            value

            ;;If we have a function to parse the data
            parse-fn
            (let [parsed-value (parse-fn value)]
              ;;If the value parsed successfully
              (if (not (identical? :tech.v3.dataset/parse-failure parsed-value))
                parsed-value
                ;;else Perform column promotion
                (let [next-idx (find-next-parser value container-dtype promotion-list)
                      [parser-datatype new-parser-fn]
                      (resolve-parser-index next-idx container container-dtype
                                            missing promotion-list)
                      parsed-value (if new-parser-fn
                                     (new-parser-fn value)
                                     value)
                      new-container (promote-container container missing
                                                       parser-datatype
                                                       options)
                      new-missing-value (column-base/datatype->missing-value
                                         parser-datatype)]
                  ;;Update member variables based on new parser
                  (set! container new-container)
                  (set! container-dtype parser-datatype)
                  (set! missing-value new-missing-value)
                  (set! parse-fn new-parser-fn)
                  parsed-value)))
            ;;Else, nothing to parse with, just return string value
            :else
            value)]
      (cond
        ;;Promotional parsers should not have parse failures.
        (identical? :tech.v3.dataset/parse-failure parsed-value)
        (errors/throwf "Parse failure detected in promotional parser - Please file issue.")
        (identical? :tech.v3.dataset/missing parsed-value)
        nil ;;Skip, will add missing on next valid value
        :else
        (do
          (add-missing-values! container missing missing-value idx)
          (try
            (.add container parsed-value)
            (catch Exception e
              (throw (RuntimeException. (str "Parse failure of datatype: "
                                             (dtype/elemwise-datatype container)
                                             e)))))))))
  (finalize [_p rowcount]
    (finalize-parser-data! container missing nil nil missing-value rowcount)))


(defn promotional-string-parser
  (^PParser [column-name parser-datatype-sequence options]
   (let [first-dtype (first parser-datatype-sequence)]
     (PromotionalStringParser. (column-base/make-container
                                (if (= :bool first-dtype)
                                  :boolean
                                  first-dtype)
                                options)
                               first-dtype
                               false
                               (default-coercers first-dtype)
                               (bitmap/->bitmap)
                               (mapv (juxt identity default-coercers)
                                     parser-datatype-sequence)
                               column-name
                               -1
                               options)))
  (^PParser [column-name options]
   (promotional-string-parser column-name default-parser-datatype-sequence options)))


(deftype PromotionalObjectParser [^{:unsynchronized-mutable true
                                    :tag IMutList} container
                                  ^{:unsynchronized-mutable true} container-dtype
                                  ^{:unsynchronized-mutable true} missing-value
                                  ^RoaringBitmap missing
                                  column-name
                                  ^:unsynchronized-mutable ^long max-idx
                                  options]
  dtype-proto/PECount
  (ecount [_this] (inc max-idx))
  Indexed
  (nth [this idx] (.nth this idx nil))
  (nth [this idx dv]
    (let [cec (.size container)]
      (if (or (>= idx cec)
              (.contains missing idx))
        nil
        (.get container idx))))
  PParser
  (addValue [_p idx value]
    (set! max-idx (max idx max-idx))
    (when-not (missing-value? value)
      (let [org-datatype (dtype/datatype value)
            ;;Avoid the pack call if possible
            packed-dtype (if (identical? container-dtype org-datatype)
                           org-datatype
                           (packing/pack-datatype org-datatype))
            container-ecount (- (.size container) (.getCardinality missing))]
        (if (or (== 0 container-ecount)
                (identical? container-dtype packed-dtype))
          (do
            (when (== 0 container-ecount)
              (set! container (column-base/make-container packed-dtype options))
              (set! container-dtype packed-dtype)
              (set! missing-value (column-base/datatype->missing-value packed-dtype)))
            (when-not (== container-ecount idx)
              (add-missing-values! container missing missing-value idx))
            (.add container value))
          ;;boolean present a problem here.  We generally want to keep them as booleans
          ;;and not promote them to full numbers.
          (let [widest-datatype (if (identical? org-datatype :boolean)
                                  (if (identical? container-dtype :boolean)
                                    :boolean
                                    :object)
                                  (casting/widest-datatype
                                   (packing/unpack-datatype container-dtype)
                                   org-datatype))]
            (when-not (= widest-datatype container-dtype)
              (let [new-container (promote-container container
                                                     missing widest-datatype
                                                     options)]
                (set! container new-container)
                (set! container-dtype widest-datatype)
                (set! missing-value (column-base/datatype->missing-value
                                     widest-datatype))))
            (when-not (== container-ecount idx)
              (add-missing-values! container missing missing-value idx))
            (.add container value))))))
  (finalize [_p rowcount]
    (finalize-parser-data! container missing nil nil
                           missing-value rowcount)))


(defn promotional-object-parser
  ^PParser [column-name options]
  (PromotionalObjectParser. (dtype/make-list :boolean)
                            :boolean
                            false
                            (bitmap/->bitmap)
                            column-name
                            -1
                            options))
