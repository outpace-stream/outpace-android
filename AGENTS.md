# Repository guidelines

Use main as the base with feat/, fix/, docs/, test/ or chore/ topic branches.
The Gradle project lives at the repository root. Engine binaries and HTTP API changes belong
in outpace-stream/outpace-engine; this app downloads published, unmodified engine releases.
Keep engine polling, log buffers, version storage and restart attempts bounded.
No content ids, infohashes, stream names, private captures, secrets, keystores or local SDK state
may be committed. Tests use 0123456789abcdef0123456789abcdef01234567 when valid hex is required.
Run ./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease and
python3 tools/hygiene/check_identifiers.py. Update docs with user-visible changes.
