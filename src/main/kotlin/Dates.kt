package ilios

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The Monday of [day]'s week. */
fun mondayOf(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** The first day the stored history covers: the Monday of last week. */
fun historyStart(today: LocalDate): LocalDate = mondayOf(today).minusDays(WEEK_DAYS)
