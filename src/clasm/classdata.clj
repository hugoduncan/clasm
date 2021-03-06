(ns clasm.classdata
  "A JVM classfile representation builder.
Creates a data structure with all the classfile details."
  (:refer-clojure :exclude [class pop]))

;;; # Components
;;; A component is a class, field, method, constant or an attribute. These
;;; functions provide a generic way of composing components.

;;; ## Component Metadata
(defn component-scope
  "Each component provides a specific scope."
  [component]
  (-> component meta ::scope))

(defn component-collection
  "Each component is attached to a specific component collection."
  [component]
  (-> component meta ::collection))

(defn component-parent-scopes
  "Each component is attached to one of a set of component scopes."
  [component]
  (-> component meta ::parent-scopes))

(defn report-unattached
  [components]
  (throw (ex-info
          (str "Could not attach components.")
          {:components components})))

(defn ^:internal add-component
  "Add a component to the first component in the stack which is in the set of
possible scopes and which has the specified collection."
  [top possible-scopes collection component]
  (if (and (contains? top collection) (possible-scopes (component-scope top)))
    [(update-in top [collection] conj component) nil]
    [top component]))

(declare label)

(defn add*
  [parent components]
  (let [[new-parent others]
        (reduce
         (fn add-compish [[p pass-ups] c]
           (cond
             (vector? c) (let [[new-p & comps] (add* p c)]
                           [new-p (vec (concat pass-ups comps))])
             (keyword? c) (add-compish [p pass-ups] (label c))
             (nil? c) [p pass-ups]
             (map? c) (let [[p pass-up]
                            (add-component
                             p
                             (component-parent-scopes c)
                             (component-collection c)
                             c)]
                        [p (conj pass-ups pass-up)])
             :else (throw (ex-info (str "Invalid component" c)
                                   {:component c}))))
         [parent []]
         components)]
    (vec (concat [new-parent] others))))


;;; ## Adding Components and Attribute Values
(defmacro add
  "Add a component. Fields, methods, attributes are all components."
  {:indent 1}
  [component & body]
  `(add* ~component [~@body]))

(defmacro defstructure
  "Define a function, that adds a structure to a parent component."
  [struct-name collection parent-scopes fields]
  `(defn ~struct-name
     [~@fields]
     (with-meta
       (zipmap
        ~(vec (map (comp keyword name) fields))
        ~(vec fields))
       {::scope ~(keyword (name struct-name))
        ::collection ~collection
        ::parent-scopes ~parent-scopes})))

(defmacro defvalue
  "Define a function, that adds a value generated by value-fn to a parent
  component."
  [value-name collection parent-scopes fields value-f]
  `(defn ~value-name
     {:arglists '[[~@fields]]}
     [& [~@fields :as args#]]
     (with-meta
       (apply ~value-f args#)
       {::scope ~(keyword (name value-name))
        ::collection ~collection
        ::parent-scopes ~parent-scopes})))


;;; # Class
(defn error-check-class
  "Check the classdata for errors."
  [c]
  [nil c])

(defn class*
  "Return a new classdata map for the class"
  [class-name-sym access-flags]
  ^{:type ::class ::scope :class}
  {:name class-name-sym
   :access-flags (vec access-flags)
   :methods []
   :fields []
   :constant-pool []
   :attributes []})

(defmacro class
  "Define a new class, interface, enumeration or annotation.
`access-flags` is a sequence of zero or more of :public, :final, :super,
               :interface, :abstract, :synthetic, :annotation, :enum"
  [class-name & access-flags]
  `(class* '~class-name ~(vec access-flags)))

(defmacro edit
  "Modify a classdata definition of a class"
  [classdata & body]
  `(let [[c# others#] (add ~classdata ~@body)]
     (when others#
       (report-unattached others#))
     c#))


;;; # Constant pool
(defn constant
  [label tag & values]
  ^{:type ::constant
    ::scope :constant
    ::collection :constant-pool
    ::parent-scopes #{:class}}
  {:label label
   :tag tag
   :values values})

;;; # Methods
(defn error-check-method
  "Check the method for errors."
  [c]
  [nil c])

(defn method*
  [return-type method-name-sym args access-flags]
  ^{:type ::method ::scope :method ::collection :methods
    ::parent-scopes #{:class}}
  {:return-type return-type
   :name method-name-sym
   :args args
   :access-flags access-flags
   :attributes []})

(defmacro method
  [return-type method-name args & access-flags]
  `(method*
    ~(keyword (name return-type))
    '~method-name
    '~(vec (map
            (fn [[t n]]
              [(keyword (name t)) (keyword (name n))])
            (partition 2 args)))
    ~(vec access-flags)))

;;; # Fields
(defn field*
  [field-type field-name-sym access-flags]
  ^{:type ::field ::scope :field ::parent-scopes #{:class} ::collection :fields}
  {:name field-name-sym
   :type field-type
   :access-flags (vec access-flags)
   :attributes []})

(defmacro field
  [field-type field-name & access-flags]
  `(field* ~field-type '~field-name ~(vec access-flags)))

;;; # Attributes
(def attributes
  "Each attribute is:
  [name applies-to args implicit-members]"
  [[:constant-value #{:field} [:const-index]]
   [:code #{:method} [] [:code :exception_table]]
                                        ; :max_stack :max_locals :code_length
                                        ; :exception_table_length
   [:stack-map-table #{:code} [:stack_map_frames]]
   [:exceptions #{:method} [] [:exception_table]]
   [:inner-classes #{:class} [:classes]]
   [:enclosing-method #{:class} [:class :method]]
   [:synthetic #{:class :method :field} []]
   [:signature #{:class :method :field} [:signature]]
   [:source-file #{:class} [:source_file]]
   [:source-debug-extension #{:class} [] [:debug_extensions]]
   [:line-number-table #{:code} [] [:line_number_table]]
   [:local-variable-table #{:code} [:local_variable_table]]
   [:local-variable-type-table #{:code} [:local_variable_type_table]]
   [:deprecated #{:class :method :field} []]
   [:runtime-visible-annotations #{:class :method :field} [:annotations]]
   [:runtime-invisible-annotations #{:class :method :field} [:annotations]]
   [:runtime-visible-parameter-annotations #{:method-info} [:annotations]]
   [:runtime-invisible-parameter-annotations #{:method-info} [:annotations]]
   [:annotation-default #{:method-info} [:default-value]]
   [:bootstrap-methods #{:class} [:bootstrap_methods]]])

(defn make-attribute
  [attribute-name-sym applies-to args implicit-members]
  (with-meta
    (merge
     {:name attribute-name-sym
      :args args
      :attributes []}
     (zipmap implicit-members (repeat [])))
    {:type ::attribute
     ::scope (keyword (name attribute-name-sym))
     ::parent-scopes applies-to
     ::collection :attributes}))

(defn generate-attribute-function
  "Generate an attribute function"
  [[attribute applies-to args implicit-members]]
  (let [arg-names (vec (map (comp symbol name) args))
        f-name (symbol (name attribute))]
    `(defn ~f-name [~@arg-names]
       (make-attribute ~attribute ~applies-to ~arg-names ~implicit-members))))

(defmacro generate-attribute-functions
  "Generate a function for each attribute."
  []
  (let [fns (for [attribute attributes]
              (generate-attribute-function attribute))]
    `(do ~@fns)))

(generate-attribute-functions)

(defstructure line-number :line_number_table #{:line-number-table}
  [start_pc line_number])

(defstructure exception :exception_table #{:exceptions}
  [exception-type])

(defn smap-string
  "SMAP as defined in JSR-45. The SMAP is added to the SourceDebugExtension
  attribute.

  See: http://download.oracle.com/otndocs/jcp/dsol-1.0-fr-spec-oth-JSpec/"
  [source-file default-stratum stratums & [{:keys [vendor-id vendor-content]}]]
  (str "SMAP\n" source-file ".java\n" default-stratum "\n"
       (reduce
        (fn smap-stratum [s {:keys [stratum source-files lines] :as stratum}]
          (str s
               "*S " stratum "\n"
               "*F\n"
               (reduce
                (fn smap-source [s [{:keys [source-name source-path]} i]]
                  (if source-path
                    (str s "+ " i " " source-name \newline source-path)
                    (str s i " " source-name)))
                ""
                (map vector source-files (iterate inc 1)))
               "\n*L\n"
               (reduce
                (fn smap-line [s {:keys [input-start-line line-file-id
                                         repeat-count output-start-line
                                         output-increment]} ]
                  (str s input-start-line
                       (when line-file-id (str "#" line-file-id))
                       (when repeat-count (str "," repeat-count))
                       ":"
                       output-start-line
                       (when output-increment (str "," output-increment))
                       \newline))
                ""
                lines)))
        "" stratums)
       (when vendor-id (str "*V\n" vendor-id "\n" vendor-content))
       "*E"))

(defvalue smap :debug_extensions #{:source-debug-extension}
  [source-file default-stratum stratums & [{:keys [vendor-id vendor-content]}]]
  (comp #(hash-map :smap %) smap-string))




;;; # Opcodes

(def
  opcodes
  "Each element is:
    [opcode nemonic description args opstack-pop opstack-push size-fn asm-args"
  [[0 :nop "Do nothing"]
   [1 :aconst_null "Push null" [] [] [:null]]
   [2 :iconst_m1 "Push int constant -1" [] [] [:int-const]]
   [3 :iconst_0 "Push int constant 0" []]
   [4 :iconst_1 "Push int constant 1" []]
   [5 :iconst_2 "Push int constant 2" []]
   [6 :iconst_3 "Push int constant 3" []]
   [7 :iconst_4 "Push int constant 4" []]
   [8 :iconst_5 "Push int constant 5" []]
   [9 :lconst_0 "Push long constant 0" []]
   [10 :lconst_1 "Push long constant 1" []]
   [11 :fconst_0 "Push float constant 0" []]
   [12 :fconst_1 "Push float constant 1" []]
   [13 :fconst_2 "Push float constant 2" []]
   [14 :dconst_0 "Push double constant 0.0" []]
   [15 :dconst_1 "Push double constant 1.0" []]
   [16 :bipush "Push byte" [:byte-value] [] [:byte-value]]
   [17 :sipush "Push short"
    [:short-value-hi :short-value-low] [] [:short-value]]
   [18 :ldc "Push item from runtime constant pool" [:const-index] [] [:value]]
   [19 :ldc_w "Push item from runtime constant pool (wide index)"
    [:const-index-high :const-index-low] [] [:value]]
   [20 :ldc2_w "Push long or double from runtime constant pool (wide index)"
    [:const-index-high :const-index-low] [] [:value] nil nil [:wide-index]]
   [21 :iload "Load int from local variable" [:local-index] [] [:int-value]]
   [22 :lload "Load long from local variable" [:local-index] [] [:long-value]]
   [23 :fload "Load float from local variable" [:local-index] [] [:float-value]]
   [24 :dload "Load double from local variable"
    [:local-index] [] [:double-value]]
   [25 :aload "Load reference from local variable"
    [:local-index] [] [:object-ref]]
   [26 :iload_0 "Load int from local variable 0" [] [] [:int-value]]
   [27 :iload_1 "Load int from local variable 1" [] [] [:int-value]]
   [28 :iload_2 "Load int from local variable 2" [] [] [:int-value]]
   [29 :iload_3 "Load int from local variable 3" [] [] [:int-value]]
   [30 :lload_0 "Load long from local variable 0" [] [] [:long-value]]
   [31 :lload_1 "Load long from local variable 1" [] [] [:long-value]]
   [32 :lload_2 "Load long from local variable 2" [] [] [:long-value]]
   [33 :lload_3 "Load long from local variable 3" [] [] [:long-value]]
   [34 :fload_0 "Load float from local variable 0" [] [] [:float-value]]
   [35 :fload_1 "Load float from local variable 1" [] [] [:float-value]]
   [36 :fload_2 "Load float from local variable 2" [] [] [:float-value]]
   [37 :fload_3 "Load float from local variable 3" [] [] [:float-value]]
   [38 :dload_0 "Load double from local variable 0" [] [] [:double-value]]
   [39 :dload_1 "Load double from local variable 1" [] [] [:double-value]]
   [40 :dload_2 "Load double from local variable 2" [] [] [:double-value]]
   [41 :dload_3 "Load double from 3rd local variable" [] [] [:double-value]]
   [42 :aload_0 "Load reference from local variable 0"
    [] [] [:object-ref] [:local-0]]
   [43 :aload_1 "Load reference from local variable 1"
    [] [] [:object-ref] [:local-1]]
   [44 :aload_2 "Load reference from local variable 2"
    [] [] [:object-ref] [:local-2]]
   [45 :aload_3 "Load reference from local variable 3"
    [] [] [:object-ref] [:local-3]]
   [46 :iaload "Load int from array"
    [] [:array-ref :array-index] [:int-value]]
   [47 :laload "Load long from array"
    [] [:array-ref :array-index] [:long-value]]
   [48 :faload "Load float from array"
    [] [:array-ref :array-index] [:float-value]]
   [49 :daload "Load double from array"
    [] [:array-ref :array-index] [:double-value]]
   [50 :aaload "Load reference from array"
    [] [:array-ref :array-index] [:reference]]
   [51 :baload "Load byte or boolean from array"
    [] [:array-ref :index] [:value]]
   [52 :caload "Load char from array"
    [] [:array-ref :index] [:value]]
   [53 :saload "Load short from array"
    [] [:array-ref :index] [:value]]
   [54 :istore "Store int into local variable"
    [:local-index] [:int-value] []]
   [55 :lstore "Store long into local variable"
    [:local-index] [:long-value] []]
   [56 :fstore "Store float into local variable"
    [:local-index] [:float-value] []]
   [57 :dstore "Store double into local variable"
    [:local-index] [:double-value] []]
   [58 :astore "Store reference into local variable"
    [:local-index] [:object-ref] []]
   [59 :istore_0 "Store int into local variable 0" [] [:int-value] []]
   [60 :istore_1 "Store int into local variable 1" [] [:int-value] []]
   [61 :istore_2 "Store int into local variable 2" [] [:int-value] []]
   [62 :istore_3 "Store int into local variable 3" [] [:int-value] []]
   [63 :lstore_0 "Store long into local variable 0" [] [:long-value] []]
   [64 :lstore_1 "Store long into local variable 1" [] [:long-value] []]
   [65 :lstore_2 "Store long into local variable 2" [] [:long-value] []]
   [66 :lstore_3 "Store long into local variable 3" [] [:long-value] []]
   [67 :fstore_0 "Store float into local variable 0" [] [:float-value] []]
   [68 :fstore_1 "Store float into local variable 1" [] [:float-value] []]
   [69 :fstore_2 "Store float into local variable 2" [] [:float-value] []]
   [70 :fstore_3 "Store float into local variable 3" [] [:float-value] []]
   [71 :dstore_0 "Store double into local variable 0" [] [:double-value] []]
   [72 :dstore_1 "Store double into local variable 1" [] [:double-value] []]
   [73 :dstore_2 "Store double into local variable 2" [] [:double-value] []]
   [74 :dstore_3 "Store double into local variable 3" [] [:double-value] []]
   [75 :astore_0 "Store reference into first local variable"
    [] [:object-ref] [] [:local-0]]
   [76 :astore_1 "Store reference into second local variable"
    [] [:object-ref] [] [:local-1]]
   [77 :astore_2 "Store reference into third local variable"
    [] [:object-ref] [] [:local-2]]
   [78 :astore_3 "Store reference into fourth local variable"
    [] [:object-ref] [] [:local-3]]
   [79 :iastore "Store into int array"
    [] [:array-ref :array-index :value] []]
   [80 :lastore "Store into long array"
    [] [:array-ref :array-index :value] []]
   [81 :fastore "Store into float array"
    [] [:array-ref :array-index :value] []]
   [82 :dastore "Store into double array"
    [] [:array-ref :array-index :value] []]
   [83 :aastore "Store into reference array"
    [] [:array-ref :array-index :value] []]
   [84 :bastore "Store into byte or boolean array"
    [] [:array-ref :array-index :value] []]
   [85 :castore  "Store into char array"
    [] [:array-ref :array-index :value] []]
   [86 :sastore "Store into short array"
    [] [:array-ref :array-index :value] []]
   [87 :pop "Pop the top operand stack value" [] [:value] []]
   [88 :pop2 "Pop the top one or two operand stack values" [] [:value] []]
   [89 :dup "Duplicate the top operand stack value"
    [] [:value] [:value :value]]
   [90 :dup_x1
    "Duplicate the top operand stack value and insert two values down"
    [] [:value] [:value :value]]
   [91 :dup_x2
    "Duplicate the top operand stack value and insert two or three values down"
    [] [:value] [:value :value]]
   [92 :dup2
    "Duplicate the top one or two operand stack values"
    [] [:value] [:value :value]]
   [93 :dup2_x1
    "Duplicate the top 1 or 2 stack values and insert 2 or 3 values down"
    [] [:value] [:value :value]]
   [94 :dup2_x2
    "Duplicate the top one or two operand stack values and insert two, three, or four values down"
    [] [:value] [:value :value]]
   [95 :swap "Swap the top two operand stack values"
    [] [:value :value] [:value :value]]
   [96 :iadd "Add int" [] [:int-value :int-value] [:int-value]]
   [97 :ladd "Add long" [] [:long-value :long-value] [:long-value]]
   [98 :fadd "Add float" [] [:float-value :float-value] [:float-value]]
   [99 :dadd "Add double" [] [:double-value :double-value] [:double-value]]
   [100 :isub "Subtract int" [] [:int-value :int-value] [:int-value]]
   [101 :lsub "Subtract long" [] [:long-value :long-value] [:long-value]]
   [102 :fsub "Subtract float" [] [:float-value :float-value] [:float-value]]
   [103 :dsub "Subtract double"
    [] [:double-value :double-value] [:double-value]]
   [104 :imul "Multiply int" [] [:int-value :int-value] [:int-value]]
   [105 :lmul "Multiply long" [] [:long-value :long-value] [:long-value]]
   [106 :fmul "Multiply float" [] [:float-value :float-value] [:float-value]]
   [107 :dmul "Multiply double"
    [] [:double-value :double-value] [:double-value]]
   [108 :idiv "Divide int" [] [:int-value :int-value] [:int-value]]
   [109 :ldiv "Divide long" [] [:long-value :long-value] [:long-value]]
   [110 :fdiv "Divide float" [] [:float-value :float-value] [:float-value]]
   [111 :ddiv "Divide double" [] [:double-value :double-value] [:double-value]]
   [112 :irem "Int remainder" [] [:int-value] [:int-value]]
   [113 :lrem "Long remainder" [] [:long-value] [:long-value]]
   [114 :frem "Float remainder" [] [:float-value] [:float-value]]
   [115 :drem "Double remainder" [] [:double-value] [:double-value]]
   [116 :ineg "Negate int" [] [:int-value] [:int-value]]
   [117 :lneg "Negate long" [] [:long-value] [:long-value]]
   [118 :fneg "Negate float" [] [:float-value] [:float-value]]
   [119 :dneg "Negate double" [] [:double-value] [:double-value]]
   [120 :ishl "Shift left int" [] [:int-value :int-value] [:int-value]]
   [121 :lshl "Shift left int" [] [:int-value :int-value] [:int-value]]
   [122 :ishr "Shift right long" [] [:int-value :int-value] [:int-value]]
   [123 :lshr "Shift right long" [] [:int-value :int-value] [:int-value]]
   [124 :iushr "Logical shift right int"
    [] [:int-value :int-value] [:int-value]]
   [125 :lushr "Logical shift right long"
    [] [:int-value :int-value] [:int-value]]
   [126 :iand "Boolean AND int" [] [:int-value :int-value] [:int-value]]
   [127 :land "Boolean AND long" [] [:long-value :long-value] [:long-value]]
   [128 :ior "Boolean OR int" [] [:int-value :int-value] [:int-value]]
   [129 :lor "Boolean OR long" [] [:long-value :long-value] [:long-value]]
   [130 :ixor "Boolean XOR int" [] [:int-value :int-value] [:int-value]]
   [131 :lxor "Boolean XOR long" [] [:long-value :long-value] [:long-value]]
   [132 :iinc "Increment local variable by constant"
    [:local-index :int-value] [] []]
   [133 :i2l "Convert int to long" [] [:int-value] [:long-value]]
   [134 :i2f "Convert int to float" [] [:int-value] [:float-value]]
   [135 :i2d "Convert int to double" [] [:int-value] [:double-value]]
   [136 :l2i "Convert long to int" [] [:long-value] [:int-value]]
   [137 :l2f "Convert long to float" [] [:long-value] [:float-value]]
   [138 :l2d "Convert long to double" [] [:long-value] [:double-value]]
   [139 :f2i "Convert float to int" [] [:float-value] [:int-value]]
   [140 :f2l "Convert float to long" [] [:float-value] [:long-value]]
   [141 :f2d "Convert float to double" [] [:float-value] [:double-value]]
   [142 :d2i "Convert double to int" [] [:double-value] [:int-value]]
   [143 :d2l "Convert double to long" [] [:double-value] [:long-value]]
   [144 :d2f "Convert double to float" [] [:double-value] [:float-value]]
   [145 :i2b "Convert int to byte" [] [:int-value] [:byte-value]]
   [146 :i2c "Convert int to char" [] [:int-value] [:char-value]]
   [147 :i2s "Convert int to short" [] [:int-value] [:int-value]]
   [148 :lcmp "Compare long"
    [] [:long-value :long-value] [:int-value]]
   [149 :fcmpl "Compare float (-1 if NaN)"
    [] [:float-value :float-value] [:int-value]]
   [150 :fcmpg "Compare float (1 if NaN)"
    [] [:float-value :float-value] [:int-value]]
   [151 :dcmpl "Compare double (-1 if NaN)"
    [] [:double-value :double-value] [:int-value]]
   [152 :dcmpg "Compare double (1 if NaN)"
    [] [:double-value :double-value] [:int-value]]
   [153 :ifeq "Branch if int comparison with 0 equal"
    [:branch-byte-high :branch-byte-low] [:int-type] nil nil nil [:label]]
   [154 :ifne  "Branch if int comparison with 0 not equal"
    [:branch-byte-high :branch-byte-low] [:int-type] nil nil nil [:label]]
   [155 :iflt  "Branch if int comparison with 0 less"
    [:branch-byte-high :branch-byte-low] [:int-type] nil nil nil [:label]]
   [156 :ifge  "Branch if int comparison with 0 greater equal"
    [:branch-byte-high :branch-byte-low] [:int-type] nil nil nil [:label]]
   [157 :ifgt "Branch if int comparison with 0 greater"
    [:branch-byte-high :branch-byte-low] [:int-type] nil nil nil [:label]]
   [158 :ifle "Branch if int comparison with 0 less or equal"
    [:branch-byte-high :branch-byte-low] [:int-type] nil nil nil [:label]]
   [159 :if_icmpeq "Branch if int comparison equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type] nil nil
    nil [:label]]
   [160 :if_icmpne  "Branch if int comparison not equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type] nil nil
    nil [:label]]
   [161 :if_icmplt "Branch if int comparison less than"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type] nil nil
    nil [:label]]
   [162 :if_icmpge "Branch if int comparison greater or equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type] nil nil
    nil [:label]]
   [163 :if_icmpgt "Branch if int comparison greater than"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type] nil nil
    nil [:label]]
   [164 :if_icmple "Branch if int comparison less or equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type] nil nil
    nil [:label]]
   [165 :if_acmpeq "Branch if reference comparison equal"
    [:branch-byte-high :branch-byte-low] [:reference-type :reference-type] []
    nil nil nil [:label]]
   [166 :if_acmpne "Branch if reference comparison not equal"
    [:branch-byte-high :branch-byte-low] [:reference-type :reference-type] []
    nil nil nil [:label]]
   [167 :goto "Branch always"
    [:branch-byte-high :branch-byte-low] [] [] nil nil [:label]]
   [168 :jsr "Jump subroutine"
    [:branch-byte-high :branch-byte-low] [] [:address]]
   [169 :ret "Return from subroutine" [] [:return-address] []]
   [170 :tableswitch "Access jump table by index and jump"
    [:4byte-align-pad :int-value :int-value :int-value :offsets] [:int-value] []
    []]
   [171 :lookupswitch "Access jump table by key match and jump"
    [:4byte-align-pad :int-value :int-value :match-offset-pairs] [:int-value] []
    []]
   [172 :ireturn "Return int from method" [] [:int-value] []]
   [173 :lreturn "Return long from method" [] [:long-value] []]
   [174 :freturn "Return float from method" [] [:float-value] []]
   [175 :dreturn "Return double from method" [] [:double-value] []]
   [176 :areturn "Return reference from method" [] [:object-ref] []]
   [177 :return "Return void from method" [] [] []]
   [178 :getstatic "Get static field from class"
    [:const-index-high :const-index-low] [] [:value]]
   [179 :putstatic "Set static field in class"
    [:const-index-high :const-index-low] [:value] []]
   [180 :getfield "Fetch field from object"
    [:const-index-high :const-index-low] [:object-ref] [:value]]
   [181 :putfield "Set field in object"
    [:const-index-high :const-index-low] [:object-ref :value] []]
   [182 :invokevirtual "Invoke instance method; dispatch based on class"
    [:const-index-high :const-index-low] [:object-ref :args] [:value]]
   [183 :invokespecial "Invoke instance method; special handling for superclass, private, and instance initialization method invocations"
    [:const-index-high :const-index-low] [:object-ref :args] [:value]]
   [184 :invokestatic "Invoke a class (static) method"
    [:const-index-high :const-index-low] [:object-ref :args] [:value]]
   [185 :invokeinterface "Invoke interface method"
    [:const-index-high :const-index-low :count :zero]
    [:object-ref :args] [:value]]
   [186 :xxxunusedxxx1]
   [187 :new "Create new object"
    [:const-index-high :const-index-low] [] [:object-ref]]
   [188 :newarray "Create new array"
    [:int-value] [] [:array-ref]]
   [189 :anewarray "Create new array of reference"
    [:const-index-high :const-index-low] [:int-value] [:array-ref]]
   [190 :arraylength "Get length of array" [] [:array-ref] [:int-value]]
   [191 :athrow "Throw exception or error" [] [:object-ref] [:object-ref]]
   [192 :checkcast "Check whether object is of given type"
    [:const-index-high :const-index-low] [:object-ref] [:object-ref]]
   [193 :instanceof "Determine if object is of given type"
    [:const-index-high :const-index-low] [:object-ref] [:int-value]]
   [194 :monitorenter "Enter monitor for object" [] [:object-ref] []]
   [195 :monitorexit "Exit monitor for object" [] [:object-ref] []]
   [196 :wide "Extend local variable index by additional bytes"
    [:opcode :local-index-high :local-index-low :const-index-high
     :const-index-low] [] []]
   [197 :multianewarray "Create new multidimensional array"
    [:const-index-high :const-index-low :int-value] [:int-values] [:array-ref]]
   [198 :ifnull "Branch if reference null"
    [:branch-byte-high :branch-byte-low] [:object-ref] []]
   [199 :ifnonnull "Branch if reference not null"
    [:branch-byte-high :branch-byte-low] [:object-ref] []]
   [200 :goto_w "Branch always (wide index)"
    [:branch-byte-high-hi :branch-byte-low
     :branch-byte-low-high :branch-byte-low-low] [] []]
   [201 :jsr_w "Jump subroutine (wide index)"
    [:branch-byte-high-hi :branch-byte-low
     :branch-byte-low-high :branch-byte-low-low] [] []]
   [202 :breakpoint "Breakpoint" [] [] []]
   [254 :impdep1 "Implementation dependent" [] [] []]
   [255 :impdep2 "Implementation dependent" [] [] []]])

(defn make-op
  [opcode mnemonic args]
  ^{:type ::op
    ::scope ::opcode
    ::parent-scopes #{:code}
    ::collection :code}
  {:opcode opcode
   :mnemonic mnemonic
   :args args})

(defn generate-opcode-function
  "Generate an opcode function"
  [[opcode mnemonic desc args stack-in stack-out implicit-args size-fn
    asm-args]]
  (let [arg-names (map (comp symbol name) (or asm-args args))]
    `(defn ~(symbol (name mnemonic)) [~@arg-names]
       (make-op ~opcode ~mnemonic ~(vec arg-names)))))

(defmacro generate-opcode-functions
  "Generate a function for each opcode."
  []
  (let [fns (for [opcode opcodes]
              (generate-opcode-function opcode))]
    `(do ~@fns)))

(generate-opcode-functions)

;;; ## Psuedo ops

(defn label [label-kw]
  (make-op :label :label [label-kw]))
