# meter is its own library

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** manor's metrics are a library of their own, meter, in its own public repository, under the MIT license; an app takes it from git by tag. One public namespace, `meter.api`. Its `deps.edn` names only logbook (the app's log, handed in) and malli (the settings checked), and its tests run with nothing else on the classpath. It knows nothing of who uses it: no library's metric is in it, each declares its own on the meter it is handed.

The app makes one meter, `(make-meter settings {:log log})`, and serves it at one guarded GET; every library counts into that meter. The name: a house's meters are read from outside, as Prometheus reads `/metrics`.

**Why:** counting what an app does, and handing it to Prometheus, is one idea every library in manor needs and none owns. In a library of its own it can't grow a dependency on mailroom or cellar, and every library can take it.

**The price:** a change to meter is a new tag, and every library that counts moves to it on purpose.

**Instead of:**
- a namespace in house: no library under house could count without depending on house
- each library writing its own text: every one would repeat the format, the token check and the label rule
