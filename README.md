# hive-olympus-universe

Olympus on the universe canvas. A manifest-only
[hive-olympus](../hive-olympus) harness brick that posts the agent grid into
[hive-universe](../hive-universe)'s conversation, one utterance per tab.

```clojure
{:addon/id "hive.olympus.universe"
 :addon/init-ns "hive-olympus.harness"
 :addon/init-fn "addon-ctor"
 :addon/config {:olympus/host "hive.universe"}
 :addon/dependencies #{"hive.olympus" "hive.universe"}}
```

No source. The brick is the manifest; `hive-olympus.harness` does the work.

## The route

`hive.universe` contributes `:vessel/target` and no `:vessel/dispatch!`, so the
brick delivers through route 3: the host's bare target, lowered by hive-vessel's
standard registry. That lowering happens on **this** classpath, which is why
`io.github.hive-agi/hive-vessel` is declared here and not left to the host. The
universe is an empty box on purpose and does not carry hive-vessel.

The dialect is `:text`, so what reaches the canvas is already-rendered lines.
A canvas is a scrolling conversation with no windows, so each panel's lines are
prefixed with `[olympus/tab-1]` and posted as one utterance.

## Watch it

```bash
clojure -Sdeps "$(cat local.deps.edn)" -M:demo          # a synthetic swarm, moving
clojure -Sdeps "$(cat local.deps.edn)" -M:demo --live   # the real hive swarm
```

Boots the manager's default universe, installs it as the say-channel, mounts
`hive.olympus` and this brick against a host exposing only `:vessel/target`, and
hands over the terminal. The grid arrives as `[olympus/tab-1]` utterances and
repaints as the swarm changes. `/quit` to leave; the universe keeps running.

`--live` drops the synthetic roster, so Olympus falls back to its default: the
hive swarm, lings at depth 1. Outside a hive that is an empty grid, which is
itself the honest answer.

## Tests

```bash
clojure -M:test                                   # hermetic: published deps only
clojure -Sdeps "$(cat local.deps.edn)" -M:proof   # the above, plus the live canvas proof
```

`-M:test` resolves entirely from Clojars and needs no checkout. It mounts the
brick against a recording `:text` stub, then against the real `hive.olympus`
core with six agents on two tabs.

`-M:proof` additionally needs the hive-universe checkout, because it drives the
real refresh loop onto a real canvas and reads the transcript back through
`hive-universe.port`. The canvas is hive-universe's `AtomUniverse`, the double
its own port-conformance suite holds to the port alongside the in-process and
remote implementations; the only thing faked is the nREPL server the grid never
touches. The suite's alias puts `../hive-universe/test` on the path to reach it,
so the proof uses that proven double rather than a second hand-rolled one.

tools.deps warns that `../hive-universe/test` is external to the project. That
is the point: the alias is for a checkout, the way `local.deps.edn` is, and a
copy of the double here would be the thing the double exists to prevent.

Three things it establishes, each stated as a mutation that kills it:

| Proof | Falsified by |
|---|---|
| Six agents reach the canvas as two utterances, and ten further ticks add nothing | — |
| A tick before any universe is installed does not silence the grid forever | recording the panel key before `say!` returns |
| A retry after a partial failure does not repost the tab that already landed | removing the canvas key |

The second is the defect the proof was written to find: `channel/say!` throws
until `install!`, the refresh loop can tick before that, and a key written on
intent rather than on arrival made the grid invisible for the life of the JVM.
Fixed in hive-universe; the test here is the end-to-end statement of it.

The third measures what the canvas key is actually for. Olympus's presenter seat
already sends only the delta since its own last successful delivery, so an
unchanged tick is silent with or without the key — removing the key changes
nothing on the ordinary path. It changes everything on the retry path: a
presenter that threw part-way re-offers the whole delta every tick, and without
the key half a second of retries put ten copies of tab 1 on the canvas.

## License

MIT. See [LICENSE](LICENSE).
