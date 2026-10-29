package dev.cloneforge.patcher.manifest

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.reandroid.utils.collection.CollectionUtil
import dev.cloneforge.patcher.CfConstants
import java.io.File
import java.io.IOException

/**
 * Mutation result of the base APK (spec 3.1).
 */
data class MutationResult(
    val originalPackage: String,
    val mutatedPackage: String,
    val originalApplicationClass: String?,
    val originalAppComponentFactory: String?,
    val authorityMap: Map<String, String>,
    val permissionMap: Map<String, String>
)

/**
 * Binary AndroidManifest.xml & resources.arsc mutation engine (spec section 3).
 *
 * Implemented against the real ARSCLib V1.4.0 API:
 *  - ApkModule.loadApkFile(File), getAndroidManifest(), writeApk(File)
 *  - AndroidManifestBlock.getManifestElement()/setPackageName()
 *  - ResXmlElement.searchAttributeByName/ByResourceId,
 *    getOrCreateAndroidAttribute, newElement, removeAttributesWithId,
 *    removeAttributeIf, getElements(name), getElements(), equalsName()
 *  - ValueItem.setValueAsString / setValueAsBoolean / getValueType
 *  - TableBlock.getResource(id) -> ResourceEntry -> Entry.getResValue()
 */
class ManifestAndArscMutator {

    companion object {
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_PERMISSION = 0x01010006
        private const val ATTR_READ_PERMISSION = 0x01010007
        private const val ATTR_WRITE_PERMISSION = 0x01010008
        private const val ATTR_SHARED_USER_ID = 0x0101000b
        private const val ATTR_PROCESS = 0x01010011
        private const val ATTR_TASK_AFFINITY = 0x01010012
        private const val ATTR_LABEL = 0x01010001
        private const val ATTR_AUTHORITIES = 0x01010018
        private const val ATTR_META_VALUE = 0x01010024
        private const val ATTR_TARGET_ACTIVITY = 0x01010202
        private const val ATTR_APP_COMPONENT_FACTORY = 0x0101057a
        private const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea

        private const val TAG_APPLICATION = "application"
        private const val TAG_META_DATA = "meta-data"
        private const val TAG_PROVIDER = "provider"
        private const val TAG_PERMISSION = "permission"
        private const val TAG_PERMISSION_GROUP = "permission-group"
        private const val TAG_PERMISSION_TREE = "permission-tree"
        private const val TAG_USES_PERMISSION = "uses-permission"
        private const val TAG_USES_PERMISSION_SDK_23 = "uses-permission-sdk-23"

        private const val NAME_SHARED_USER_ID = "sharedUserId"
        private const val NAME_SHARED_USER_LABEL = "sharedUserLabel"
        private const val NAME_SHARED_USER_MAX_SDK_VERSION = "sharedUserMaxSdkVersion"

        private const val COMPONENT_TAGS = "activity.service.receiver.provider."
        private val COMPONENT_TAG_LIST =
            listOf("activity", "activity-alias", "service", "receiver", "provider")
    }

    /**
     * Applies the complete mutation matrix of spec 3.1 to [baseApkFile] and rewrites
     * it in place (write goes through a temp file so lazy source reads stay intact).
     */
    fun mutateBaseApk(
        baseApkFile: File,
        origPkg: String,
        clonePkg: String,
        cloneSuffix: String, // e.g., "cf001"
        customAppLabel: String
    ): MutationResult {
        val apkModule = ApkModule.loadApkFile(baseApkFile)
        // Our mutations do not need resource resolution against the Android
        // framework tables; skipping them avoids a heavy one-time load.
        apkModule.setLoadDefaultFramework(false)
        val manifest = apkModule.getAndroidManifest()
            ?: throw IOException("APK has no AndroidManifest.xml: $baseApkFile")
        val rootManifest: ResXmlElement = requireManifestElement(manifest)

        val authorityMap = linkedMapOf<String, String>()
        val permissionMap = linkedMapOf<String, String>()

        // 1. Strip sharedUserId / sharedUserLabel / sharedUserMaxSdkVersion from <manifest>.
        // Leaving sharedUserId causes INSTALL_FAILED_SHARED_USER_INCOMPATIBLE.
        rootManifest.removeAttributesWithId(ATTR_SHARED_USER_ID)
        rootManifest.removeAttributeIf { attr ->
            val n = attr.getName()
            n == NAME_SHARED_USER_ID || n == NAME_SHARED_USER_LABEL || n == NAME_SHARED_USER_MAX_SDK_VERSION
        }

        // 2. Rewrite <manifest package="...">.
        manifest.setPackageName(clonePkg)

        // 3. First pass on <permission>, <permission-group>, <permission-tree>
        //    and build the permission map.
        for (tagName in listOf(TAG_PERMISSION, TAG_PERMISSION_GROUP, TAG_PERMISSION_TREE)) {
            val elements = CollectionUtil.toList(rootManifest.getElements(tagName))
            for (el in elements) {
                val nameAttr = el.searchAttributeByResourceId(ATTR_NAME)
                val oldPerm = nameAttr?.getValueAsString()
                if (!oldPerm.isNullOrBlank()) {
                    val newPerm = "$oldPerm.$cloneSuffix"
                    permissionMap[oldPerm] = newPerm
                    nameAttr.setValueAsString(newPerm)
                }
            }
        }

        // 4. Rewrite <uses-permission> and <uses-permission-sdk-23>.
        for (tagName in listOf(TAG_USES_PERMISSION, TAG_USES_PERMISSION_SDK_23)) {
            val elements = CollectionUtil.toList(rootManifest.getElements(tagName))
            for (el in elements) {
                val nameAttr = el.searchAttributeByResourceId(ATTR_NAME)
                val permVal = nameAttr?.getValueAsString()
                if (!permVal.isNullOrBlank()) {
                    val mapped = permissionMap[permVal]
                        ?: if (permVal.startsWith("$origPkg.")) "$permVal.$cloneSuffix" else null
                    if (mapped != null) {
                        nameAttr.setValueAsString(mapped)
                    }
                }
            }
        }

        // 5. Mutate <application>.
        val appElement = CollectionUtil.toList(rootManifest.getElements(TAG_APPLICATION))
            .firstOrNull() ?: throw IOException("Missing <application> element in $baseApkFile")

        // Do NOT force extractNativeLibs to false. If an app contains compressed .so files
        // (e.g. Telegram, Forkgram), forcing extractNativeLibs=false causes Android PackageInstaller
        // to fail with INSTALL_FAILED_INVALID_APK: Failed to extract native libraries, res=-2.

        // Capture & replace application@android:name.
        val appNameAttr = appElement.searchAttributeByResourceId(ATTR_NAME)
        val rawOrigAppClass = appNameAttr?.getValueAsString()
        val expandedOrigAppClass = rawOrigAppClass?.let { expandClassName(origPkg, it) }
        appElement.getOrCreateAndroidAttribute("name", ATTR_NAME)
            .setValueAsString(CfConstants.BOOTSTRAP_APPLICATION)

        // Capture & replace application@android:appComponentFactory.
        val factoryAttr = appElement.searchAttributeByResourceId(ATTR_APP_COMPONENT_FACTORY)
        val rawOrigFactory = factoryAttr?.getValueAsString()
        val expandedOrigFactory = rawOrigFactory?.let { expandClassName(origPkg, it) }
        appElement.getOrCreateAndroidAttribute("appComponentFactory", ATTR_APP_COMPONENT_FACTORY)
            .setValueAsString(CfConstants.BOOTSTRAP_FACTORY)

        mutateProcessAndAffinity(appElement, origPkg, clonePkg)
        mutateComponentPermissions(appElement, origPkg, cloneSuffix, permissionMap)

        // 6. Traverse all component elements inside <application>.
        for (comp in collectComponents(appElement)) {
            // Expand android:name.
            val compNameAttr = comp.searchAttributeByResourceId(ATTR_NAME)
            val compName = compNameAttr?.getValueAsString()
            if (!compName.isNullOrBlank()) {
                compNameAttr.setValueAsString(expandClassName(origPkg, compName))
            }

            // Expand android:targetActivity on <activity-alias>.
            if (comp.equalsName("activity-alias")) {
                val targetAttr = comp.searchAttributeByResourceId(ATTR_TARGET_ACTIVITY)
                val targetVal = targetAttr?.getValueAsString()
                if (!targetVal.isNullOrBlank()) {
                    targetAttr.setValueAsString(expandClassName(origPkg, targetVal))
                }
            }

            // Mutate <provider> authorities and build the authority map.
            if (comp.equalsName(TAG_PROVIDER)) {
                val authAttr = comp.searchAttributeByResourceId(ATTR_AUTHORITIES)
                val rawAuths = authAttr?.getValueAsString()
                if (!rawAuths.isNullOrBlank()) {
                    val mutatedAuths = rawAuths.split(";")
                        .filter { it.isNotBlank() }
                        .joinToString(";") { singleAuth ->
                            val trimmed = singleAuth.trim()
                            val newAuth = "$trimmed.$cloneSuffix"
                            authorityMap[trimmed] = newAuth
                            newAuth
                        }
                    authAttr.setValueAsString(mutatedAuths)
                }
            }

            mutateProcessAndAffinity(comp, origPkg, clonePkg)
            mutateComponentPermissions(comp, origPkg, cloneSuffix, permissionMap)
        }

        // 7. Inject CloneForge bootstrap <meta-data> tags into <application>.
        injectMetaData(appElement, CfConstants.META_ORIG_PACKAGE, origPkg)
        injectMetaData(appElement, CfConstants.META_CLONE_PACKAGE, clonePkg)
        injectMetaData(appElement, CfConstants.META_CLONE_SUFFIX, cloneSuffix)
        if (!expandedOrigAppClass.isNullOrBlank()) {
            injectMetaData(appElement, CfConstants.META_ORIG_APPLICATION, expandedOrigAppClass)
        }
        if (!expandedOrigFactory.isNullOrBlank()) {
            injectMetaData(appElement, CfConstants.META_ORIG_FACTORY, expandedOrigFactory)
        }

        // 8. Patch the app label and launcher activity labels.
        patchApplicationAndLauncherLabels(appElement, customAppLabel)

        writeApkSafe(apkModule, baseApkFile)

        return MutationResult(
            originalPackage = origPkg,
            mutatedPackage = clonePkg,
            originalApplicationClass = expandedOrigAppClass,
            originalAppComponentFactory = expandedOrigFactory,
            authorityMap = authorityMap,
            permissionMap = permissionMap
        )
    }

    /**
     * Mutates a split APK's AndroidManifest.xml so its package attribute matches
     * base.apk (spec 3.2). The shared authority/permission maps from the base
     * pass keep expansion rules consistent when the split declares components.
     */
    fun mutateSplitApk(
        splitApkFile: File,
        origPkg: String,
        clonePkg: String,
        cloneSuffix: String,
        authorityMap: MutableMap<String, String> = linkedMapOf(),
        permissionMap: MutableMap<String, String> = linkedMapOf(),
    ) {
        val splitModule = ApkModule.loadApkFile(splitApkFile)
        splitModule.setLoadDefaultFramework(false)
        val manifest = splitModule.getAndroidManifest()
            ?: throw IOException("Split APK has no AndroidManifest.xml: $splitApkFile")
        val rootManifest = requireManifestElement(manifest)

        rootManifest.removeAttributesWithId(ATTR_SHARED_USER_ID)
        manifest.setPackageName(clonePkg)

        // Expand any relative component names if the feature split declares components.
        val appElement = CollectionUtil.toList(rootManifest.getElements(TAG_APPLICATION)).firstOrNull()
        if (appElement != null) {
            for (comp in collectComponents(appElement)) {
                val nameAttr = comp.searchAttributeByResourceId(ATTR_NAME)
                val compName = nameAttr?.getValueAsString()
                if (!compName.isNullOrBlank()) {
                    nameAttr.setValueAsString(expandClassName(origPkg, compName))
                }
                if (comp.equalsName(TAG_PROVIDER)) {
                    val authAttr = comp.searchAttributeByResourceId(ATTR_AUTHORITIES)
                    val rawAuths = authAttr?.getValueAsString()
                    if (!rawAuths.isNullOrBlank()) {
                        val mutated = rawAuths.split(";")
                            .filter { it.isNotBlank() }
                            .joinToString(";") { single ->
                                val trimmed = single.trim()
                                val mapped = authorityMap[trimmed] ?: "$trimmed.$cloneSuffix"
                                authorityMap[trimmed] = mapped
                                mapped
                            }
                        authAttr.setValueAsString(mutated)
                    }
                }
                mutateComponentPermissions(comp, origPkg, cloneSuffix, permissionMap)
            }
        }

        writeApkSafe(splitModule, splitApkFile)
    }

    // --------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------

    private fun requireManifestElement(manifest: AndroidManifestBlock): ResXmlElement {
        return manifest.getManifestElement()
            ?: throw IOException("AndroidManifest.xml has no <manifest> root")
    }

    /**
     * Collects direct child elements of [parent] whose tag is a component tag.
     * The list is materialized first so later structural additions (meta-data
     * injection) never happen while the node iterator is live.
     */
    private fun collectComponents(parent: ResXmlElement): List<ResXmlElement> {
        val result = ArrayList<ResXmlElement>()
        val iterator = parent.getElements()
        while (iterator.hasNext()) {
            val child = iterator.next() ?: continue
            for (tag in COMPONENT_TAG_LIST) {
                if (child.equalsName(tag)) {
                    result.add(child)
                    break
                }
            }
        }
        return result
    }

    private fun expandClassName(origPkg: String, rawName: String): String {
        return when {
            rawName.startsWith(".") -> origPkg + rawName
            !rawName.contains(".") -> "$origPkg.$rawName"
            else -> rawName
        }
    }

    private fun mutateProcessAndAffinity(el: ResXmlElement, origPkg: String, clonePkg: String) {
        val processAttr = el.searchAttributeByResourceId(ATTR_PROCESS)
        val procVal = processAttr?.getValueAsString()
        if (!procVal.isNullOrBlank() && procVal.startsWith(origPkg)) {
            processAttr.setValueAsString(procVal.replaceFirst(origPkg, clonePkg))
        }

        val affinityAttr = el.searchAttributeByResourceId(ATTR_TASK_AFFINITY)
        val affVal = affinityAttr?.getValueAsString()
        if (!affVal.isNullOrBlank() && affVal.startsWith(origPkg)) {
            affinityAttr.setValueAsString(affVal.replaceFirst(origPkg, clonePkg))
        }
    }

    private fun mutateComponentPermissions(
        el: ResXmlElement,
        origPkg: String,
        cloneSuffix: String,
        permissionMap: Map<String, String>
    ) {
        val permAttrIds = intArrayOf(ATTR_PERMISSION, ATTR_READ_PERMISSION, ATTR_WRITE_PERMISSION)
        for (attrId in permAttrIds) {
            val attr = el.searchAttributeByResourceId(attrId) ?: continue
            val valStr = attr.getValueAsString() ?: continue
            val mapped = permissionMap[valStr]
                ?: if (valStr.startsWith("$origPkg.")) "$valStr.$cloneSuffix" else null
            if (mapped != null) {
                attr.setValueAsString(mapped)
            }
        }
    }

    private fun injectMetaData(appElement: ResXmlElement, key: String, value: String) {
        val metaEl = appElement.newElement(TAG_META_DATA)
        metaEl.getOrCreateAndroidAttribute("name", ATTR_NAME).setValueAsString(key)
        metaEl.getOrCreateAndroidAttribute("value", ATTR_META_VALUE).setValueAsString(value)
    }

    /**
     * Patches the app label directly on the <application> element and on any
     * launcher activities or activity-aliases in the AndroidManifest.xml.
     * This avoids parsing the multi-megabyte resources.arsc table into memory,
     * which prevents OutOfMemory / SIGABRT crashes on large apps (like Brave).
     */
    private fun patchApplicationAndLauncherLabels(appElement: ResXmlElement, customLabel: String) {
        val appLabelAttr = appElement.getOrCreateAndroidAttribute("label", ATTR_LABEL)
        appLabelAttr.setValueAsString(customLabel)

        for (comp in collectComponents(appElement)) {
            if (isLauncherComponent(comp)) {
                val compLabelAttr = comp.searchAttributeByResourceId(ATTR_LABEL)
                if (compLabelAttr != null) {
                    compLabelAttr.setValueAsString(customLabel)
                }
            }
        }
    }

    private fun isLauncherComponent(element: ResXmlElement): Boolean {
        for (intentFilter in CollectionUtil.toList(element.getElements("intent-filter"))) {
            var hasMain = false
            var hasLauncher = false
            for (action in CollectionUtil.toList(intentFilter.getElements("action"))) {
                val name = action.searchAttributeByResourceId(ATTR_NAME)?.getValueAsString()
                if (name == "android.intent.action.MAIN") {
                    hasMain = true
                }
            }
            for (category in CollectionUtil.toList(intentFilter.getElements("category"))) {
                val name = category.searchAttributeByResourceId(ATTR_NAME)?.getValueAsString()
                if (name == "android.intent.category.LAUNCHER") {
                    hasLauncher = true
                }
            }
            if (hasMain && hasLauncher) return true
        }
        return false
    }

    /**
     * Writes the module through a temp file next to [target] and swaps it in,
     * because lazily-loaded InputSources keep readers open on the target file.
     */
    private fun writeApkSafe(apkModule: ApkModule, target: File) {
        val dir = target.parentFile
            ?: throw IOException("Mutated APK path has no parent directory: $target")
        val tmp = File(dir, target.name + ".mut_tmp_" + System.nanoTime())
        try {
            apkModule.writeApk(tmp)
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace mutated file: $target")
            }
            if (!tmp.renameTo(target)) {
                try {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                } catch (e: Exception) {
                    throw IOException("Unable to move or copy mutated APK into place: $target", e)
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
