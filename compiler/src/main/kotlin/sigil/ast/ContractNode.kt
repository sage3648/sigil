package sigil.ast

import kotlinx.serialization.Serializable

@Serializable
enum class Severity { PROVE, ABORT, WARN }

@Serializable
data class ContractClause(
    val predicate: ExprNode,
    val severity: Severity = Severity.ABORT
)

@Serializable
data class ContractNode(
    val requires: List<ContractClause>,
    val ensures: List<ContractClause>
)
