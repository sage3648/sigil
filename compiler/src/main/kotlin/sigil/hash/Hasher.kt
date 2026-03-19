package sigil.hash

import sigil.ast.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object Hasher {

    // Node type tags
    private const val TAG_FN_DEF: Byte = 0x01
    private const val TAG_TYPE_DEF: Byte = 0x02
    private const val TAG_EXPR_NODE: Byte = 0x03
    private const val TAG_MODULE_DEF: Byte = 0x04
    private const val TAG_TRAIT_DEF: Byte = 0x05
    private const val TAG_EFFECT_DEF: Byte = 0x06
    private const val TAG_CONTRACT_NODE: Byte = 0x07
    private const val TAG_REFINEMENT_TYPE: Byte = 0x08

    // ExprNode kind tags
    private const val TAG_LITERAL: Byte = 0x10
    private const val TAG_APPLY: Byte = 0x11
    private const val TAG_MATCH: Byte = 0x12
    private const val TAG_LET: Byte = 0x13
    private const val TAG_LAMBDA: Byte = 0x14
    private const val TAG_REF: Byte = 0x15
    private const val TAG_IF: Byte = 0x16
    private const val TAG_BLOCK: Byte = 0x17

    fun hashFnDef(fn: FnDef): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_FN_DEF.toInt())
        serializeParams(dos, fn.params)
        serializeTypeRef(dos, fn.returnType)
        serializeOptional(dos, fn.contract) { serializeContract(dos, it) }
        serializeEffectSet(dos, fn.effects)
        serializeExpr(dos, fn.body)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    fun hashTypeDef(td: TypeDef): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_TYPE_DEF.toInt())
        serializeTypeParams(dos, td.typeParams)
        serializeVariants(dos, td.variants)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    fun hashModuleDef(md: ModuleDef): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_MODULE_DEF.toInt())
        serializeHashList(dos, md.exports)
        serializeHashList(dos, md.definitions)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    fun hashTraitDef(td: TraitDef): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_TRAIT_DEF.toInt())
        serializeFnSignatures(dos, td.methods)
        serializeProperties(dos, td.laws)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    fun hashEffectDef(ed: EffectDef): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_EFFECT_DEF.toInt())
        serializeEffectOperations(dos, ed.operations)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    fun hashExpr(expr: ExprNode): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_EXPR_NODE.toInt())
        serializeExpr(dos, expr)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    fun hashContract(contract: ContractNode): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_CONTRACT_NODE.toInt())
        serializeContract(dos, contract)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    fun hashRefinementType(rt: RefinementType): Hash {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(TAG_REFINEMENT_TYPE.toInt())
        serializeTypeRef(dos, rt.baseType)
        serializeExpr(dos, rt.predicate)
        dos.flush()
        return Blake3.hashHex(out.toByteArray())
    }

    // --- Internal serialization helpers ---

    private fun serializeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun serializeHash(dos: DataOutputStream, hash: Hash) {
        if (hash.startsWith("#sigil:") || hash.startsWith("#")) {
            serializeString(dos, hash)
        } else if (hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            // Hex-encoded hash -> decode to 32 raw bytes
            val bytes = hexToBytes(hash)
            dos.write(bytes)
        } else {
            // Plain identifier (e.g. variable name used as Ref)
            serializeString(dos, hash)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun <T> serializeList(dos: DataOutputStream, list: List<T>, serializer: (T) -> Unit) {
        dos.writeInt(list.size)
        for (item in list) {
            serializer(item)
        }
    }

    private fun <T> serializeOptional(dos: DataOutputStream, value: T?, serializer: (T) -> Unit) {
        if (value == null) {
            dos.writeByte(0x00)
        } else {
            dos.writeByte(0x01)
            serializer(value)
        }
    }

    private fun serializeTypeRef(dos: DataOutputStream, ref: TypeRef) {
        serializeHash(dos, ref.defHash)
        serializeList(dos, ref.args) { serializeTypeRef(dos, it) }
    }

    private fun serializeTypeVar(dos: DataOutputStream, tv: TypeVar) {
        // Excludes name (positional), only serialize bounds
        serializeList(dos, tv.bounds) { serializeHash(dos, it) }
    }

    private fun serializeParam(dos: DataOutputStream, param: Param) {
        serializeString(dos, param.name)
        serializeTypeRef(dos, param.type)
    }

    private fun serializeParams(dos: DataOutputStream, params: List<Param>) {
        serializeList(dos, params) { serializeParam(dos, it) }
    }

    private fun serializeTypeParams(dos: DataOutputStream, typeParams: List<TypeVar>) {
        serializeList(dos, typeParams) { serializeTypeVar(dos, it) }
    }

    private fun serializeVariant(dos: DataOutputStream, variant: Variant) {
        serializeString(dos, variant.name)
        serializeParams(dos, variant.fields)
    }

    private fun serializeVariants(dos: DataOutputStream, variants: List<Variant>) {
        serializeList(dos, variants) { serializeVariant(dos, it) }
    }

    private fun serializeHashList(dos: DataOutputStream, hashes: List<Hash>) {
        serializeList(dos, hashes) { serializeHash(dos, it) }
    }

    private fun serializeEffectSet(dos: DataOutputStream, effects: EffectSet) {
        val sorted = effects.sorted()
        serializeList(dos, sorted) { serializeHash(dos, it) }
    }

    private fun serializeContract(dos: DataOutputStream, contract: ContractNode) {
        // requires predicates, ensures predicates (EXCLUDES severity)
        serializeList(dos, contract.requires) { serializeExpr(dos, it.predicate) }
        serializeList(dos, contract.ensures) { serializeExpr(dos, it.predicate) }
    }

    private fun serializeLiteralValue(dos: DataOutputStream, value: LiteralValue) {
        when (value) {
            is LiteralValue.IntLit -> {
                dos.writeByte(0x00)
                dos.writeLong(value.value)
            }
            is LiteralValue.FloatLit -> {
                dos.writeByte(0x01)
                dos.writeDouble(value.value)
            }
            is LiteralValue.StringLit -> {
                dos.writeByte(0x02)
                serializeString(dos, value.value)
            }
            is LiteralValue.BoolLit -> {
                dos.writeByte(0x03)
                dos.writeByte(if (value.value) 0x01 else 0x00)
            }
            is LiteralValue.UnitLit -> {
                dos.writeByte(0x04)
            }
        }
    }

    private fun serializePattern(dos: DataOutputStream, pattern: Pattern) {
        when (pattern) {
            is Pattern.WildcardPattern -> dos.writeByte(0x00)
            is Pattern.LiteralPattern -> {
                dos.writeByte(0x01)
                serializeLiteralValue(dos, pattern.value)
            }
            is Pattern.BindingPattern -> {
                dos.writeByte(0x02)
                serializeString(dos, pattern.name)
            }
            is Pattern.ConstructorPattern -> {
                dos.writeByte(0x03)
                serializeHash(dos, pattern.constructor)
                serializeList(dos, pattern.fields) { serializePattern(dos, it) }
            }
            is Pattern.TuplePattern -> {
                dos.writeByte(0x04)
                serializeList(dos, pattern.elements) { serializePattern(dos, it) }
            }
        }
    }

    private fun serializeMatchArm(dos: DataOutputStream, arm: MatchArm) {
        serializePattern(dos, arm.pattern)
        serializeExpr(dos, arm.body)
    }

    private fun serializeExpr(dos: DataOutputStream, expr: ExprNode) {
        when (expr) {
            is ExprNode.Literal -> {
                dos.writeByte(TAG_LITERAL.toInt())
                serializeLiteralValue(dos, expr.value)
            }
            is ExprNode.Apply -> {
                dos.writeByte(TAG_APPLY.toInt())
                serializeExpr(dos, expr.fn)
                serializeList(dos, expr.args) { serializeExpr(dos, it) }
            }
            is ExprNode.Match -> {
                dos.writeByte(TAG_MATCH.toInt())
                serializeExpr(dos, expr.scrutinee)
                serializeList(dos, expr.arms) { serializeMatchArm(dos, it) }
            }
            is ExprNode.Let -> {
                dos.writeByte(TAG_LET.toInt())
                serializeString(dos, expr.binding)
                serializeExpr(dos, expr.value)
                serializeExpr(dos, expr.body)
            }
            is ExprNode.Lambda -> {
                dos.writeByte(TAG_LAMBDA.toInt())
                serializeParams(dos, expr.params)
                serializeExpr(dos, expr.body)
            }
            is ExprNode.Ref -> {
                dos.writeByte(TAG_REF.toInt())
                serializeHash(dos, expr.hash)
            }
            is ExprNode.If -> {
                dos.writeByte(TAG_IF.toInt())
                serializeExpr(dos, expr.cond)
                serializeExpr(dos, expr.then_)
                serializeExpr(dos, expr.else_)
            }
            is ExprNode.Block -> {
                dos.writeByte(TAG_BLOCK.toInt())
                serializeList(dos, expr.exprs) { serializeExpr(dos, it) }
            }
        }
    }

    private fun serializeFnSignature(dos: DataOutputStream, sig: FnSignature) {
        serializeString(dos, sig.name)
        serializeParams(dos, sig.params)
        serializeTypeRef(dos, sig.returnType)
        serializeEffectSet(dos, sig.effects)
    }

    private fun serializeFnSignatures(dos: DataOutputStream, sigs: List<FnSignature>) {
        serializeList(dos, sigs) { serializeFnSignature(dos, it) }
    }

    private fun serializeProperty(dos: DataOutputStream, prop: Property) {
        serializeParams(dos, prop.quantifiedVars)
        serializeExpr(dos, prop.predicate)
    }

    private fun serializeProperties(dos: DataOutputStream, props: List<Property>) {
        serializeList(dos, props) { serializeProperty(dos, it) }
    }

    private fun serializeEffectOperation(dos: DataOutputStream, op: EffectOperation) {
        serializeString(dos, op.name)
        serializeParams(dos, op.params)
        serializeTypeRef(dos, op.returnType)
    }

    private fun serializeEffectOperations(dos: DataOutputStream, ops: List<EffectOperation>) {
        serializeList(dos, ops) { serializeEffectOperation(dos, it) }
    }
}
