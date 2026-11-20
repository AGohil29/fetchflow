package io.github.agohil29.fetchflow.core

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SanityTest {
    @Test
    fun multiplatformTestHarnessWorksDeterministically() = runTest {
        val calculatedSum = 100L + 200L
        calculatedSum shouldBe 300L
    }
}