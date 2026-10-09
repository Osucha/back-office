package pl.mos.backoffice.shared

import java.math.BigDecimal
import java.math.RoundingMode

/** Monetary amount with 6 decimal places, enough for sub-cent LLM pricing. */
data class Money(val amount: BigDecimal, val currency: String = "USD") : Comparable<Money> {

    init {
        require(currency.length == 3) { "currency must be an ISO 4217 code, got $currency" }
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount - other.amount, currency)
    }

    operator fun times(factor: BigDecimal): Money = Money(amount * factor, currency)

    operator fun div(divisor: Int): Money =
        Money(amount.divide(BigDecimal(divisor), SCALE, RoundingMode.HALF_UP), currency)

    fun isZero(): Boolean = amount.signum() == 0

    fun rounded(): Money = Money(amount.setScale(SCALE, RoundingMode.HALF_UP), currency)

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) { "currency mismatch: $currency vs ${other.currency}" }
    }

    companion object {
        const val SCALE = 6
        val ZERO = Money(BigDecimal.ZERO)
        fun usd(amount: Double): Money = Money(BigDecimal.valueOf(amount)).rounded()
        fun usd(amount: String): Money = Money(BigDecimal(amount)).rounded()
        fun sum(values: Iterable<Money>): Money = values.fold(ZERO) { acc, m -> if (acc.isZero()) m else acc + m }
    }
}
