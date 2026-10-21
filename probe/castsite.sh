#!/bin/bash
# castsite probes
run() { clojure -M:dev -i probe/castsite.clj -e "$1"; }
D=test/issues
run "(probe.castsite/-main2 \"$D/45/b10211330-R3-25-3.edn.gz\" 5040400 5041400 1)"
run "(probe.castsite/-main2 \"$D/231/b10211330-R2-41-2.edn.gz\" 13333700 13334300 1)"
run "(probe.castsite/-main3 \"$D/41/b10211330-real-1-s1-Clj_r09.edn.gz\" 1792341022900 'clojurecraft.build/hunt-target 'clojurecraft.build/lava-hunt 'clojurecraft.build/lava-walk)"
run "(probe.castsite/-main3 \"$D/41/b10211330-real-4-s2-Clj_r21.edn.gz\" 1792481009248 'clojurecraft.build/hunt-target 'clojurecraft.build/lava-hunt)"
