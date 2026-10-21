#!/bin/bash
run() { clojure -M:dev -i probe/castsite.clj -e "$1"; }
D=test/issues
run "(probe.castsite/-main2 \"$D/231/b10211330-R2-41-2.edn.gz\" 13332900 13333250 1)"
run "(probe.castsite/-main3 \"$D/231/b10211330-R2-41-2.edn.gz\" 13332950)"
run "(probe.castsite/-main3 \"$D/231/b10211330-R2-41-2.edn.gz\" 13333100)"
