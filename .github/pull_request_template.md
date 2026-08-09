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

<!--
Which of these actually ran, with real results -- not which ones should have.

- [ ] `./gradlew :shared:jvmTest :shared:testDebugUnitTest`  (CI runs this)
- [ ] `./gradlew :app:assembleDebug`  (CI runs this)
- [ ] `./gradlew :app:connectedDebugAndroidTest`  (needs a device; CI does NOT run this)
- [ ] Ran on a real device

If this touches a Compose screen, the last two matter most: the build passing says nothing about
whether the screen works. See AGENTS.md §7.
-->

## Known gaps

<!--
Anything deliberately left undone, unverified, or accepted as a risk. Saying so here is much
cheaper than someone finding it later and having to guess whether it was intentional.
-->
