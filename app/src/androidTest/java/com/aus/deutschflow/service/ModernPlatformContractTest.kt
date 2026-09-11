package com.aus.deutschflow.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Assertions that only become meaningful on current Android behavior. */
@RunWith(AndroidJUnit4::class)
class ModernPlatformContractTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun modernBuildDeclaresRuntimeNotificationPermission() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertTrue(
            "POST_NOTIFICATIONS must be declared on API 33+",
            Manifest.permission.POST_NOTIFICATIONS in requested
        )
    }

    @Test
    fun modernBuildPublishesDataExtractionRules() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        @Suppress("DEPRECATION")
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue(
            "Android OS backup must remain explicitly enabled",
            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        )

        context.resources.getXml(R.xml.data_extraction_rules).use { parser ->
            while (parser.eventType != XmlPullParser.START_TAG &&
                parser.eventType != XmlPullParser.END_DOCUMENT
            ) {
                parser.next()
            }
            assertTrue(
                "data_extraction_rules.xml must contain the platform policy root",
                parser.name == "data-extraction-rules"
            )
        }
    }
}
