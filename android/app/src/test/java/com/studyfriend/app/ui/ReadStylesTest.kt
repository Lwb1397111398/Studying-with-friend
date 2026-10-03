package com.studyfriend.app.ui

import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.ui.screens.ReadStyles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-F 次级角色判定：四值 true（TOC/FOOTNOTE/FRONT/BACK），BODY 与未知值 false */
class ReadStylesTest {

    @Test
    fun secondaryRoles() {
        assertTrue(ReadStyles.isSecondaryRole(DbValues.ROLE_TOC))
        assertTrue(ReadStyles.isSecondaryRole(DbValues.ROLE_FOOTNOTE))
        assertTrue(ReadStyles.isSecondaryRole(DbValues.ROLE_FRONT))
        assertTrue(ReadStyles.isSecondaryRole(DbValues.ROLE_BACK))
    }

    @Test
    fun bodyIsNotSecondary() {
        assertFalse(ReadStyles.isSecondaryRole(DbValues.ROLE_BODY))
    }

    @Test
    fun unknownRoleIsNotSecondary() {
        assertFalse(ReadStyles.isSecondaryRole(""))
        assertFalse(ReadStyles.isSecondaryRole("WHATEVER"))
    }
}
