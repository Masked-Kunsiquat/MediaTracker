# private-data

Real personal exports used to develop importers against. **Nothing in here is committed** — the
whole directory is gitignored, and this README is the only exception.

Put a Prime Video viewing history export here (#153). It is read by hand during development; no
build step or test depends on it, so a clone without it works normally.

Fixtures that are *safe* to commit — invented data, small, stable — belong in `docs/sample-data`
instead. The line between the two is provenance, not size: if a real person watched it, read it or
bought it, it goes here.
