<!--
Keep this short. The commit messages carry the detail; this is the summary a reviewer reads first.
Delete any section that does not apply rather than writing "N/A" in it.
-->

## What and why

<!-- What changed, and the reason it needed to. Prefer the reason: "what" is visible in the diff. -->

## Decisions worth knowing

<!--
Anything a reviewer would otherwise have to reverse-engineer: an alternative considered and
rejected, a constraint that forced the shape, a tradeoff deliberately accepted. If a decision
belongs in ROADMAP.md rather than only here, put it there too -- this template is not a substitute
for that file.
-->

## Testing

<!-- Tick what actually ran, not what should have. Leave a box unticked rather than tidying it. -->

- [ ] `./gradlew :shared:jvmTest :shared:testDebugUnitTest` — *CI runs this*
- [ ] `./gradlew :app:assembleDebug` — *CI runs this*
- [ ] `./gradlew :app:connectedDebugAndroidTest` — **needs a device; CI does NOT run this**
- [ ] Ran the affected screens on a real device

Compose screens tested (or n/a):

<!--
Name them. If this touches a screen and the last two boxes are unticked, say so under Known gaps --
the build passing says nothing about whether a screen works. Two bugs shipped that way before the
instrumented tests existed. See AGENTS.md §7.
-->

## Known gaps

<!--
Anything deliberately left undone, unverified, or accepted as a risk -- including any screen
changed here but not actually run. Saying so is much cheaper than someone finding it later and
having to guess whether it was intentional.
-->
