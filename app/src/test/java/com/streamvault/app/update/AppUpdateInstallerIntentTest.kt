package com.streamvault.app.update

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateInstallerIntentTest {
    @Test
    fun packageInstallIntentSharesApkUriWithReadPermission() {
        val apkUri = Uri.parse("content://com.streamvault.app.fileprovider/apk/update.apk")

        val intent = buildUpdateInstallIntent(Intent.ACTION_INSTALL_PACKAGE, apkUri)

        assertThat(intent.action).isEqualTo(Intent.ACTION_INSTALL_PACKAGE)
        assertThat(intent.data).isEqualTo(apkUri)
        assertThat(intent.type).isEqualTo("application/vnd.android.package-archive")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.clipData?.getItemAt(0)?.uri).isEqualTo(apkUri)
    }
}
