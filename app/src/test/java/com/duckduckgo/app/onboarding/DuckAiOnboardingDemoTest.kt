/*
 * Copyright (c) 2026 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.onboarding

import com.duckduckgo.app.cta.db.DismissedCtaDao
import com.duckduckgo.app.cta.model.CtaId
import com.duckduckgo.app.cta.model.DismissedCta
import com.duckduckgo.app.onboarding.DuckAiOnboardingDemo.Driver
import com.duckduckgo.common.test.CoroutineTestRule
import com.duckduckgo.common.test.api.InMemorySharedPreferences
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

class DuckAiOnboardingDemoTest {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    private val preferences = InMemorySharedPreferences()
    private val sharedPreferencesProvider: SharedPreferencesProvider = mock {
        on { getSharedPreferences(any(), any(), any()) } doReturn preferences
    }
    private val dismissedCtaDao: DismissedCtaDao = mock()

    private val testee = DuckAiOnboardingDemoImpl(
        sharedPreferencesProvider = sharedPreferencesProvider,
        dismissedCtaDao = dismissedCtaDao,
        dispatchers = coroutineRule.testDispatcherProvider,
    )

    @Test
    fun `when not armed then the demo is inactive`() {
        assertFalse(testee.isActive())
    }

    @Test
    fun `when armed then the demo is active`() = runTest {
        testee.arm(Driver.LinearPlan)
        assertTrue(testee.isActive())
    }

    @Test
    fun `when armed by a linear plan then the driver is the linear plan`() = runTest {
        testee.arm(Driver.LinearPlan)
        assertEquals(Driver.LinearPlan, testee.driver())
    }

    @Test
    fun `when armed by the browser then the driver is the browser`() = runTest {
        testee.arm(Driver.Browser)
        assertEquals(Driver.Browser, testee.driver())
    }

    @Test
    fun `when an install armed the demo before the driver existed then the driver is the browser`() {
        preferences.edit().putBoolean("duckAiOnboardingFlow", true).apply()

        assertTrue(testee.isActive())
        assertEquals(Driver.Browser, testee.driver())
    }

    @Test
    fun `when armed by a linear plan then the demo was central to onboarding`() = runTest {
        testee.arm(Driver.LinearPlan)
        assertTrue(testee.wasCentralToOnboarding())
    }

    @Test
    fun `when armed by the browser then the demo was not central to onboarding`() = runTest {
        testee.arm(Driver.Browser)
        assertFalse(testee.wasCentralToOnboarding())
    }

    @Test
    fun `when armed then standard dax ctas are dismissed`() = runTest {
        testee.arm(Driver.LinearPlan)
        verify(dismissedCtaDao).insert(DismissedCta(CtaId.DAX_INTRO))
        verify(dismissedCtaDao).insert(DismissedCta(CtaId.DAX_DIALOG_SERP))
        verify(dismissedCtaDao).insert(DismissedCta(CtaId.DAX_DIALOG_TRACKERS_FOUND))
        verify(dismissedCtaDao).insert(DismissedCta(CtaId.DAX_FIRE_BUTTON))
        verify(dismissedCtaDao).insert(DismissedCta(CtaId.DAX_END))
        verifyNoMoreInteractions(dismissedCtaDao)
    }

    @Test
    fun `when finished then the duck ai fire button cta is dismissed`() = runTest {
        testee.finish()
        verify(dismissedCtaDao).insert(DismissedCta(CtaId.DAX_DUCK_AI_FIRE_BUTTON))
    }

    @Test
    fun `when finished twice then the dismissal is idempotent`() = runTest {
        testee.finish()
        testee.finish()
        verify(dismissedCtaDao, times(2)).insert(DismissedCta(CtaId.DAX_DUCK_AI_FIRE_BUTTON))
    }
}
