package org.rust.lang.core.types

import org.rust.lang.core.psi.RustImplItemElement
import org.rust.lang.core.psi.RustStructOrEnumItemElement
import org.rust.lang.core.stubs.index.RustImplIndex

abstract class RustStructOrEnumTypeBase(struct: RustStructOrEnumItemElement) : RustType {

    override val inherentImpls: Sequence<RustImplItemElement> by lazy {
        RustImplIndex.getInherentImpls(struct.project, this).asSequence()
    }

    override val baseTypeName: String? = struct.name

}
