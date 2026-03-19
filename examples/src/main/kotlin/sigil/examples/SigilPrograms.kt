package sigil.examples

object SigilPrograms {

    // Arithmetic utilities
    val MATH = """
        fn abs(x: Int) -> Int {
            if x < 0 then -x else x
        }

        fn clamp(x: Int, lo: Int, hi: Int) -> Int {
            requires lo <= hi
            ensures result >= lo
            ensures result <= hi
            if x < lo then lo else if x > hi then hi else x
        }

        fn max(a: Int, b: Int) -> Int {
            ensures result >= a
            ensures result >= b
            if a > b then a else b
        }

        fn min(a: Int, b: Int) -> Int {
            ensures result <= a
            ensures result <= b
            if a < b then a else b
        }
    """.trimIndent()

    // Validation functions with contracts
    val VALIDATORS = """
        fn is_positive(x: Int) -> Bool {
            x > 0
        }

        fn is_non_negative(x: Int) -> Bool {
            x >= 0
        }

        fn is_in_range(x: Int, lo: Int, hi: Int) -> Bool {
            requires lo <= hi
            if x < lo then false else if x > hi then false else true
        }

        fn safe_divide(a: Int, b: Int) -> Int {
            requires b != 0
            a / b
        }
    """.trimIndent()

    // Scoring/ranking computations (simulating backend logic)
    val SCORING = """
        fn weighted_score(base: Int, bonus: Int, multiplier: Int) -> Int {
            requires multiplier > 0
            ensures result >= 0
            let raw = base * multiplier + bonus
            if raw < 0 then 0 else raw
        }

        fn normalize_score(score: Int, max_score: Int) -> Int {
            requires max_score > 0
            ensures result >= 0
            ensures result <= 100
            let pct = score * 100 / max_score
            if pct < 0 then 0 else if pct > 100 then 100 else pct
        }

        fn rank_tier(score: Int) -> Int {
            if score >= 90 then 1 else if score >= 70 then 2 else if score >= 50 then 3 else 4
        }
    """.trimIndent()
}
