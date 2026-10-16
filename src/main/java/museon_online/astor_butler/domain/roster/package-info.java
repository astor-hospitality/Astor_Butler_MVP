/**
 * Who is on shift: the schedule the staff posts as a photo to the «График» topic is read by the vision model, its
 * names are matched to the staff directory, and once a person presses «Верно» it answers {@link
 * museon_online.astor_butler.domain.roster.StaffRoster}. Behind {@code astor.staff.schedule.enabled}; off, the topic
 * is not touched and the roster knows nobody. See docs/operations/STAFF_SHIFT_SCHEDULE.md.
 */
package museon_online.astor_butler.domain.roster;
