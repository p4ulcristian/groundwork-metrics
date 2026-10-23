# Secrets are safe's wrapped secrets

> Changes in part [The metrics are pulled, behind a bearer token](2026-10-23-the-metrics-are-pulled-behind-a-token.md) (the token is `safe/secret`, compared by `safe/equal?`). Since v0.1.3, on [safe](https://github.com/p4ulcristian/safe) v0.1.0.

**Decision:** the metrics route's `:token` is a secret of safe's, `[:token safe/secret]` (`#secret :meter/token` in the app's settings file). A request is answered when its `Authorization` header is `Bearer ` followed by text that `safe/equal?` finds equal to the token, in constant time (both hashed with SHA-256, then `MessageDigest/isEqual`); the `Bearer ` prefix is checked first, as it is no secret. Anything else is 401, as before. A token written out as text is refused, `:meter/bad-routes-settings` with `{:token ["should be a #secret, read from the settings' secrets source"]}`, and the refusal never holds the text. The tests make theirs with `safe/make-secret`.

**Why:** the settings are printed, logged and put in ex-data; a token in them as a string leaks by every one of those. Wrapped, it prints as `#secret :meter/token`, and meter never reveals it: `equal?` compares it inside safe. safe's [A secret stays wrapped until it is used](https://github.com/p4ulcristian/safe/blob/main/decisions/2026-10-23-a-secret-stays-wrapped-until-it-is-used.md) says how.

**Instead of:**
- a string, as before: every log of the settings is a leak
- revealing it to build `"Bearer " + token` and comparing the whole header: the text would leave the secret for a comparison safe already does
- meter's own hashing: safe's `equal?` is the one every library uses
