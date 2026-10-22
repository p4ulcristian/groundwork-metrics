# Counting never throws

**Decision:** `add-to-counter!`, `set-gauge!`, `add-to-gauge!` and `record-in-histogram!` return nil and never throw. A call that can't count counts nothing and is written to the app's log at `:error`, once per metric and mistake:

| event | when |
|---|---|
| `:meter/metric-not-declared` | no metric of that name is declared |
| `:meter/wrong-type` | the metric is of another type, or is read (`:read`), not counted |
| `:meter/bad-value` | the value isn't a finite number, or a counter's is below 0 |
| `:meter/bad-labels` | the labels aren't a map of exactly the declared names |
| `:meter/counting-failed` | anything else threw |

Every function takes all its arguments: the labels (`{}` for none) and the amount, `1` written out.

**Why:** counting sits in every path, the failing ones too: a handler's catch, a background job that gave up. A throw there would replace the failure with one about counting it, as a throwing log call would ("A log call never throws", logbook). These are mistakes in code, so `:error`: kept for a monitor to pull. Once per metric and mistake, because the same mistake comes on every call.

**The price:** a mistake doesn't stop the program; it is seen in the errors, not in a failing test, unless a test reads the log.

**Instead of:**
- throwing: a metrics mistake would break a request
- logging every bad call: one wrong label in a hot path is a line per request
