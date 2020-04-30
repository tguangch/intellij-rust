/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveMultipleElementsViewDescriptor
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import org.rust.ide.inspections.import.insertUseItem
import org.rust.ide.inspections.import.lastElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

/**
 * Move refactoring currently supports moving single file without submodules.
 * It consists of these steps:
 * - Check visibility conflicts (for all references their target should remain accessible after move)
 * - Update `pub(in path)` visibility modifiers in moved file if necessary
 * - Move mod-declaration to new parent mod
 * - Update necessary imports in other files
 * - Update necessary paths in other files (some usages could still remain invalid because of glob-imports)
 *     We replace path with absolute if there are few usages with same path in file, otherwise add new import
 * - Update relative paths in moved file
 */
class RsMoveFilesOrDirectoriesProcessor(
    private val project: Project,
    private val elementsToMove: Array<PsiElement>,
    private val newParent: PsiDirectory,
    private val newParentMod: RsMod,
    moveCallback: MoveCallback?,
    doneCallback: Runnable
) : MoveFilesOrDirectoriesProcessor(
    project,
    elementsToMove,
    newParent,
    true,
    true,
    true,
    moveCallback,
    doneCallback
) {

    private val psiFactory = RsPsiFactory(project)
    private val movedFile: RsFile = elementsToMove[0] as RsFile

    // keys --- `RsPath`s inside movedFile
    // outsideReferencesMap[path] --- target element for path reference
    private lateinit var outsideReferencesMap: Map<RsPath, RsElement>
    private lateinit var conflictsDetector: RsMoveFilesOrDirectoriesConflictsDetector

    override fun doRun() {
        checkMove()
        super.doRun()
    }

    private fun checkMove() {
        // TODO: support move multiply files
        check(elementsToMove.size == 1)

        check(newParentMod.crateRoot == movedFile.crateRoot)
        movedFile.modName?.let {
            if (newParentMod.getChildModule(it) != null) {
                throw IncorrectOperationException("Cannot move. Mod with same crate relative path already exists")
            }
        }
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val usages = refUsages.get()
        val conflicts = MultiMap<PsiElement, String>()

        val success = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { runReadAction { detectVisibilityProblems(usages, conflicts) } },
            RefactoringBundle.message("detecting.possible.conflicts"),
            true,
            project
        )
        if (!success) return false

        return showConflicts(conflicts, usages)
    }

    private fun detectVisibilityProblems(usages: Array<UsageInfo>, conflicts: MultiMap<PsiElement, String>) {
        outsideReferencesMap = collectOutsideReferencesFromMovedFile()
        conflictsDetector = RsMoveFilesOrDirectoriesConflictsDetector(movedFile, newParentMod, outsideReferencesMap)
        conflictsDetector.detectVisibilityProblems(usages, conflicts)
    }

    private fun collectOutsideReferencesFromMovedFile(): MutableMap<RsPath, RsElement> {
        val paths = movedFile.descendantsOfType<RsPath>()
        val outsideReferencesMap = mutableMapOf<RsPath, RsElement>()
        for (path in paths) {
            val target = path.reference?.resolve() ?: continue
            // ignore references from child modules of moved file
            if (target.isInsideModSubtree(movedFile)) continue

            outsideReferencesMap[path] = target
        }
        return outsideReferencesMap
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val (oldModDeclarations, useDirectiveUsages, otherUsages) = groupUsages(usages)

        updateReferencesInMovedFileBeforeMove()

        // step 1: move mod-declaration and update references in use-directives
        moveModDeclaration(oldModDeclarations)
        super.performRefactoring(useDirectiveUsages.toTypedArray())

        check(!movedFile.crateRelativePath.isNullOrEmpty())
        { "${movedFile.name} had correct crateRelativePath before moving mod-declaration, but empty/null after move" }

        // step 2: some usages could still remain invalid (because of glob-imports)
        // so we should add new import, or replace reference path with absolute one
        otherUsages.removeIf { it.reference!!.resolve() != null }
        retargetOtherUsages(otherUsages)

        // step 3: retarget references from moved file to outside
        updateReferencesInMovedFileAfterMove()
    }

    private fun groupUsages(usages: Array<out UsageInfo>): Triple<List<UsageInfo>, List<UsageInfo>, MutableList<UsageInfo>> {
        val oldModDeclarations = mutableListOf<UsageInfo>()
        val useDirectiveUsages = mutableListOf<UsageInfo>()
        val otherUsages = mutableListOf<UsageInfo>()
        for (usage in usages) {
            // ignore strange usages
            if (usage.element == null || usage.reference == null) continue

            when {
                usage.element is RsModDeclItem -> oldModDeclarations.add(usage)
                usage.element!!.parentOfType<RsUseItem>() != null -> useDirectiveUsages.add(usage)
                else -> otherUsages.add(usage)
            }
        }

        // files not included in module tree are filtered in RsMoveFilesOrDirectoriesHandler::canMove
        // by check file.crateRoot != null
        check(oldModDeclarations.isNotEmpty())
        if (oldModDeclarations.size > 1) {
            throw IncorrectOperationException("Can't move ${movedFile.name}.\nIt is declared in more than one parent modules")
        }

        return Triple(oldModDeclarations, useDirectiveUsages, otherUsages)
    }

    private fun moveModDeclaration(oldModDeclarations: List<UsageInfo>) {
        check(oldModDeclarations.size == 1)
        val oldModDeclaration = oldModDeclarations[0].element as RsModDeclItem

        when (oldModDeclaration.visibility) {
            is RsVisibility.Private -> {
                if (conflictsDetector.shouldMakeMovedFileModDeclarationPublic) {
                    oldModDeclaration.addAfter(psiFactory.createPub(), null)
                }
            }
            is RsVisibility.Restricted -> run {
                val visRestriction = oldModDeclaration.vis?.visRestriction ?: return@run
                visRestriction.updateScopeIfNecessary(psiFactory, newParentMod)
            }
        }
        val newModDeclaration = oldModDeclaration.copy()

        oldModDeclaration.delete()
        newParentMod.insertModDecl(psiFactory, newModDeclaration)
    }

    private fun retargetOtherUsages(otherUsages: MutableList<UsageInfo>) {
        val movedFileName = movedFile.modName
        if (movedFileName == null) {
            super.retargetUsages(otherUsages.toTypedArray(), emptyMap())
            return
        }

        // (assume `foo` is movedFile)
        // group by pair:
        //   1) containingMod
        //   2) path prefix before `foo` (e.g. for `foo::...` it is `foo` and for `bar1::bar2::foo::...` it is `bar1::bar2::foo`)
        val otherUsagesGrouped = otherUsages.groupBy {
            val element = it.element!! as RsElement
            Pair(element.containingMod, element.text)
        }

        val otherUsagesRemaining = mutableListOf<UsageInfo>()
        for (usages in otherUsagesGrouped.values) {
            if (usages.size <= NUMBER_USAGES_THRESHOLD_FOR_ADDING_IMPORT) {
                otherUsagesRemaining.addAll(usages)
                continue
            }

            val context = PsiTreeUtil.findCommonParent(usages.map { it.element }) as RsElement
            if (!addImport(psiFactory, context, movedFile)) {
                otherUsagesRemaining.addAll(usages)
                continue
            }

            // we added import for `foo`,
            // and if path starts with `mod1::foo` (and not with just `foo`)
            // we should remove `mod1::` part from path
            val movedFileSinglePath = psiFactory.tryCreatePath(movedFileName)!!
            for (usage in usages) {
                val element = usage.element!! as RsElement
                if (element !is RsPath || element.children.isEmpty()) continue

                element.replace(movedFileSinglePath)
            }
        }

        super.retargetUsages(otherUsagesRemaining.toTypedArray(), emptyMap())
    }

    private fun updateReferencesInMovedFileBeforeMove() {
        for ((path, _) in outsideReferencesMap) {
            val visRestriction = path.parent as? RsVisRestriction ?: continue
            visRestriction.updateScopeIfNecessary(psiFactory, newParentMod)
        }
    }

    private fun updateReferencesInMovedFileAfterMove() {
        // we should update `super::...` paths in movedFile
        // but in direct submodules of movedFile we should update only `super::super::...` paths
        // and so on

        for ((path, target) in outsideReferencesMap) {
            val pathParent = path.parent
            if (pathParent is RsVisRestriction) continue
            if (pathParent is RsPath && pathParent.hasOnlySuperSegments()) continue
            if (!path.hasOnlySuperSegments()) continue

            val targetModPath = (target as? RsMod)?.crateRelativePath ?: continue
            val pathNew = psiFactory.tryCreatePath("crate$targetModPath") ?: continue
            path.replace(pathNew)
        }
    }

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor {
        return MoveMultipleElementsViewDescriptor(elementsToMove, newParent.name)
    }

    companion object {
        const val NUMBER_USAGES_THRESHOLD_FOR_ADDING_IMPORT: Int = 2
    }
}

fun RsElement.isInsideModSubtree(mod: RsMod): Boolean = containingMod.superMods.contains(mod)

// updates `pub(in path)` visibility modifier
// `path` must be a parent module of the item whose visibility is being declared,
// so we replace `path` with common parent module of `newParent` and old `path`
private fun RsVisRestriction.updateScopeIfNecessary(psiFactory: RsPsiFactory, newParent: RsMod) {
    val oldScope = path.reference?.resolve() as? RsMod ?: return
    val newScope = commonParentMod(oldScope, newParent) ?: return
    if (newScope == oldScope) return
    val newScopePath = newScope.crateRelativePath ?: return

    check(crateRoot == newParent.crateRoot)
    val newVisRestriction = psiFactory.createVisRestriction("crate$newScopePath")
    replace(newVisRestriction)
}

private fun RsMod.insertModDecl(psiFactory: RsPsiFactory, modDecl: PsiElement) {
    val anchor = childrenOfType<RsModDeclItem>().lastElement ?: childrenOfType<RsUseItem>().lastElement
    if (anchor != null) {
        addAfter(modDecl, anchor)
    } else {
        val firstItem = itemsAndMacros.firstOrNull { it !is RsAttr && it !is RsVis }
            ?: (this as? RsModItem)?.rbrace
        addBefore(modDecl, firstItem)
    }

    if (modDecl.nextSibling == null) {
        addAfter(psiFactory.createNewline(), modDecl)
    }
}

private fun addImport(psiFactory: RsPsiFactory, context: RsElement, movedFile: RsFile): Boolean {
    val path = movedFile.qualifiedNameRelativeTo(context.containingMod) ?: return false

    val blockScope = context.ancestors.find { it is RsBlock && it.childOfType<RsUseItem>() != null } as RsBlock?
    val scope = blockScope ?: context.containingMod
    scope.insertUseItem(psiFactory, path)
    return true
}

fun RsPath.hasOnlySuperSegments(): Boolean {
    if (`super` == null) return false
    return path?.hasOnlySuperSegments() ?: true
}
