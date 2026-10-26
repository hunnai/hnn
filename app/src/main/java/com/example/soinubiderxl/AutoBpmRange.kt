package com.example.soinubiderxl

data class AutoBpmRange(val minimum: Int = 115, val maximum: Int = 135) {
    init {
        require(minimum in minimumOptions && maximum in maximumOptions)
        require(maximum - minimum == 20)
    }

    // Ambos extremos se mueven juntos: diferencia exacta de 20 BPM.
    fun withMinimum(value: Int): AutoBpmRange {
        require(value in minimumOptions)
        return AutoBpmRange(value, value + 20)
    }

    fun withMaximum(value: Int): AutoBpmRange {
        require(value in maximumOptions)
        return AutoBpmRange(value - 20, value)
    }

    companion object {
        val minimumOptions = (85..155 step 10).toList()
        val maximumOptions = (105..175 step 10).toList()
    }
}
