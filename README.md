# clasm

A Clojure library designed to convert jvm assembler into bytecode.

## Current Status

This is in an early state of implementation. Not usable yet.

## Usage

```clj
(use 'clasm.classdata)
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
      (return))))
```

## Install

Put `[clasm "0.1.0-SNAPSHOT"]` into the `:dependencies` vector of your
`project.clj` file.

## License

Copyright © 2012 Hugo Duncan

Distributed under the Eclipse Public License.
