/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

class ReplaceLineCommentWithBlockCommentIntentionTest : RsIntentionTestBase(ReplaceLineCommentWithBlockCommentIntention()) {

    fun `test convert single line comment to block`() = doAvailableTest("""
        // /*caret*/Hello, World!
    """, """
        /* /*caret*/Hello, World! */
    """)

    fun `test convert multiline comment to block`() = doAvailableTest("""
        // First
		// /*caret*/Second
		// Third
    """, """
        /*
        First
        Second
        Third
        *//*caret*/
        
    """)
}
