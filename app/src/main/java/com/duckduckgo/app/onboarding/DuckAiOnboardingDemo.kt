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

import androidx.core.content.edit
import com.duckduckgo.app.cta.db.DismissedCtaDao
import com.duckduckgo.app.cta.model.CtaId
import com.duckduckgo.app.cta.model.DismissedCta
import com.duckduckgo.app.onboarding.DuckAiOnboardingDemo.Driver
import com.duckduckgo.app.onboarding.store.OnboardingStore
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The in-browser Duck.ai onboarding demo (the fire-button CTA sequence) owner.
 * Every path that requires the demo routes through this interface.
 */
interface DuckAiOnboardingDemo {
    /**
     * Arm the demo: mark the flow active and silence the standard DAX onboarding CTAs so only the
     * Duck.ai demo CTAs show. [driver] represents who ends the flow.
     */
    suspend fun arm(driver: Driver)

    /** Whether the Duck.ai onboarding demo is active. Gates the Duck.ai demo CTAs. */
    fun isActive(): Boolean

    /**
     * Who ends the flow. [Driver.LinearPlan] defers dismissal of the Duck.ai fire-button CTA to
     * [finish], so the CTA survives an app kill and the plan can re-run; [Driver.Browser] lets the CTA
     * dismiss itself on user interaction, because no plan will ever call [finish] for it.
     */
    fun driver(): Driver

    /**
     * Whether the current onboarding flow is focused on Duck.ai.
     * If true, picks Duck.ai-specific copies for CTA, route to specific subscription upsells, and potentially more.
     */
    fun wasCentralToOnboarding(): Boolean

    /** End the flow by dismissing the Duck.ai fire-button CTA. Safe to call more than once. */
    suspend fun finish()

    enum class Driver { LinearPlan, Browser }
}

@SingleInstanceIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DuckAiOnboardingDemoImpl @Inject constructor(
    private val sharedPreferencesProvider: SharedPreferencesProvider,
    private val onboardingStore: OnboardingStore,
    private val dismissedCtaDao: DismissedCtaDao,
    private val dispatchers: DispatcherProvider,
) : DuckAiOnboardingDemo {

    private val preferences by lazy { sharedPreferencesProvider.getSharedPreferences(FILENAME) }

    override suspend fun arm(driver: Driver) {
        withContext(dispatchers.io()) {
            preferences.edit {
                putBoolean(KEY_ACTIVE, true)
                putBoolean(KEY_PLAN_DRIVEN, driver == Driver.LinearPlan)
            }
            PRE_DISMISSED_CTAS.forEach { dismissedCtaDao.insert(DismissedCta(it)) }
        }
    }

    override fun isActive(): Boolean = preferences.getBoolean(KEY_ACTIVE, legacyActive())

    override fun driver(): Driver =
        if (preferences.getBoolean(KEY_PLAN_DRIVEN, false)) Driver.LinearPlan else Driver.Browser

    // Every Duck.ai-led plan is also the one that ends its own demo, so one stored bit answers both today.
    // If a search-led plan ever runs a plan-driven demo, this needs its own key defaulted from the driver.
    override fun wasCentralToOnboarding(): Boolean = driver() == Driver.LinearPlan

    /**
     * The state of the flow before this class owned it, so an instance that armed the demo pre-upgrade
     * stays armed — deactivating a mid-flight demo would leave the user with no CTAs at all, since [arm]
     * has already pre-dismissed the standard DAX ones. Only remove the fallback once the update has reached critical adoption.
     */
    @Suppress("DEPRECATION")
    private fun legacyActive(): Boolean = onboardingStore.isDuckAiOnboardingFlow()

    override suspend fun finish() {
        withContext(dispatchers.io()) {
            dismissedCtaDao.insert(DismissedCta(CtaId.DAX_DUCK_AI_FIRE_BUTTON))
        }
    }

    companion object {
        private const val FILENAME = "com.duckduckgo.app.onboarding.duckaidemo"
        private const val KEY_ACTIVE = "active"
        private const val KEY_PLAN_DRIVEN = "planDriven"

        /**
         * Standard DAX onboarding CTAs the Duck.ai flow pre-dismisses when armed, so only the Duck.ai
         * demo CTAs (and the trailing Privacy Pro bubble) drive the flow.
         */
        val PRE_DISMISSED_CTAS = listOf(
            CtaId.DAX_INTRO,
            CtaId.DAX_DIALOG_SERP,
            CtaId.DAX_DIALOG_TRACKERS_FOUND,
            CtaId.DAX_FIRE_BUTTON,
            CtaId.DAX_END,
        )
    }
}
