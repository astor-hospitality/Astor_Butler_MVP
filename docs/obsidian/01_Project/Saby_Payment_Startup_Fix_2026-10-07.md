# Saby payment startup regression — 7 October 2026

On main ad394d6 (aeris-2026-10-07-rc1), a real Spring context reproduces
`SabyPaymentProvider: No default constructor found`, even with Saby disabled.
Two constructors require explicitly selecting the production constructor.

Added @Autowired and a regression test that refreshes a Spring context with real
properties and RestTemplateBuilder beans. The test fails before the annotation
and passes afterwards. Full Maven tests pass locally on Java 25.

This validates bean construction, not full production startup, DB migration or deployment.
Existing rc1 tag is unchanged. Production and live Telegram were not touched.

Concierge PR #24: local typecheck and 84 tests pass, but deploy review requests
canonical target-path protection before rsync --delete and enforced Compose isolation.
