(ns clasm.classdata-test
  (:refer-clojure :exclude [class pop])
  (:use
   clasm.classdata
   clojure.test))

(deftest class-test
  (testing "empty class"
    (is (= {:name 'myClass
            :access-flags []
            :methods []
            :fields []
            :constant-pool []
            :attributes []}
           (class myClass))))
  (testing "class with access flags"
    (is (= [:public :final]
           (:access-flags (class myClass :public :final))))))

(deftest edit-test
  (testing "empty class"
    (is (= (class myClass) (edit (class myClass))))))

(deftest field-test
  (testing "simple field"
    (is (= {:name 'myClass
            :access-flags []
            :methods []
            :fields [{:name 'fred :type String :access-flags []
                      :attributes []}]
            :constant-pool []
            :attributes []}
           (edit (class myClass)
             (add (field String fred))))))
  (testing "field with constant-value"
    (is (= {:name 'myClass
            :access-flags []
            :methods []
            :fields [{:name 'fred :type String :access-flags []
                      :attributes [{:name :constant-value :args [1]
                                    :attributes []}]}]
            :constant-pool []
            :attributes []}
           (edit (class myClass)
             (add (field String fred)
               (add (constant-value 1))))))))

(deftest method-test
  (is (= {:name 'myClass
          :methods [{:name 'myMethod
                     :args []
                     :return-type :int
                     :access-flags []
                     :attributes []}]
          :access-flags []
          :fields []
          :constant-pool []
          :attributes []}
         (edit (class myClass)
           (add (method int myMethod [])))))
  (is (= {:name 'myClass
          :methods [{:name 'myMethod
                     :args []
                     :return-type :int
                     :access-flags []
                     :attributes [{:code [{:opcode 0 :mnemonic :nop :args []}]
                                   :exception_table []
                                   :name :code
                                   :args []
                                   :attributes []}]}]
          :access-flags []
          :fields []
          :constant-pool []
          :attributes []}
         (edit (class myClass)
           (add (method int myMethod [])
             (add (code)
               (nop)))))))


(deftest goto-and-label-test
  (is (= {:name 'SpinTest
          :access-flags []
          :methods [{:return-type :void
                     :name 'spin
                     :args []
                     :access-flags []
                     :attributes
                     [{:exception_table []
                       :name :code
                       :args []
                       :attributes []
                       :code [{:opcode 3 :mnemonic :iconst_0 :args []}
                              {:opcode 60 :mnemonic :istore_1 :args []}
                              {:opcode 167 :mnemonic :goto :args [:start-loop]}
                              {:opcode :label :mnemonic :label :args [:loop]}
                              {:opcode 132 :mnemonic :iinc :args [1 1]}
                              {:opcode :label :mnemonic :label
                               :args [:start-loop]}
                              {:opcode 21 :mnemonic :iload :args [1]}
                              {:opcode 16 :mnemonic :bipush :args [100]}
                              {:opcode 161 :mnemonic :if_icmplt :args [:loop]}
                              {:opcode 177 :mnemonic :return :args []}]}]}]
          :fields []
          :constant-pool []
          :attributes []}
         (edit (class SpinTest)
           (add (method void spin [])
             (add (code)
               (iconst_0)               ; some comment
               (istore_1)
               (goto :start-loop)
               (label :loop)
               (iinc 1 1)
               (label :start-loop)
               (iload 1)
               (bipush 100)
               (if_icmplt :loop)
               (return))))))
  (testing "label as keyword"
    (is (= (edit (class SpinTest)
             (add (method void spin [])
               (add (code)
                 (iconst_0)
                 (istore_1)
                 (goto :start-loop)
                 (label :loop)
                 (iinc 1 1)
                 (label :start-loop)
                 (iload 1)
                 (bipush 100)
                 (if_icmplt :loop)
                 (return))))
           (edit (class SpinTest)
             (add (method void spin [])
               (add (code)
                 (iconst_0)
                 (istore_1)
                 (goto :start-loop)
                 :loop
                 (iinc 1 1)
                 :start-loop
                 (iload 1)
                 (bipush 100)
                 (if_icmplt :loop)
                 (return))))))))

(deftest constant-test
  (is (= {:constant-pool [{:label :x, :tag 100000, :values nil}
                          {:label :minus-one, :tag 4294967295, :values nil}
                          {:label :some-const, :tag 2.2, :values nil}]
          :name 'ConstTest
          :access-flags []
          :methods [{:return-type :void
                     :name 'useManyNumeric
                     :args []
                     :access-flags []
                     :attributes
                     [{:exception_table []
                       :name :code
                       :args []
                       :attributes []
                       :code [{:opcode 16, :mnemonic :bipush, :args [100]}
                              {:opcode 60, :mnemonic :istore_1, :args []}
                              {:opcode 18, :mnemonic :ldc, :args [1]}
                              {:opcode 61, :mnemonic :istore_2, :args []}
                              {:opcode 10, :mnemonic :lconst_1, :args []}
                              {:opcode 66, :mnemonic :lstore_3, :args []}
                              {:opcode 20, :mnemonic :ldc2_w, :args [6]}
                              {:opcode 55, :mnemonic :lstore, :args [5]}
                              {:opcode 20, :mnemonic :ldc2_w, :args [8]}
                              {:opcode 57, :mnemonic :dstore, :args [7]}]}]}]
          :fields []
          :attributes []}
         (edit (class ConstTest)
           (add (method void useManyNumeric [])
             (add (code)
               (constant :x 100000)
               (constant :minus-one 0xffffffff)
               (constant :some-const 2.20000)
               (bipush 100)
               (istore_1)
               (ldc 1)
               (istore_2)
               (lconst_1)
               (lstore_3)
               (ldc2_w 6)
               (lstore 5)
               (ldc2_w 8)
               (dstore 7)))))))

(deftest line-number-test
  (is (= {:constant-pool []
          :name 'LineNumTest
          :access-flags []
          :methods [{:return-type :void
                     :name 'withLineNums
                     :args []
                     :access-flags []
                     :attributes
                     [{:exception_table []
                       :name :code
                       :args []
                       :attributes [{:line_number_table
                                     [{:line_number 123,:start_pc 1}
                                      {:line_number 129,:start_pc 3}],
                                     :name :line-number-table,
                                     :args [],
                                     :attributes []}]
                       :code [{:opcode 16, :mnemonic :bipush, :args [100]}]}]}]
          :fields []
          :attributes []}
         (edit (class LineNumTest)
           (add (method void withLineNums [])
             (add (code)
               (add (line-number-table)
                 (line-number 1 123)
                 (line-number 3 129))
               (bipush 100)))))))

(deftest exception-test
  (is (= {:constant-pool []
          :name 'LineNumTest
          :access-flags []
          :methods [{:return-type :void
                     :name 'withLineNums
                     :args []
                     :access-flags []
                     :attributes
                     [{:name :exceptions,
                       :args [],
                       :attributes []
                       :exception_table
                       [{:exception-type Exception}]}
                      {:name :code
                       :args []
                       :attributes []
                       :code [{:opcode 16, :mnemonic :bipush, :args [100]}]
                       :exception_table []}]}]
          :fields []
          :attributes []}
         (edit (class LineNumTest)
           (add (method void withLineNums [])
             (add (exceptions)
               (exception Exception))
             (add (code)
               (bipush 100)))))))

(deftest smap-test
  (is (= {:constant-pool []
          :name 'LineNumTest
          :access-flags []
          :methods
          [{:return-type :void
            :name 'withLineNums
            :args []
            :access-flags []
            :attributes
            [{:name :code
              :args []
              :attributes []
              :code [{:opcode 16, :mnemonic :bipush, :args [100]}]
              :exception_table []}]}]
          :fields []
          :attributes [{:name :source-debug-extension,
                        :args [],
                        :attributes []
                        :debug_extensions
                        [{:smap
                          (str
                           "SMAP\nfred.java\nClojure\n*S Clojure\n*F\n"
                           "+ 1 fred\nsome/path/fred\n*L\n10:20\n*E")}]}]}
         (edit (class LineNumTest)
           (add (method void withLineNums [])
             (add (source-debug-extension)
               (smap "fred" "Clojure"
                     [{:stratum "Clojure"
                       :source-files [{:source-name "fred"
                                       :source-path "some/path/fred"}]
                       :lines [{:input-start-line 10 :output-start-line 20}]}]))
             (add (code)
               (bipush 100)))))))

(deftest readme-test
  (is (= {:name 'ReadmeExample
          :access-flags []
          :methods [{:return-type :void
                     :name 'aFirstMethod
                     :args []
                     :access-flags []
                     :attributes
                     [{:code
                       [{:opcode 16 :mnemonic :bipush :args [100]}
                        {:opcode 177 :mnemonic :return :args []}]
                       :exception_table []
                       :name :code
                       :args []
                       :attributes []}]}]
          :fields []
          :constant-pool []
          :attributes
          [{:debug_extensions
            [{:smap
              (str "SMAP\nfred.java\nClojure\n*S Clojure\n*F\n+ 1 fred\n"
                   "some/path/fred\n*L\n10:20\n*E")}]
            :name :source-debug-extension
            :args []
            :attributes []}]}
         (edit (class ReadmeExample)
           (add (method void aFirstMethod [])
             (add (source-debug-extension)
               (smap "fred" "Clojure"
                     [{:stratum "Clojure"
                       :source-files [{:source-name "fred"
                                       :source-path "some/path/fred"}]
                       :lines [{:input-start-line 10 :output-start-line 20}]}]))
             (add (code)
               (bipush 100)
               (return)))))))
