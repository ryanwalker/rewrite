package com.netflix.java.refactor.fix

import com.netflix.java.refactor.*
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.tree.JCTree
import com.sun.tools.javac.util.Context
import java.util.*

class ChangeMethodInvocation(signature: String, val tx: RefactorTransaction) : FixingOperation {
    override fun scanner(): RefactoringScanner<List<RefactorFix>> =
            if (refactorTargetToStatic is String) {
                IfThenScanner(ifFixesResultFrom = ChangeMethodInvocationScanner(this),
                        then = arrayOf(
                                AddImport(refactorTargetToStatic!!).scanner()
                        ))
            } else {
                ChangeMethodInvocationScanner(this)
            }

    internal val matcher = MethodMatcher(signature)

    internal var refactorName: String? = null
    internal var refactorArguments: RefactorArguments? = null
    internal var refactorTargetToStatic: String? = null
    internal var refactorTargetToVariable: String? = null

    fun refactorName(name: String): ChangeMethodInvocation {
        refactorName = name
        return this
    }

    fun refactorArguments(): RefactorArguments {
        refactorArguments = RefactorArguments(this)
        return refactorArguments!!
    }

    fun refactorTargetToStatic(clazz: String): ChangeMethodInvocation {
        refactorTargetToStatic = clazz
        return this
    }

    fun refactorTargetToStatic(clazz: Class<*>) = refactorTargetToStatic(clazz.name)

    fun refactorTargetToVariable(variable: String): ChangeMethodInvocation {
        refactorTargetToVariable = variable
        return this
    }

    fun done(): RefactorTransaction {
        if (tx.autoCommit)
            tx.commit()
        return tx
    }
}

class RefactorArguments(val op: ChangeMethodInvocation) {
    val individualArgumentRefactors = ArrayList<RefactorArgument>()
    var reorderArguments: List<String>? = null

    fun arg(clazz: String): RefactorArgument {
        val arg = RefactorArgument(this, typeConstraint = clazz)
        individualArgumentRefactors.add(arg)
        return arg
    }

    fun arg(clazz: Class<*>) = arg(clazz.name)

    fun arg(pos: Int): RefactorArgument {
        val arg = RefactorArgument(this, posConstraint = pos)
        individualArgumentRefactors.add(arg)
        return arg
    }

    fun reorderArguments(vararg nameOrType: String): RefactorArguments {
        reorderArguments = nameOrType.toList()
        return this
    }

    fun done() = op
}

open class RefactorArgument(val op: RefactorArguments,
                            val typeConstraint: String? = null,
                            val posConstraint: Int? = null) {
    var moveToLast: Boolean = false
    var refactorLiterals: ((Any) -> Any)? = null

    fun refactorLiterals(transform: (Any) -> Any): RefactorArgument {
        this.refactorLiterals = transform
        return this
    }

    fun moveToLast(): RefactorArgument {
        this.moveToLast = true
        return this
    }

    fun done() = op
}

class ChangeMethodInvocationScanner(val op: ChangeMethodInvocation) : FixingScanner() {
    override fun visitMethodInvocation(node: MethodInvocationTree, context: Context): List<RefactorFix>? {
        val invocation = node as JCTree.JCMethodInvocation
        if (op.matcher.matches(invocation)) {
            return refactorMethod(invocation)
        }
        return null
    }

    fun refactorMethod(invocation: JCTree.JCMethodInvocation): List<RefactorFix> {
        val meth = invocation.meth
        val fixes = ArrayList<RefactorFix>()
        val methSym = when (meth) {
            is JCTree.JCFieldAccess -> meth.sym as Symbol.MethodSymbol
            is JCTree.JCIdent -> meth.sym as Symbol.MethodSymbol
            else -> null
        }

        if (op.refactorName is String) {
            when (meth) {
                is JCTree.JCFieldAccess -> {
                    val nameStart = meth.selected.getEndPosition(cu.endPositions) + 1
                    fixes.add(RefactorFix(nameStart..nameStart + meth.name.toString().length, op.refactorName!!, source))
                }
                is JCTree.JCIdent -> {
                    meth.replace(op.refactorName!!)
                }
            }
        }

        if (op.refactorArguments is RefactorArguments) {
            if (op.refactorArguments?.reorderArguments != null) {
                val reorders = op.refactorArguments!!.reorderArguments!!
                val paramNames = methSym?.params()?.map { it.name.toString() }

                if(paramNames != null) {
                    reorders.forEachIndexed { i, reorder ->
                        if (invocation.arguments.size <= i) {
                            // this is a weird case, there are not enough arguments in the invocation to satisfy the reordering specification
                            // TODO what to do?
                            return@forEachIndexed
                        }

                        val arg = invocation.arguments[i]
                        if (paramNames[i] != reorder) {
                            val swap = invocation.arguments.filterIndexed { j, swap -> paramNames[j] == reorder }.firstOrNull() ?: 
                                    throw RuntimeException("Unable to find argument '$reorder' on method")
                            fixes.add(arg.replace(swap.changesToArgument(i) ?: swap.source()))
                        }
                    }
                }
                else {
                    // TODO what do we do when the method symbol is not present?
                }
            } else {
                invocation.arguments.forEachIndexed { i, arg ->
                    arg.changesToArgument(i)?.let { changes ->
                        fixes.add(arg.replace(changes))
                    }
                }
            }
        }

        if (op.refactorTargetToStatic is String) {
            when (meth) {
                is JCTree.JCFieldAccess ->
                    fixes.add(meth.selected.replace(className(op.refactorTargetToStatic!!)))
                is JCTree.JCIdent ->
                    fixes.add(meth.insertBefore(className(op.refactorTargetToStatic!! + ".")))
            }
        }

        if (op.refactorTargetToVariable is String) {
            when (meth) {
                is JCTree.JCFieldAccess ->
                    fixes.add(meth.selected.replace(op.refactorTargetToVariable!!))
                is JCTree.JCIdent ->
                    fixes.add(meth.insertBefore(op.refactorTargetToVariable!! + "."))
            }
        }

        return fixes
    }

    private inner class ChangeArgumentScanner : TreePathScanner<List<RefactorFix>, RefactorArgument>() {
        override fun visitLiteral(node: LiteralTree, refactor: RefactorArgument): List<RefactorFix> {
            val literal = node as JCTree.JCLiteral
            val value = literal.value

            // prefix and suffix hold the special characters surrounding the values of primitive-ish types,
            // e.g. the "" around String, the L at the end of a long, etc.
            val valueMatcher = "(.*)$value(.*)".toRegex().find(node.toString())
            val (prefix, suffix) = valueMatcher!!.groupValues.drop(1)

            val transformed = refactor.refactorLiterals?.invoke(value) ?: value
            return if (transformed != value.toString()) listOf(literal.replace("$prefix$transformed$suffix")) else emptyList()
        }

        override fun reduce(r1: List<RefactorFix>?, r2: List<RefactorFix>?): List<RefactorFix> =
                (r1 ?: emptyList()).plus(r2 ?: emptyList())
    }

    fun JCTree.JCExpression.changesToArgument(pos: Int): String? {
        val refactor = op.refactorArguments?.individualArgumentRefactors?.find { it.posConstraint == pos } ?:
                op.refactorArguments?.individualArgumentRefactors?.find { this.type.matches(it.typeConstraint) }

        return if (refactor is RefactorArgument) {
            val fixes = ChangeArgumentScanner().scan(TreePath.getPath(cu, this), refactor)

            // aggregate all the fixes to this argument into one "change" replacement rule
            return if (fixes.isNotEmpty()) {
                val sortedFixes = fixes.sortedBy { it.position.last }.sortedBy { it.position.start }
                var fixedArg = sortedFixes.foldIndexed("") { i, source, fix ->
                    val prefix = if (i == 0)
                        sourceText.substring(this.startPosition, fix.position.first)
                    else sourceText.substring(sortedFixes[i - 1].position.last, fix.position.start)
                    source + prefix + (fix.changes ?: "")
                }
                if (sortedFixes.last().position.last < sourceText.length) {
                    fixedArg += sourceText.substring(sortedFixes.last().position.last, this.getEndPosition(cu.endPositions))
                }

                fixedArg
            } else null
        } else null
    }
}
