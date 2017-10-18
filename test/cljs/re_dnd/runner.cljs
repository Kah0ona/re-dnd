(ns re-dnd.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [re-dnd.core-test]))

(doo-tests 're-dnd.core-test)
