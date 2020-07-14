/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.ui.foundation

import android.os.Handler
import android.os.Looper
import androidx.animation.ExponentialDecay
import androidx.animation.ManualAnimationClock
import androidx.annotation.RequiresApi
import androidx.compose.Composable
import androidx.compose.mutableStateOf
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import androidx.ui.core.Modifier
import androidx.ui.core.testTag
import androidx.ui.foundation.animation.FlingConfig
import androidx.ui.graphics.Color
import androidx.ui.layout.Stack
import androidx.ui.layout.preferredHeight
import androidx.ui.layout.preferredSize
import androidx.ui.test.GestureScope
import androidx.ui.test.SemanticsNodeInteraction
import androidx.ui.test.StateRestorationTester
import androidx.ui.test.assertIsDisplayed
import androidx.ui.test.assertIsNotDisplayed
import androidx.ui.test.assertPixels
import androidx.ui.test.captureToBitmap
import androidx.ui.test.createComposeRule
import androidx.ui.test.performGesture
import androidx.ui.test.performScrollTo
import androidx.ui.test.onNodeWithTag
import androidx.ui.test.onNodeWithText
import androidx.ui.test.runOnIdle
import androidx.ui.test.runOnUiThread
import androidx.ui.test.click
import androidx.ui.test.swipeDown
import androidx.ui.test.swipeLeft
import androidx.ui.test.swipeRight
import androidx.ui.test.swipeUp
import androidx.ui.unit.Dp
import androidx.ui.unit.IntSize
import androidx.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SmallTest
@RunWith(JUnit4::class)
class ScrollerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val scrollerTag = "ScrollerTest"

    private val defaultCrossAxisSize = 45
    private val defaultMainAxisSize = 40
    private val defaultCellSize = 5

    private val colors = listOf(
        Color(red = 0xFF, green = 0, blue = 0, alpha = 0xFF),
        Color(red = 0xFF, green = 0xA5, blue = 0, alpha = 0xFF),
        Color(red = 0xFF, green = 0xFF, blue = 0, alpha = 0xFF),
        Color(red = 0xA5, green = 0xFF, blue = 0, alpha = 0xFF),
        Color(red = 0, green = 0xFF, blue = 0, alpha = 0xFF),
        Color(red = 0, green = 0xFF, blue = 0xA5, alpha = 0xFF),
        Color(red = 0, green = 0, blue = 0xFF, alpha = 0xFF),
        Color(red = 0xA5, green = 0, blue = 0xFF, alpha = 0xFF)
    )

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun verticalScroller_SmallContent() {
        val height = 40

        composeVerticalScroller(height = height)

        validateVerticalScroller(height = height)
    }

    @Test
    fun verticalScroller_SmallContent_Unscrollable() {
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0)
        )

        composeVerticalScroller(scrollerPosition)

        runOnIdle {
            assertTrue(scrollerPosition.maxPosition == 0f)
        }
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun verticalScroller_LargeContent_NoScroll() {
        val height = 30

        composeVerticalScroller(height = height)

        validateVerticalScroller(height = height)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun verticalScroller_LargeContent_ScrollToEnd() {
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0)
        )
        val height = 30
        val scrollDistance = 10

        composeVerticalScroller(scrollerPosition, height = height)

        validateVerticalScroller(height = height)

        runOnIdle {
            assertEquals(scrollDistance.toFloat(), scrollerPosition.maxPosition)
            scrollerPosition.scrollTo(scrollDistance.toFloat())
        }

        runOnIdle {} // Just so the block below is correct
        validateVerticalScroller(offset = scrollDistance, height = height)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun verticalScroller_Reversed() {
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0),
            isReversed = true
        )
        val height = 30
        val expectedOffset = defaultCellSize * colors.size - height

        composeVerticalScroller(scrollerPosition, height = height)

        validateVerticalScroller(offset = expectedOffset, height = height)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun verticalScroller_LargeContent_Reversed_ScrollToEnd() {
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0),
            isReversed = true
        )
        val height = 20
        val scrollDistance = 10
        val expectedOffset = defaultCellSize * colors.size - height - scrollDistance

        composeVerticalScroller(scrollerPosition, height = height)

        runOnIdle {
            scrollerPosition.scrollTo(scrollDistance.toFloat())
        }

        runOnIdle {} // Just so the block below is correct
        validateVerticalScroller(offset = expectedOffset, height = height)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun horizontalScroller_SmallContent() {
        val width = 40

        composeHorizontalScroller(width = width)

        validateHorizontalScroller(width = width)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun horizontalScroller_LargeContent_NoScroll() {
        val width = 30

        composeHorizontalScroller(width = width)

        validateHorizontalScroller(width = width)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun horizontalScroller_LargeContent_ScrollToEnd() {
        val width = 30
        val scrollDistance = 10

        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0)
        )

        composeHorizontalScroller(scrollerPosition, width = width)

        validateHorizontalScroller(width = width)

        runOnIdle {
            assertEquals(scrollDistance.toFloat(), scrollerPosition.maxPosition)
            scrollerPosition.scrollTo(scrollDistance.toFloat())
        }

        runOnIdle {} // Just so the block below is correct
        validateHorizontalScroller(offset = scrollDistance, width = width)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun horizontalScroller_reversed() {
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0),
            isReversed = true
        )
        val width = 30
        val expectedOffset = defaultCellSize * colors.size - width

        composeHorizontalScroller(scrollerPosition, width = width)

        validateHorizontalScroller(offset = expectedOffset, width = width)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun horizontalScroller_LargeContent_Reversed_ScrollToEnd() {
        val width = 30
        val scrollDistance = 10

        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0),
            isReversed = true
        )

        val expectedOffset = defaultCellSize * colors.size - width - scrollDistance

        composeHorizontalScroller(scrollerPosition, width = width)

        runOnIdle {
            scrollerPosition.scrollTo(scrollDistance.toFloat())
        }

        runOnIdle {} // Just so the block below is correct
        validateHorizontalScroller(offset = expectedOffset, width = width)
    }

    @Test
    fun verticalScroller_scrollTo_scrollForward() {
        createScrollableContent(isVertical = true)

        onNodeWithText("50")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun horizontalScroller_scrollTo_scrollForward() {
        createScrollableContent(isVertical = false)

        onNodeWithText("50")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Ignore("Unignore when b/156389287 is fixed for proper reverve delegation")
    @Test
    fun verticalScroller_reversed_scrollTo_scrollForward() {
        createScrollableContent(
            isVertical = true, scrollerPosition = ScrollerPosition(
                FlingConfig(ExponentialDecay()),
                animationClock = ManualAnimationClock(0),
                isReversed = true
            )
        )

        onNodeWithText("50")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Ignore("Unignore when b/156389287 is fixed for proper reverve delegation")
    @Test
    fun horizontalScroller_reversed_scrollTo_scrollForward() {
        createScrollableContent(
            isVertical = false, scrollerPosition = ScrollerPosition(
                FlingConfig(ExponentialDecay()),
                animationClock = ManualAnimationClock(0),
                isReversed = true
            )
        )

        onNodeWithText("50")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun verticalScroller_scrollTo_scrollBack() {
        createScrollableContent(isVertical = true)

        onNodeWithText("50")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()

        onNodeWithText("20")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun horizontalScroller_scrollTo_scrollBack() {
        createScrollableContent(isVertical = false)

        onNodeWithText("50")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()

        onNodeWithText("20")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun verticalScroller_swipeUp_swipeDown() {
        swipeScrollerAndBack(true, GestureScope::swipeUp, GestureScope::swipeDown)
    }

    @Test
    fun horizontalScroller_swipeLeft_swipeRight() {
        swipeScrollerAndBack(false, GestureScope::swipeLeft, GestureScope::swipeRight)
    }

    @Test
    fun scroller_coerce_whenScrollTo() {
        val clock = ManualAnimationClock(0)
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = clock
        )

        createScrollableContent(isVertical = true, scrollerPosition = scrollerPosition)

        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(0f)
            assertThat(scrollerPosition.maxPosition).isGreaterThan(0f)
        }
        runOnUiThread {
            scrollerPosition.scrollTo(-100f)
        }
        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(0f)
        }
        runOnUiThread {
            scrollerPosition.scrollBy(-100f)
        }
        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(0f)
        }
        runOnUiThread {
            scrollerPosition.scrollTo(scrollerPosition.maxPosition)
        }
        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(scrollerPosition.maxPosition)
        }
        runOnUiThread {
            scrollerPosition.scrollTo(scrollerPosition.maxPosition + 1000)
        }
        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(scrollerPosition.maxPosition)
        }
        runOnUiThread {
            scrollerPosition.scrollBy(100f)
        }
        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(scrollerPosition.maxPosition)
        }
    }

    @Test
    fun verticalScroller_LargeContent_coerceWhenMaxChanges() {
        val clock = ManualAnimationClock(0)
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = clock
        )
        val itemCount = mutableStateOf(100)
        composeTestRule.setContent {
            Stack {
                VerticalScroller(
                    scrollerPosition = scrollerPosition,
                    modifier = Modifier.preferredSize(100.dp).testTag(scrollerTag)
                ) {
                    for (i in 0..itemCount.value) {
                        Text(i.toString())
                    }
                }
            }
        }

        val max = runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(0f)
            assertThat(scrollerPosition.maxPosition).isGreaterThan(0f)
            scrollerPosition.maxPosition
        }

        runOnUiThread {
            scrollerPosition.scrollTo(max)
        }
        runOnUiThread {
            itemCount.value -= 2
        }
        runOnIdle {
            val newMax = scrollerPosition.maxPosition
            assertThat(newMax).isLessThan(max)
            assertThat(scrollerPosition.value).isEqualTo(newMax)
        }
    }

    @Test
    fun scroller_coerce_whenScrollSmoothTo() {
        val clock = ManualAnimationClock(0)
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = clock
        )

        createScrollableContent(isVertical = true, scrollerPosition = scrollerPosition)

        val max = runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(0f)
            assertThat(scrollerPosition.maxPosition).isGreaterThan(0f)
            scrollerPosition.maxPosition
        }

        performWithAnimationWaitAndAssertPosition(0f, scrollerPosition, clock) {
            scrollerPosition.smoothScrollTo(-100f)
        }

        performWithAnimationWaitAndAssertPosition(0f, scrollerPosition, clock) {
            scrollerPosition.smoothScrollBy(-100f)
        }

        performWithAnimationWaitAndAssertPosition(max, scrollerPosition, clock) {
            scrollerPosition.smoothScrollTo(scrollerPosition.maxPosition)
        }

        performWithAnimationWaitAndAssertPosition(max, scrollerPosition, clock) {
            scrollerPosition.smoothScrollTo(scrollerPosition.maxPosition + 1000)
        }
        performWithAnimationWaitAndAssertPosition(max, scrollerPosition, clock) {
            scrollerPosition.smoothScrollBy(100f)
        }
    }

    @Test
    fun scroller_whenFling_stopsByTouchDown() {
        val clock = ManualAnimationClock(0)
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = clock
        )

        createScrollableContent(isVertical = true, scrollerPosition = scrollerPosition)

        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(0f)
            assertThat(scrollerPosition.isAnimating).isEqualTo(false)
        }

        onNodeWithTag(scrollerTag)
            .performGesture { swipeUp() }

        runOnIdle {
            clock.clockTimeMillis += 100
            assertThat(scrollerPosition.isAnimating).isEqualTo(true)
        }

        // TODO (matvei/jelle): this should be down, and not click to be 100% fair
        onNodeWithTag(scrollerTag)
            .performGesture { click() }

        runOnIdle {
            assertThat(scrollerPosition.isAnimating).isEqualTo(false)
        }
    }

    @Test
    fun scroller_restoresScrollerPosition() {
        val restorationTester = StateRestorationTester(composeTestRule)
        var scrollerPosition: ScrollerPosition? = null

        restorationTester.setContent {
            scrollerPosition = ScrollerPosition()
            VerticalScroller(scrollerPosition!!) {
                repeat(50) {
                    Box(Modifier.preferredHeight(100.dp))
                }
            }
        }

        runOnIdle {
            scrollerPosition!!.scrollTo(70f)
            scrollerPosition = null
        }

        restorationTester.emulateSavedInstanceStateRestore()

        runOnIdle {
            assertThat(scrollerPosition!!.value).isEqualTo(70f)
        }
    }

    private fun performWithAnimationWaitAndAssertPosition(
        assertValue: Float,
        scrollerPosition: ScrollerPosition,
        clock: ManualAnimationClock,
        uiAction: () -> Unit
    ) {
        runOnUiThread {
            uiAction.invoke()
        }
        runOnIdle {
            clock.clockTimeMillis += 5000
        }
        onNodeWithTag(scrollerTag).awaitScrollAnimation(scrollerPosition)
        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(assertValue)
        }
    }

    private fun swipeScrollerAndBack(
        isVertical: Boolean,
        firstSwipe: GestureScope.() -> Unit,
        secondSwipe: GestureScope.() -> Unit
    ) {
        val clock = ManualAnimationClock(0)
        val scrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = clock
        )

        createScrollableContent(isVertical, scrollerPosition = scrollerPosition)

        runOnIdle {
            assertThat(scrollerPosition.value).isEqualTo(0f)
        }

        onNodeWithTag(scrollerTag)
            .performGesture { firstSwipe() }

        runOnIdle {
            clock.clockTimeMillis += 5000
        }

        onNodeWithTag(scrollerTag)
            .awaitScrollAnimation(scrollerPosition)

        val scrolledValue = runOnIdle {
            scrollerPosition.value
        }
        assertThat(scrolledValue).isGreaterThan(0f)

        onNodeWithTag(scrollerTag)
            .performGesture { secondSwipe() }

        runOnIdle {
            clock.clockTimeMillis += 5000
        }

        onNodeWithTag(scrollerTag)
            .awaitScrollAnimation(scrollerPosition)

        runOnIdle {
            assertThat(scrollerPosition.value).isLessThan(scrolledValue)
        }
    }

    private fun composeVerticalScroller(
        scrollerPosition: ScrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0)
        ),
        width: Int = defaultCrossAxisSize,
        height: Int = defaultMainAxisSize,
        rowHeight: Int = defaultCellSize
    ) {
        // We assume that the height of the device is more than 45 px
        with(composeTestRule.density) {
            composeTestRule.setContent {
                Stack {
                    VerticalScroller(
                        scrollerPosition = scrollerPosition,
                        modifier = Modifier
                            .preferredSize(width.toDp(), height.toDp())
                            .testTag(scrollerTag)
                    ) {
                        colors.forEach { color ->
                            Box(
                                Modifier.preferredSize(width.toDp(), rowHeight.toDp()),
                                backgroundColor = color
                            )
                        }
                    }
                }
            }
        }
    }

    private fun composeHorizontalScroller(
        scrollerPosition: ScrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0)
        ),
        width: Int = defaultMainAxisSize,
        height: Int = defaultCrossAxisSize,
        columnWidth: Int = defaultCellSize
    ) {
        // We assume that the height of the device is more than 45 px
        with(composeTestRule.density) {
            composeTestRule.setContent {
                Stack {
                    HorizontalScroller(
                        scrollerPosition = scrollerPosition,
                        modifier = Modifier
                            .preferredSize(width.toDp(), height.toDp())
                            .testTag(scrollerTag)
                    ) {
                        colors.forEach { color ->
                            Box(
                                Modifier.preferredSize(columnWidth.toDp(), height.toDp()),
                                backgroundColor = color
                            )
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(api = 26)
    private fun validateVerticalScroller(
        offset: Int = 0,
        width: Int = 45,
        height: Int = 40,
        rowHeight: Int = 5
    ) {
        onNodeWithTag(scrollerTag)
            .captureToBitmap()
            .assertPixels(expectedSize = IntSize(width, height)) { pos ->
                val colorIndex = (offset + pos.y) / rowHeight
                colors[colorIndex]
            }
    }

    @RequiresApi(api = 26)
    private fun validateHorizontalScroller(
        offset: Int = 0,
        width: Int = 40,
        height: Int = 45,
        columnWidth: Int = 5
    ) {
        onNodeWithTag(scrollerTag)
            .captureToBitmap()
            .assertPixels(expectedSize = IntSize(width, height)) { pos ->
                val colorIndex = (offset + pos.x) / columnWidth
                colors[colorIndex]
            }
    }

    private fun createScrollableContent(
        isVertical: Boolean,
        itemCount: Int = 100,
        width: Dp = 100.dp,
        height: Dp = 100.dp,
        scrollerPosition: ScrollerPosition = ScrollerPosition(
            FlingConfig(ExponentialDecay()),
            animationClock = ManualAnimationClock(0)
        )
    ) {
        composeTestRule.setContent {
            val content = @Composable {
                repeat(itemCount) {
                    Text(text = "$it")
                }
            }
            Stack {
                Box(
                    Modifier.preferredSize(width, height),
                    backgroundColor = Color.White
                ) {
                    if (isVertical) {
                        VerticalScroller(
                            scrollerPosition,
                            modifier = Modifier.testTag(scrollerTag)
                        ) {
                            content()
                        }
                    } else {
                        HorizontalScroller(
                            scrollerPosition,
                            modifier = Modifier.testTag(scrollerTag)
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    // TODO(b/147291885): This should not be needed in the future.
    private fun SemanticsNodeInteraction.awaitScrollAnimation(
        scroller: ScrollerPosition
    ): SemanticsNodeInteraction {
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                if (scroller.isAnimating) {
                    handler.post(this)
                } else {
                    latch.countDown()
                }
            }
        })
        assertWithMessage("Scroll didn't finish after 20 seconds")
            .that(latch.await(20, TimeUnit.SECONDS)).isTrue()
        return this
    }
}