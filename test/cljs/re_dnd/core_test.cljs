(ns re-dnd.core-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [re-dnd.core :as core]))

(deftest fake-test
  (testing "fake description"
    (is (= 1 2))))
