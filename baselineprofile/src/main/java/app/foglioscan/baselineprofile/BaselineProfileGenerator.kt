package app.foglioscan.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code paths of the critical user journey — cold start, then opening the camera
 * with live page detection — so they ship AOT-compiled in release builds.
 *
 * Run: ./gradlew :app:generateReleaseBaselineProfile (with a device/emulator on API 33+ connected)
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndCamera() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        device.executeShellCommand("pm grant $PACKAGE android.permission.CAMERA")
        pressHome()
        startActivityAndWait()

        device.wait(Until.hasObject(By.res("scan")), 5_000)
        device.findObject(By.res("scan"))?.click()
        device.wait(Until.hasObject(By.res("shutter")), 10_000)
        // Let a few analysis frames go through the detector.
        Thread.sleep(3_000)
        device.pressBack()
        device.wait(Until.hasObject(By.res("scan")), 5_000)
    }

    private companion object {
        const val PACKAGE = "app.foglioscan"
    }
}
