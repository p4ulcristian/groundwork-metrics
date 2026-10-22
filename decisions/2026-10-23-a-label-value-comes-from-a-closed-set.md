# Every label value comes from a closed set; a stranger is "other"

**Decision:** every label is declared with the set of values it may take, and only those are ever written. A value outside the set, whatever it is (text a request carried, a keyword no one declared, nil), is counted under `"other"`: never a series of its own. The first stranger for each metric and label is written to the app's log at `:warn`, `:meter/label-value-not-in-set`, `{:meter/metric … :meter/label … :meter/value … :meter/counted-as "other"}`; later ones, of any value, are not. `"other"` is meter's own: no declared set may hold it.

How many series a declaration can make is known before it counts: each label's values plus `"other"`, multiplied (a histogram's each times its buckets, `+Inf`, sum and count). The settings' `:most-series-per-metric` is the most one declaration may make; a bigger one is refused at the start.

**Why:** in Iron Rainbow, on 2026-10-21, the HTTP metrics' `path` label held the raw address of each request. Scanners asking for `/wp-admin`, `/.env` and thousands more gave it 3,561 values: 66,000 series, 12 to 22 MB on every scrape, about 98% of the app's outgoing traffic. A label that takes what a caller sent grows without end; one that takes a value from a set the code wrote does not. Iron Rainbow fixed it by hand, for each label (the route template, the known endpoint names, `"unknown"`). meter makes it the only way a label can be declared. Logging once per metric and label says a stranger came without letting strangers fill the log the way they filled the metrics.

**The price:** a value the code forgot to declare is counted under `"other"` until someone reads the warning and adds it. Only the first stranger is named in the log. A label whose values aren't known at the start (an id, an address) can't be a label: that is the point.

**Instead of:**
- open labels with a cap on series: the cap hides which series were dropped, and the first values to arrive, scanners' perhaps, win
- throwing on a stranger: the value usually comes from outside, in a request's path
- dropping the count: totals would no longer add up
- logging every stranger: a scanner's thousands of addresses would move from the metrics into the log
