package ru.pomoshchnik.elektrika

/** All values are integer kopeks, never floating-point money. */
data class EstimateLine(val priceKopeks: Long?, val quantity: Int)
data class EstimateTotal(val knownKopeks: Long, val unknownLines: Int)

object EstimateMath {
    fun calculate(lines: List<EstimateLine>): EstimateTotal {
        var known = 0L
        var missing = 0
        for (line in lines) {
            require(line.quantity in 1..100_000) { "Некорректное количество" }
            val price = line.priceKopeks
            if (price == null) {
                missing++
            } else {
                require(price >= 0) { "Отрицательная цена" }
                known = Math.addExact(known, Math.multiplyExact(price, line.quantity.toLong()))
            }
        }
        return EstimateTotal(known, missing)
    }
}
