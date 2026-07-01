package jadx.server

import jadx.server.engine.AndroidManifestParser
import jadx.server.engine.ManifestComponentType
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidManifestParserTest {
    private fun parseVmallManifest() = AndroidManifestParser.parse(
        Path.of(System.getProperty("user.dir"))
            .resolve("test/manifest/vmall.xml")
            .toFile()
            .readText()
    )

    @Test
    fun parsesManifestSummary() {
        val manifest = parseVmallManifest()

        assertEquals("com.vmall.client", manifest.packageName)
        assertEquals("1.26.5.302", manifest.versionName)
        assertEquals(12605302, manifest.versionCode)
        assertEquals(34, manifest.compileSdkVersion)
        assertEquals("14", manifest.compileSdkVersionCodename)
        assertEquals(21, manifest.minSdk)
        assertEquals(30, manifest.targetSdk)

        val app = assertNotNull(manifest.application)
        assertEquals("com.vmall.client.VmallApplication", app.name)
        assertEquals("@string/app_name", app.label)
        assertEquals("@drawable/logo3_0", app.icon)
        assertEquals("@style/Theme.AppStartLoadTranslucent", app.theme)
        assertEquals(false, app.allowBackup)
        assertEquals("@xml/network_security_config", app.networkSecurityConfig)
        assertEquals(true, app.requestLegacyExternalStorage)
    }

    @Test
    fun parsesPermissionsAndFeatures() {
        val manifest = parseVmallManifest()

        assertTrue(manifest.usesPermissions.any { it.name == "android.permission.CAMERA" })
        assertTrue(manifest.usesPermissions.any { it.name == "android.permission.INTERNET" })
        assertTrue(manifest.usesPermissions.any { it.name == "com.vmall.client.permission.PROCESS_PUSH_MSG" })
        assertTrue(manifest.declaredPermissions.any {
            it.name == "com.vmall.client.permission.PROCESS_PUSH_MSG" &&
                it.protectionLevel == "signature"
        })
        assertTrue(manifest.usesFeatures.any { it.name == "android.hardware.camera" })
        assertTrue(manifest.usesFeatures.any { it.name == "android.hardware.camera.autofocus" })
    }

    @Test
    fun parsesComponentsAndProviderAttributes() {
        val manifest = parseVmallManifest()

        assertTrue(manifest.components.any {
            it.type == ManifestComponentType.ACTIVITY &&
                it.name == "com.vmall.client.splash.fragment.StartAdsActivity" &&
                it.exported == true
        })
        assertTrue(manifest.components.any {
            it.type == ManifestComponentType.SERVICE &&
                it.name == "com.huawei.hms.support.api.push.service.HmsMsgService" &&
                it.exported == true &&
                it.process == ":pushservice"
        })
        assertTrue(manifest.components.any {
            it.type == ManifestComponentType.RECEIVER &&
                it.name == "com.huawei.hms.support.api.push.PushMsgReceiver" &&
                it.permission == "com.vmall.client.permission.PROCESS_PUSH_MSG"
        })
        assertTrue(manifest.components.any {
            it.type == ManifestComponentType.PROVIDER &&
                it.name == "com.huawei.hms.support.api.push.PushProvider" &&
                it.exported == true &&
                it.authorities == "com.vmall.client.huawei.push.provider" &&
                it.readPermission == "com.vmall.client.permission.PUSH_PROVIDER" &&
                it.writePermission == "com.vmall.client.permission.PUSH_WRITE_PROVIDER"
        })
    }

    @Test
    fun parsesLauncherAndDeepLinkIntentFilters() {
        val manifest = parseVmallManifest()

        val launcher = assertNotNull(manifest.components.firstOrNull {
            it.type == ManifestComponentType.ACTIVITY &&
                it.name == "com.vmall.client.splash.fragment.SplashActivity"
        })
        assertTrue(launcher.intentFilters.any {
            "android.intent.action.MAIN" in it.actions &&
                "android.intent.category.LAUNCHER" in it.categories
        })

        val startAds = assertNotNull(manifest.components.firstOrNull {
            it.type == ManifestComponentType.ACTIVITY &&
                it.name == "com.vmall.client.splash.fragment.StartAdsActivity"
        })
        assertTrue(startAds.intentFilters.any { filter ->
            "android.intent.action.VIEW" in filter.actions &&
                "android.intent.category.BROWSABLE" in filter.categories &&
                filter.data.any { it.scheme == "https" && it.host == "m.vmall.com" }
        })
        assertTrue(startAds.intentFilters.any { filter ->
            filter.data.any { it.scheme == "vmall" && it.host == "com.vmall.client" }
        })
    }

    @Test
    fun searchesComponentXmlNodes() {
        val xml = Path.of(System.getProperty("user.dir"))
            .resolve("test/manifest/vmall.xml")
            .toFile()
            .readText()

        val matches = assertNotNull(
            AndroidManifestParser.searchComponents(
                xml,
                "com.huawei.hms.support.api.push.service.HmsMsgService",
                type = "service"
            )
        )

        assertEquals(1, matches.size)
        assertEquals(ManifestComponentType.SERVICE, matches.first().type)
        assertEquals("com.huawei.hms.support.api.push.service.HmsMsgService", matches.first().name)
        assertTrue(matches.first().xml.startsWith("<service"))
        assertTrue(matches.first().xml.contains("HmsMsgService"))
        assertTrue(matches.first().xml.contains("android:process=\":pushservice\""))
    }
}
