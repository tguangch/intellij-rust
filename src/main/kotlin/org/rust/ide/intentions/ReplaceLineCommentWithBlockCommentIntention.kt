/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.rust.lang.core.parser.RustParserDefinition.Companion.EOL_COMMENT
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.ext.ancestorOrSelf
import org.rust.lang.core.psi.ext.getNextNonWhitespaceSibling
import org.rust.lang.core.psi.ext.getPrevNonWhitespaceSibling

class ReplaceLineCommentWithBlockCommentIntention : RsElementBaseIntentionAction<PsiComment>() {

    override fun getText(): String = familyName
    override fun getFamilyName(): String = "Replace with block comment"

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): PsiComment? {
        val comment = element.ancestorOrSelf<PsiComment>() ?: return null
        if (!comment.isEndOfLineComment) {
            return null
        }

        return generateSequence(comment) { it.prevComment }.last()
    }

    override fun invoke(project: Project, editor: Editor, ctx: PsiComment) {
        val indent = (ctx.prevSibling as? PsiWhiteSpace)
            ?.text
            ?.substringAfterLast('\n')
            .orEmpty()

        val comments = generateSequence(ctx) { it.nextComment }.toList()

        val blockCommentText = if (comments.size == 1) {
            " ${ctx.commentText} "
        } else {
            comments.joinToString(separator = "\n", prefix = "\n", postfix = "\n$indent") {
                "$indent${it.commentText}"
            }
        }

        comments.drop(1).forEach {
            (it.prevSibling as? PsiWhiteSpace)?.delete()
            it.delete()
        }

        val newComment = RsPsiFactory(project).createBlockComment(blockCommentText)
        ctx.replace(newComment)
    }

    private val PsiElement.isEndOfLineComment: Boolean
        get() = node.elementType == EOL_COMMENT

    private val PsiComment.commentText: String
        get() = text.substring(2)
            .replace("/*", "/ *")
            .replace("*/", "* /")
            .trim()

    private val PsiComment.prevComment: PsiComment?
        get() = (getPrevNonWhitespaceSibling() as? PsiComment)?.takeIf { it.isEndOfLineComment }

    private val PsiComment.nextComment: PsiComment?
        get() = (getNextNonWhitespaceSibling() as? PsiComment)?.takeIf { it.isEndOfLineComment }
}
