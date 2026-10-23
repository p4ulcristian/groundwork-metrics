# A library takes the app's meter as `:meter`, and declares its own where it starts

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** a manor library that counts something takes the app's meter as a required `:meter` key in the options of the function where it starts counting, beside `:log`, never nil: mailroom's `make-routes-for-messages`, cellar's `start-pool!` and `start-listening!`, backroom's `start-workers!`, doorbell's `start-listening!`. That function declares the library's metrics on it (`declare-metrics!`) before anything runs:

- **its names** in its own namespace (`:mailroom/…`, `:cellar/…`);
- **each label's set** from what the library already holds when it is called: the handler table's names, the queries made so far, the kinds of background jobs, the tables told about; a value outside it is `"other"`;
- **a histogram's buckets** from the library's settings, required, one key per histogram (`:metric-buckets {:answer-seconds [...]}`): what a query takes and what a background job takes are the app's to say.

A function that counts nothing takes no `:meter`, though it takes `:log` (mailroom's `start-server!` and `answer-message`, cellar's `migrate!`). logbook takes none: meter depends on it; the app reads `logbook/read-counts` as read metrics.

`declare-metrics!` refuses a value `make-meter` didn't make, `:meter/not-a-meter` (a meter carries a mark in its metadata), so the library's start refuses it, as `logbook/with-context` refuses a log logbook didn't make. Since v0.1.1.

**Why:** the meter is a value the app hands down, as it hands its log ("meter is its own library"); declaring where the library starts is where it knows its sets, and the whole declaration is checked there, with every other setting, before a request comes. The buckets are settings for the reason they are part of a declaration: no default fits every app's queries and jobs.

**The price:** each of those functions takes one key more and each library one settings key more; every caller changes. A declaration made again differently is refused, so a library's start runs once per meter where its sets or its read functions would differ (cellar: a meter per pool).

**Instead of:**
- `:meter` optional, nil to count nothing: a key that is sometimes there is a hidden default; a test makes a meter as it makes a log
- `:meter` on every function that takes `:log`: a key with nothing to do
- a `meter?` function in the api: one more name, and each library would check it itself; `declare-metrics!` is the first thing each calls
