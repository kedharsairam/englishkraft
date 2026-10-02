package com.krafttools.englishkraft.data

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "no permissions" claim, asked of the OS rather than of the source.
 *
 * This test exists because the claim had two readings and nothing separated them.
 * `aapt2 dump permissions` on the release APK reported one permission and
 * `dumpsys package` on the device reported none — both true of the same build. The
 * permission was androidx.core's own signature-level declaration for
 * `ContextCompat.registerReceiver`, which this app never calls. The README's claim was
 * true of the app and false of its APK, and nothing in the build noticed.
 *
 * Reading it from the installed package is deliberate. A test that greps the manifest
 * would have passed here, because the app's own manifest never had it: it arrived from
 * a dependency. Only the merged, installed manifest carries the truth.
 */
@RunWith(AndroidJUnit4::class)
class ZeroPermissionsTest {

    private fun installed(): android.content.pm.PackageInfo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        return pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }

    @Test
    fun theAppRequestsNoPermissions() {
        val requested = installed().requestedPermissions
        assertEquals(
            "this app requests no permissions. Every one added here is a promise " +
                "broken, and the README says so in its first line",
            0,
            requested?.size ?: 0,
        )
    }

    @Test
    fun theAppDefinesNoPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .permissions
        assertTrue(
            "a library injected a permission declaration the app has no use for: " +
                declared?.joinToString(),
            declared.isNullOrEmpty(),
        )
    }

    @Test
    fun nothingTheAppDoesIsHiddenBehindAPermission() {
        // The two absences that matter and are easiest to break by accident. A reviewer
        // should not have to take the manifest's word for either.
        val requested = installed().requestedPermissions?.toList().orEmpty()
        for (forbidden in listOf(
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.POST_NOTIFICATIONS",
        )) {
            assertTrue(
                "$forbidden is requested, and the app has no business asking for it",
                forbidden !in requested,
            )
        }
    }
}
