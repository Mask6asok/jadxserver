package jadx.server.engine

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

object AndroidManifestParser {
    fun parse(xml: String): AndroidManifestInfo {
        val document = parseDocument(xml)
        val manifest = document.documentElement
        val packageName = manifest.attr("package") ?: ""
        val usesSdk = manifest.directChildren("uses-sdk").firstOrNull()
        val application = manifest.directChildren("application").firstOrNull()

        return AndroidManifestInfo(
            packageName = packageName,
            versionName = manifest.androidAttr("versionName"),
            versionCode = manifest.androidAttr("versionCode")?.toIntOrNull(),
            compileSdkVersion = manifest.androidAttr("compileSdkVersion")?.toIntOrNull(),
            compileSdkVersionCodename = manifest.androidAttr("compileSdkVersionCodename"),
            platformBuildVersionCode = manifest.attr("platformBuildVersionCode")?.toIntOrNull(),
            platformBuildVersionName = manifest.attr("platformBuildVersionName"),
            minSdk = usesSdk?.androidAttr("minSdkVersion")?.toIntOrNull(),
            targetSdk = usesSdk?.androidAttr("targetSdkVersion")?.toIntOrNull(),
            application = application?.toApplicationInfo(packageName),
            usesPermissions = manifest.directChildren("uses-permission").map { it.toUsesPermission() },
            declaredPermissions = manifest.directChildren("permission").map { it.toDeclaredPermission() },
            usesFeatures = manifest.directChildren("uses-feature").map { it.toUsesFeature() },
            components = application?.directChildren()
                ?.filter { it.tagName in manifestComponentTags }
                ?.map { it.toComponent(packageName) }
                .orEmpty()
        )
    }

    fun searchComponents(
        xml: String,
        query: String,
        type: String = "all",
        regex: Regex? = null,
        caseSensitive: Boolean = false,
        limit: Int = 100
    ): List<ManifestComponentNodeMatch>? {
        val document = parseDocument(xml)
        val manifest = document.documentElement
        val packageName = manifest.attr("package") ?: ""
        val componentType = type.toComponentTypeOrNull()
        if (!type.equals("all", ignoreCase = true) && componentType == null) return null
        val components = manifest.directChildren("application").firstOrNull()
            ?.directChildren()
            ?.filter { it.tagName in manifestComponentTags }
            ?.filter { componentType == null || it.toComponentType() == componentType }
            .orEmpty()

        val matches = mutableListOf<ManifestComponentNodeMatch>()
        for (component in components) {
            val nodeXml = component.toXmlString()
            val name = component.androidAttr("name")?.normalizeClassName(packageName) ?: ""
            val searchable = "$name\n$nodeXml"
            val matched = regex?.containsMatchIn(searchable)
                ?: searchable.contains(query, ignoreCase = !caseSensitive)
            if (!matched) continue

            matches += ManifestComponentNodeMatch(
                type = component.toComponentType(),
                name = name,
                xml = nodeXml
            )
            if (matches.size >= limit) break
        }
        return matches
    }

    private fun parseDocument(xml: String) = DocumentBuilderFactory.newInstance()
        .apply {
            isNamespaceAware = true
            isIgnoringComments = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))

    private fun Element.toApplicationInfo(packageName: String): AndroidApplicationInfo {
        return AndroidApplicationInfo(
            name = androidAttr("name")?.normalizeClassName(packageName),
            label = androidAttr("label"),
            icon = androidAttr("icon"),
            theme = androidAttr("theme"),
            debuggable = androidAttr("debuggable")?.toBooleanStrictOrNull(),
            allowBackup = androidAttr("allowBackup")?.toBooleanStrictOrNull(),
            largeHeap = androidAttr("largeHeap")?.toBooleanStrictOrNull(),
            supportsRtl = androidAttr("supportsRtl")?.toBooleanStrictOrNull(),
            extractNativeLibs = androidAttr("extractNativeLibs")?.toBooleanStrictOrNull(),
            resizeableActivity = androidAttr("resizeableActivity")?.toBooleanStrictOrNull(),
            networkSecurityConfig = androidAttr("networkSecurityConfig"),
            appComponentFactory = androidAttr("appComponentFactory"),
            requestLegacyExternalStorage = androidAttr("requestLegacyExternalStorage")?.toBooleanStrictOrNull()
        )
    }

    private fun Element.toUsesPermission(): ManifestUsesPermission {
        return ManifestUsesPermission(
            name = androidAttr("name") ?: "",
            maxSdkVersion = androidAttr("maxSdkVersion")?.toIntOrNull()
        )
    }

    private fun Element.toDeclaredPermission(): ManifestDeclaredPermission {
        return ManifestDeclaredPermission(
            name = androidAttr("name") ?: "",
            protectionLevel = androidAttr("protectionLevel"),
            label = androidAttr("label"),
            description = androidAttr("description")
        )
    }

    private fun Element.toUsesFeature(): ManifestUsesFeature {
        return ManifestUsesFeature(
            name = androidAttr("name") ?: "",
            required = androidAttr("required")?.toBooleanStrictOrNull()
        )
    }

    private fun Element.toComponent(packageName: String): ManifestComponent {
        val type = toComponentType()
        return ManifestComponent(
            type = type,
            name = androidAttr("name")?.normalizeClassName(packageName) ?: "",
            targetActivity = androidAttr("targetActivity")?.normalizeClassName(packageName),
            label = androidAttr("label"),
            icon = androidAttr("icon"),
            theme = androidAttr("theme"),
            exported = androidAttr("exported")?.toBooleanStrictOrNull(),
            enabled = androidAttr("enabled")?.toBooleanStrictOrNull(),
            permission = androidAttr("permission"),
            readPermission = androidAttr("readPermission"),
            writePermission = androidAttr("writePermission"),
            process = androidAttr("process"),
            authorities = androidAttr("authorities"),
            grantUriPermissions = androidAttr("grantUriPermissions")?.toBooleanStrictOrNull(),
            launchMode = androidAttr("launchMode"),
            taskAffinity = androidAttr("taskAffinity"),
            screenOrientation = androidAttr("screenOrientation"),
            configChanges = androidAttr("configChanges"),
            directBootAware = androidAttr("directBootAware")?.toBooleanStrictOrNull(),
            intentFilters = directChildren("intent-filter").map { it.toIntentFilter() },
            metaData = directChildren("meta-data").map { it.toMetaData() }
        )
    }

    private fun Element.toComponentType(): ManifestComponentType {
        return when (tagName) {
            "activity-alias" -> ManifestComponentType.ACTIVITY_ALIAS
            else -> ManifestComponentType.valueOf(tagName.uppercase().replace('-', '_'))
        }
    }

    private fun String.toComponentTypeOrNull(): ManifestComponentType? {
        return when (lowercase()) {
            "all" -> null
            "activity" -> ManifestComponentType.ACTIVITY
            "activity_alias", "activity-alias" -> ManifestComponentType.ACTIVITY_ALIAS
            "service" -> ManifestComponentType.SERVICE
            "receiver" -> ManifestComponentType.RECEIVER
            "provider" -> ManifestComponentType.PROVIDER
            else -> null
        }
    }

    private fun Element.toIntentFilter(): ManifestIntentFilter {
        return ManifestIntentFilter(
            autoVerify = androidAttr("autoVerify")?.toBooleanStrictOrNull(),
            actions = directChildren("action").mapNotNull { it.androidAttr("name") },
            categories = directChildren("category").mapNotNull { it.androidAttr("name") },
            data = directChildren("data").map { it.toIntentData() }
        )
    }

    private fun Element.toIntentData(): ManifestIntentData {
        return ManifestIntentData(
            scheme = androidAttr("scheme"),
            host = androidAttr("host"),
            port = androidAttr("port"),
            path = androidAttr("path"),
            pathPrefix = androidAttr("pathPrefix"),
            pathPattern = androidAttr("pathPattern"),
            mimeType = androidAttr("mimeType")
        )
    }

    private fun Element.toMetaData(): ManifestMetaData {
        return ManifestMetaData(
            name = androidAttr("name") ?: "",
            value = androidAttr("value"),
            resource = androidAttr("resource")
        )
    }

    private fun Element.directChildren(tagName: String? = null): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            if (tagName == null || element.tagName == tagName) {
                result += element
            }
        }
        return result
    }

    private fun Element.androidAttr(name: String): String? {
        val namespaced = getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }
        return namespaced ?: attr("android:$name")
    }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    private fun String.normalizeClassName(packageName: String): String {
        return when {
            startsWith(".") -> packageName + this
            '.' !in this -> "$packageName.$this"
            else -> this
        }
    }

    private fun Element.toXmlString(): String {
        val writer = StringWriter()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        transformer.transform(DOMSource(this), StreamResult(writer))
        return writer.toString().trim()
    }

    private val manifestComponentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
}

data class AndroidManifestInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Int?,
    val compileSdkVersion: Int?,
    val compileSdkVersionCodename: String?,
    val platformBuildVersionCode: Int?,
    val platformBuildVersionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val application: AndroidApplicationInfo?,
    val usesPermissions: List<ManifestUsesPermission>,
    val declaredPermissions: List<ManifestDeclaredPermission>,
    val usesFeatures: List<ManifestUsesFeature>,
    val components: List<ManifestComponent>
)

data class AndroidApplicationInfo(
    val name: String?,
    val label: String?,
    val icon: String?,
    val theme: String?,
    val debuggable: Boolean?,
    val allowBackup: Boolean?,
    val largeHeap: Boolean?,
    val supportsRtl: Boolean?,
    val extractNativeLibs: Boolean?,
    val resizeableActivity: Boolean?,
    val networkSecurityConfig: String?,
    val appComponentFactory: String?,
    val requestLegacyExternalStorage: Boolean?
)

data class ManifestUsesPermission(
    val name: String,
    val maxSdkVersion: Int?
)

data class ManifestDeclaredPermission(
    val name: String,
    val protectionLevel: String?,
    val label: String?,
    val description: String?
)

data class ManifestUsesFeature(
    val name: String,
    val required: Boolean?
)

enum class ManifestComponentType {
    ACTIVITY,
    ACTIVITY_ALIAS,
    SERVICE,
    RECEIVER,
    PROVIDER
}

data class ManifestComponent(
    val type: ManifestComponentType,
    val name: String,
    val targetActivity: String?,
    val label: String?,
    val icon: String?,
    val theme: String?,
    val exported: Boolean?,
    val enabled: Boolean?,
    val permission: String?,
    val readPermission: String?,
    val writePermission: String?,
    val process: String?,
    val authorities: String?,
    val grantUriPermissions: Boolean?,
    val launchMode: String?,
    val taskAffinity: String?,
    val screenOrientation: String?,
    val configChanges: String?,
    val directBootAware: Boolean?,
    val intentFilters: List<ManifestIntentFilter>,
    val metaData: List<ManifestMetaData>
)

data class ManifestIntentFilter(
    val autoVerify: Boolean?,
    val actions: List<String>,
    val categories: List<String>,
    val data: List<ManifestIntentData>
)

data class ManifestIntentData(
    val scheme: String?,
    val host: String?,
    val port: String?,
    val path: String?,
    val pathPrefix: String?,
    val pathPattern: String?,
    val mimeType: String?
)

data class ManifestMetaData(
    val name: String,
    val value: String?,
    val resource: String?
)

data class ManifestComponentNodeMatch(
    val type: ManifestComponentType,
    val name: String,
    val xml: String
)
