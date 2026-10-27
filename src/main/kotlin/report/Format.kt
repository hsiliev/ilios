package ilios.report

import kotlin.math.round

private const val TENTH = 10
private const val CENT = 100

/** Rounds to one decimal. */
@Suppress("FunctionNameMinLength")
internal fun r1(x: Double) = round(x * TENTH) / TENTH

/** Rounds to cents. */
@Suppress("FunctionNameMinLength")
internal fun r2(x: Double) = round(x * CENT) / CENT

/** One decimal with the Bulgarian decimal comma, for display texts. */
@Suppress("FunctionNameMinLength")
internal fun bg(x: Double) = r1(x).toString().replace('.', ',')

/** Two decimals with the Bulgarian decimal comma (prices). */
internal fun bg2(x: Double) = "%.2f".format(java.util.Locale.ROOT, x).replace('.', ',')
