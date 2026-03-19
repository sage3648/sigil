package sigil.types

class TraitResolver {
    private val implementations = mutableMapOf<Pair<String, String>, Boolean>()

    fun registerImpl(typeHash: String, traitHash: String) {
        implementations[Pair(typeHash, traitHash)] = true
    }

    fun checkBound(type: Type, traitHash: String): Boolean {
        val resolved = when (type) {
            is Type.Concrete -> type.hash
            else -> return false
        }
        return implementations[Pair(resolved, traitHash)] == true
    }
}
