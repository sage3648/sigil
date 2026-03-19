package sigil.ast

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LiteralValue {
    @Serializable @SerialName("int")
    data class IntLit(val value: Long) : LiteralValue()

    @Serializable @SerialName("float")
    data class FloatLit(val value: Double) : LiteralValue()

    @Serializable @SerialName("string")
    data class StringLit(val value: String) : LiteralValue()

    @Serializable @SerialName("bool")
    data class BoolLit(val value: Boolean) : LiteralValue()

    @Serializable @SerialName("unit")
    data object UnitLit : LiteralValue()
}

@Serializable
sealed class Pattern {
    @Serializable @SerialName("wildcard")
    data object WildcardPattern : Pattern()

    @Serializable @SerialName("literal")
    data class LiteralPattern(val value: LiteralValue) : Pattern()

    @Serializable @SerialName("binding")
    data class BindingPattern(val name: Alias) : Pattern()

    @Serializable @SerialName("constructor")
    data class ConstructorPattern(val constructor: Hash, val fields: List<Pattern>) : Pattern()

    @Serializable @SerialName("tuple")
    data class TuplePattern(val elements: List<Pattern>) : Pattern()
}

@Serializable
data class Param(
    val name: Alias,
    val type: TypeRef
)

@Serializable
data class MatchArm(
    val pattern: Pattern,
    val body: ExprNode
)

@Serializable
sealed class ExprNode {
    @Serializable @SerialName("literal")
    data class Literal(val value: LiteralValue) : ExprNode()

    @Serializable @SerialName("apply")
    data class Apply(val fn: ExprNode, val args: List<ExprNode>) : ExprNode()

    @Serializable @SerialName("match")
    data class Match(val scrutinee: ExprNode, val arms: List<MatchArm>) : ExprNode()

    @Serializable @SerialName("let")
    data class Let(val binding: Alias, val value: ExprNode, val body: ExprNode) : ExprNode()

    @Serializable @SerialName("lambda")
    data class Lambda(val params: List<Param>, val body: ExprNode) : ExprNode()

    @Serializable @SerialName("ref")
    data class Ref(val hash: Hash) : ExprNode()

    @Serializable @SerialName("if")
    data class If(val cond: ExprNode, val then_: ExprNode, val else_: ExprNode) : ExprNode()

    @Serializable @SerialName("block")
    data class Block(val exprs: List<ExprNode>) : ExprNode()
}
