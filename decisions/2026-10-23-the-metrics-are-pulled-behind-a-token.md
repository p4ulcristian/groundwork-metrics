# The metrics are pulled, behind a bearer token

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** Prometheus pulls the metrics; meter sends nothing anywhere. `make-metrics-routes` makes one GET, as reitit route data whose handler is a plain Ring handler, at the path the app's settings say, answered only to the bearer of the token they say (`Authorization: Bearer …`):

- with the token: 200, `text/plain; version=0.0.4; charset=utf-8`, `cache-control: no-store`, the text of `write-metrics-text`;
- without it, or with any other: 401, `www-authenticate: Bearer`, `This address needs the metrics token.`

The token is compared in constant time: the header and `"Bearer " + token` are each hashed with SHA-256 and the hashes compared with `MessageDigest/isEqual`, so the time doesn't tell how long the token is or where a guess first differs (the same as house's errors route). Both settings are required (`routes-settings-shape`): there is no route without a token.

**Why:** Iron Rainbow serves `/metrics` this way, to a Prometheus that pulls it over WireGuard with a bearer token kept as a secret. Pulled, the app keeps no address of a metrics server and nothing is lost when Prometheus is down: it reads the counters when it is back. The metrics name every message and background job kind an app has, its traffic and its failures: not for anyone who finds the address. The route is data, so house puts it beside its other routes, outside sessions, access and rate limits.

**The price:** the token is a secret the app must keep and Prometheus must be given. The text isn't compressed by meter; with closed sets it stays small.

**Instead of:**
- pushing to a gateway: the app would need its address, and a counter's history would depend on each push arriving
- no token, trusting the network: one wrong firewall rule shows the app's insides
- 404 when no token is set, as Iron Rainbow does: a route without a token would be a setting meaning "no route"; an app that doesn't want it doesn't add it
