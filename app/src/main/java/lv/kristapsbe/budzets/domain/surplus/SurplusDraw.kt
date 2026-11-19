package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.model.sumMoney

/**
 * Taking an amount from the saved surplus as a whole rather than from one pocket (Q47): what "Pay
 * off" and "Add surplus" move into a category.
 *
 * The sources are the pockets above zero other than the destination. Unallocated gives first,
 * since it belongs to no category; then the categories, the fullest first, so as few pockets as
 * possible give. Equal balances go by category id, so the plan doesn't change between calls.
 */
object SurplusDraw {

    /** The pockets [to] can be paid from, in the order they give. */
    fun sources(balances: Map<SurplusPocket, Money>, to: SurplusPocket?): List<Pair<SurplusPocket, Money>> =
        balances.filter { (pocket, balance) -> pocket != to && balance.isPositive }
            .toList()
            .sortedWith(
                compareBy<Pair<SurplusPocket, Money>> { (pocket, _) -> pocket !is SurplusPocket.Unallocated }
                    .thenByDescending { (_, balance) -> balance }
                    .thenBy { (pocket, _) -> (pocket as? SurplusPocket.Category)?.id?.value.orEmpty() },
            )

    /** Everything the saved surplus can give [to]. */
    fun available(balances: Map<SurplusPocket, Money>, to: SurplusPocket?): Money =
        sources(balances, to).map { it.second }.sumMoney()

    /**
     * How much each source gives towards [amount], in the order they give; only the pockets that
     * give something. Null when [amount] isn't positive or is more than [available].
     */
    fun plan(balances: Map<SurplusPocket, Money>, to: SurplusPocket?, amount: Money): List<Pair<SurplusPocket, Money>>? {
        if (!amount.isPositive) return null
        var left = amount
        val parts = buildList {
            for ((pocket, balance) in sources(balances, to)) {
                if (!left.isPositive) break
                val part = minOf(balance, left)
                add(pocket to part)
                left -= part
            }
        }
        return parts.takeIf { left.isZero }
    }
}
