package com.research.sensormax

import java.io.InputStream
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Safe reader for the legacy declarative experiment XML profile format.
 *
 * Imports structure and mappings only. It never executes foreign analysis,
 * networking, Bluetooth, camera, audio, or output modules.
 */
object SensorMaxExperimentFile {
    private const val SUPPORTED_MAJOR = 1
    private const val SUPPORTED_MINOR = 20
    private const val MAX_INPUT_BYTES = 16L * 1024L * 1024L

    data class Definition(
        val sourceName: String,
        val sha256: String,
        val sourceFormat: String,
        val formatVersion: String,
        val title: String,
        val category: String,
        val description: String,
        val containers: List<DataContainer>,
        val sensors: List<SensorBinding>,
        val analysisModules: List<AnalysisModule>,
        val views: List<ViewDefinition>,
        val exportSets: List<ExportSet>,
        val analysisSleepSeconds: Double?,
        val analysisOnUserInput: Boolean,
        val requireFillBuffer: String?,
        val requireFillThreshold: Int?,
        val requireFillDynamicBuffer: String?,
        val timedRun: Boolean,
        val networkBlocks: Int,
        val outputBlocks: Int,
        val unsupportedFeatures: List<String>
    ) {
        val supported: Boolean
            get() = unsupportedFeatures.isEmpty()

        val requiredAndroidSensorTypes: List<Int>
            get() = sensors.mapNotNull { it.androidSensorType }.distinct()

        val requestedCaptureRateHz: Double?
            get() = sensors.mapNotNull { it.frequencyHz }
                .filter { it > 0.0 }
                .maxOrNull()
    }

    data class DataContainer(
        val name: String,
        val size: Int,
        val clearGroup: String?,
        val isStatic: Boolean,
        val initialValueCount: Int
    )

    data class SensorBinding(
        val type: String,
        val outputs: Map<String, String>,
        val frequencyHz: Double?,
        val preferUncalibrated: Boolean,
        val androidSensorType: Int?
    )

    data class AnalysisModule(
        val type: String,
        val requireFillBuffer: String?,
        val requireFillThreshold: Int?,
        val inputs: List<String>,
        val outputs: List<String>
    )

    data class ViewDefinition(
        val label: String,
        val elementTypes: List<String>
    )

    data class ExportSet(
        val name: String,
        val dataNames: List<String>
    )

    fun parse(
        input: InputStream,
        sourceName: String = "unknown.legacy.xml"
    ): Definition {
        val bytes = input.use { stream -> readBounded(stream) }
        if (bytes.isEmpty()) error("The experiment file is empty")

        val document = secureFactory().newDocumentBuilder().parse(bytes.inputStream())
        val root = document.documentElement ?: error("Missing XML root element")
        val rootName = root.localName ?: root.nodeName
        val isLegacy = rootName == "phyphox"
        val isSensorMax = rootName == "sensormax-experiment"
        require(isLegacy || isSensorMax) {
            "Root element must be <phyphox> or <sensormax-experiment>, got <${rootName}>"
        }

        val version = if (isLegacy) {
            root.getAttribute("version").trim()
        } else {
            root.getAttribute("source-version").trim()
                .ifBlank { root.getAttribute("schema-version").trim() }
        }
        require(version.isNotEmpty()) { "Missing experiment format version" }
        val versionParts = parseVersion(version)

        val unsupported = mutableListOf<String>()
        if (!isSupportedVersion(versionParts.first, versionParts.second)) {
            unsupported += "format-version:$version"
        }

        val containers = child(root, "data-containers")?.let(::parseContainers).orEmpty()
        val inputBlock = if (isLegacy) child(root, "input") else child(root, "sensors")
        val sensors = inputBlock?.let(::parseSensors).orEmpty()
        if (inputBlock != null) {
            children(inputBlock)
                .asSequence()
                .filter { it.nodeName != "sensor" && it.localName != "sensor" }
                .map { "input:${it.nodeName}" }
                .forEach { unsupported += it }
        }

        val analysisBlock = child(root, "analysis")
        val analysis = if (isLegacy) {
            analysisBlock?.let { parseAnalysis(it, unsupported) }.orEmpty()
        } else {
            analysisBlock?.let { parseSensorMaxAnalysis(it, unsupported) }.orEmpty()
        }
        val views = child(root, "views")?.let(::parseViews).orEmpty()
        val exports = if (isLegacy) {
            child(root, "export")?.let(::parseExports).orEmpty()
        } else {
            child(root, "exports")?.let(::parseExports).orEmpty()
        }

        val networkCount = if (isLegacy) children(root, "network").size else 0
        val outputCount = if (isLegacy) children(root, "output").size else 0
        if (networkCount > 0) unsupported += "network"
        if (outputCount > 0) unsupported += "output-modules"

        if (isSensorMax) {
            descendants(root, "feature").map { it.textContent.trim() }
                .filter { it.isNotEmpty() }.forEach { unsupported += it }
        } else {
            val knownTopLevel = setOf(
                "phyphox", "title", "category", "description", "icon", "link",
                "translations", "data-containers", "events", "views", "input",
                "network", "analysis", "output", "export"
            )
            children(root).asSequence()
                .map { it.nodeName }
                .filter { it !in knownTopLevel }
                .forEach { unsupported += "top-level:$it" }
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        return Definition(
            sourceName = sourceName,
            sha256 = digest,
            sourceFormat = if (isLegacy) "legacy-experiment-xml" else "sensormax-experiment",
            formatVersion = version,
            title = if (isLegacy) directText(root, "title").orEmpty().trim() else directText(child(root, "metadata") ?: root, "title").orEmpty().trim(),
            category = if (isLegacy) directText(root, "category").orEmpty().trim() else directText(child(root, "metadata") ?: root, "category").orEmpty().trim(),
            description = if (isLegacy) directText(root, "description").orEmpty().trim() else directText(child(root, "metadata") ?: root, "description").orEmpty().trim(),
            containers = containers,
            sensors = sensors,
            analysisModules = analysis,
            views = views,
            exportSets = exports,
            analysisSleepSeconds = analysisBlock?.getAttribute("sleep")?.toDoubleOrNull(),
            analysisOnUserInput = analysisBlock?.getAttribute("onUserInput")
                ?.equals("true", ignoreCase = true) == true,
            requireFillBuffer = analysisBlock?.getAttribute("requireFill")?.takeIf { it.isNotBlank() },
            requireFillThreshold = analysisBlock?.getAttribute("requireFillThreshold")?.toIntOrNull(),
            requireFillDynamicBuffer = analysisBlock?.getAttribute("requireFillDynamic")?.takeIf { it.isNotBlank() },
            timedRun = analysisBlock?.getAttribute("timedRun")
                ?.equals("true", ignoreCase = true) == true,
            networkBlocks = networkCount,
            outputBlocks = outputCount,
            unsupportedFeatures = unsupported.distinct()
        )
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            require(total <= MAX_INPUT_BYTES) {
                "The experiment file exceeds the ${MAX_INPUT_BYTES / (1024 * 1024)} MB safety limit"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseVersion(version: String): Pair<Int, Int> {
        val parts = version.split('.', limit = 2)
        require(parts.size == 2) { "Invalid experiment format version: $version" }
        val major = parts[0].toIntOrNull()
            ?: error("Invalid experiment format version: $version")
        val minor = parts[1].toIntOrNull()
            ?: error("Invalid experiment format version: $version")
        require(major >= 0 && minor >= 0) { "Invalid experiment format version: $version" }
        return major to minor
    }

    private fun isSupportedVersion(major: Int, minor: Int): Boolean =
        major < SUPPORTED_MAJOR ||
            (major == SUPPORTED_MAJOR && minor <= SUPPORTED_MINOR)

    private fun parseContainers(parent: Element): List<DataContainer> =
        children(parent, "container").mapNotNull { element ->
            val name = element.textContent.trim()
            if (name.isEmpty()) return@mapNotNull null
            val size = element.getAttribute("size").toIntOrNull() ?: 1
            val initCount = element.getAttribute("init")
                .takeIf { it.isNotBlank() }
                ?.split(',', ignoreCase = false, limit = Int.MAX_VALUE)
                ?.size ?: 0
            DataContainer(
                name = name,
                size = size,
                clearGroup = element.getAttribute("clearGroup").takeIf { it.isNotBlank() },
                isStatic = element.getAttribute("static").equals("true", ignoreCase = true),
                initialValueCount = initCount
            )
        }

    private fun parseSensors(parent: Element): List<SensorBinding> =
        children(parent, "sensor").map { sensor ->
            val type = sensor.getAttribute("type").trim()
            val outputs = children(sensor, "output").associate { output ->
                output.getAttribute("component").trim() to output.textContent.trim()
            }.filterKeys { it.isNotEmpty() }.filterValues { it.isNotEmpty() }
            SensorBinding(
                type = type,
                outputs = outputs,
                frequencyHz = sensor.getAttribute("rate").toDoubleOrNull(),
                preferUncalibrated = sensor.getAttribute("preferUncalibrated")
                    .equals("true", ignoreCase = true),
                androidSensorType = androidSensorTypeFor(type)
            )
        }

    private fun parseAnalysis(parent: Element, unsupported: MutableList<String>): List<AnalysisModule> =
        children(parent).mapNotNull { module ->
            val type = module.nodeName.trim()
            if (type.isEmpty()) return@mapNotNull null
            val inputs = descendants(module, "input")
                .map { it.textContent.trim() }
                .filter { it.isNotEmpty() }
            val outputs = descendants(module, "output")
                .map { it.textContent.trim() }
                .filter { it.isNotEmpty() }
            if (type !in SUPPORTED_ANALYSIS_TYPES) unsupported += "analysis:$type"
            AnalysisModule(
                type = type,
                requireFillBuffer = module.getAttribute("requireFill").takeIf { it.isNotBlank() },
                requireFillThreshold = module.getAttribute("requireFillThreshold").toIntOrNull(),
                inputs = inputs,
                outputs = outputs
            )
        }

    private fun parseSensorMaxAnalysis(parent: Element, unsupported: MutableList<String>): List<AnalysisModule> =
        children(parent, "operation").map { operation ->
            val type = operation.getAttribute("type").ifBlank { "operation" }.trim()
            val inputs = children(child(operation, "inputs") ?: operation, "input")
                .map { it.textContent.trim() }.filter { it.isNotEmpty() }
            val outputs = children(child(operation, "outputs") ?: operation, "output")
                .map { it.textContent.trim() }.filter { it.isNotEmpty() }
            unsupported += "analysis-op:$type"
            AnalysisModule(
                type = type,
                requireFillBuffer = operation.getAttribute("requireFill").takeIf { it.isNotBlank() },
                requireFillThreshold = operation.getAttribute("requireFillThreshold").toIntOrNull(),
                inputs = inputs,
                outputs = outputs
            )
        }

    private fun parseViews(parent: Element): List<ViewDefinition> =
        children(parent, "view").map { view ->
            ViewDefinition(
                label = view.getAttribute("label").trim(),
                elementTypes = children(view).map { it.nodeName }.distinct()
            )
        }

    private fun parseExports(parent: Element): List<ExportSet> =
        children(parent, "set").map { set ->
            ExportSet(
                name = set.getAttribute("name").trim(),
                dataNames = children(set, "data").map { it.textContent.trim() }
                    .filter { it.isNotEmpty() }
            )
        }

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }

    private fun child(parent: Element, name: String): Element? =
        children(parent, name).firstOrNull()

    private fun children(parent: Element, name: String? = null): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            if (name == null || element.localName == name || element.nodeName == name) {
                result += element
            }
        }
        return result
    }

    private fun descendants(parent: Element, name: String): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(node: Node) {
            val children = node.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val element = child as Element
                    if (element.localName == name || element.nodeName == name) result += element
                    visit(element)
                }
            }
        }
        visit(parent)
        return result
    }

    private fun directText(parent: Element, name: String): String? = child(parent, name)?.textContent

    private fun androidSensorTypeFor(type: String): Int? = when (type.lowercase()) {
        "accelerometer" -> android.hardware.Sensor.TYPE_ACCELEROMETER
        "linear_accelerometer", "linear_acceleration" -> android.hardware.Sensor.TYPE_LINEAR_ACCELERATION
        "gravity" -> android.hardware.Sensor.TYPE_GRAVITY
        "gyroscope" -> android.hardware.Sensor.TYPE_GYROSCOPE
        "magnetic_field" -> android.hardware.Sensor.TYPE_MAGNETIC_FIELD
        "pressure" -> android.hardware.Sensor.TYPE_PRESSURE
        "light" -> android.hardware.Sensor.TYPE_LIGHT
        "proximity" -> android.hardware.Sensor.TYPE_PROXIMITY
        "temperature" -> android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE
        "relative_humidity", "humidity" -> android.hardware.Sensor.TYPE_RELATIVE_HUMIDITY
        else -> null
    }

    private val SUPPORTED_ANALYSIS_TYPES = setOf(
        "abs", "add", "subtract", "multiply", "divide", "power", "log", "sqrt", "sin", "cos"
    )
}
