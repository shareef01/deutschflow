package com.aus.deutschflow.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The goals exist twice: [RoleplayScenario.learnerGoals] is what the model is told,
 * [RoleplayScenario.goalRes] is what the learner reads. A scene whose two lists
 * differ in length would show the learner a different brief from the one the model
 * was given.
 */
class RoleplayScenarioCatalogTest {

    @Test
    fun everyPromptGoalHasADisplayedGoal() {
        RoleplayScenarioCatalog.SCENARIOS.forEach { scenario ->
            assertEquals(scenario.id, scenario.learnerGoals.size, scenario.goalRes.size)
        }
    }
}
