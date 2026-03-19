package sigil.ast

typealias Hash = String
typealias Alias = String

object PrimitiveTypes {
    const val INT = "#sigil:int"
    const val INT32 = "#sigil:i32"
    const val INT64 = "#sigil:i64"
    const val FLOAT64 = "#sigil:f64"
    const val BOOL = "#sigil:bool"
    const val STRING = "#sigil:string"
    const val UNIT = "#sigil:unit"
    const val BYTES = "#sigil:bytes"
    const val LIST = "#sigil:list"
    const val MAP = "#sigil:map"
    const val OPTION = "#sigil:option"
    const val RESULT = "#sigil:result"
}
