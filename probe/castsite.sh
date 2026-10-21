#!/bin/bash
# castsite probes: a tick trace and asks at the moments of each failure
run() { clojure -M:dev -i probe/castsite.clj -e "$1"; }
D=test/issues
run "(probe.castsite/-main3 \"$D/45/b10211330-R1-46-2.edn.gz\" 16374150)"
run "(probe.castsite/-main2 \"$D/45/b10211330-R1-46-2.edn.gz\" 16374200 16380000 10)"
run "(probe.castsite/-main3 \"$D/45/b10211330-R3-25-3.edn.gz\" 5040400 clojurecraft.trap/dry-land?)"
run "(probe.castsite/-main2 \"$D/45/b10211330-R3-25-3.edn.gz\" 5040000 5043000 5)"
run "(probe.castsite/-main2 \"$D/45/b10211330-R3-25-3.edn.gz\" 5079250 5082000 10)"
run "(probe.castsite/-main2 \"$D/231/b10211330-R2-41-2.edn.gz\" 13333200 13338000 2)"
run "(probe.castsite/-main3 \"$D/41/b10211330-real-1-s1-Clj_r09.edn.gz\" 1792341022900 clojurecraft.build/hunt-target clojurecraft.build/lava-hunt clojurecraft.build/lava-walk)"
run "(probe.castsite/-main3 \"$D/41/b10211330-real-4-s2-Clj_r21.edn.gz\" 1792481009248 clojurecraft.build/hunt-target clojurecraft.build/lava-hunt)"
run "(probe.castsite/-main3 \"$D/130/b10211330-real-6-s2-Clj_r12.edn.gz\" 1792413529600)"
run "(probe.castsite/-main3 \"$D/130/b10211330-real-6-s2-Clj_r12.edn.gz\" 1792413530300)"
run "(probe.castsite/-main3 \"$D/130/b10211330-real-8-s1-Clj_r13.edn.gz\" 1792562589700)"
run "(probe.castsite/-main3 \"$D/130/b10211330-real-8-s1-Clj_r13.edn.gz\" 1792562606700)"
