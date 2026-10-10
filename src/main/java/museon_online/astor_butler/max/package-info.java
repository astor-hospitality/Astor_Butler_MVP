/**
 * MAX messenger channel (VK, platform-api.max.ru): a second guest transport next to Telegram. Off unless
 * {@code ASTOR_MAX_ENABLED=true} and {@code MAX_BOT_TOKEN} are set; the FSM stays the single source of truth and
 * MAX, like Telegram, is only UI. Plan and phases: docs/architecture/MAX_ADAPTER_PLAN.md.
 */
package museon_online.astor_butler.max;
