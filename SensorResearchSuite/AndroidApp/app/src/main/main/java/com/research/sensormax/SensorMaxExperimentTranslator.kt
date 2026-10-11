package com.research.sensormax

/** Converts a parsed experiment definition into SensorMax-owned XML and Kotlin descriptors. */
object SensorMaxExperimentTranslator {
    data class Translation(
        val xmlFileName: String,
        val xml: String,
        val kotlinFileName: String,
        val kotlin: String
    )

    fun translate(definition: SensorMaxExperimentFile.Definition): Translation {
        val base = sanitizeFileStem(definition.sourceName)
        return Translation(
            xmlFileName = "$base.sensormax.xml",
            xml = toXml(definition),
            kotlinFileName = "${toClassName(base)}Experiment.kt",
            kotlin = toKotlin(definition, toClassName(base))
        )
    }

    fun toXml(definition: SensorMaxExperimentFile.Definition): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<sensormax-experiment schema-version=\"1.0\" source-format=\"")
        append(xmlEscape(definition.sourceFormat))
        append("\" source-version=\"")
        append(xmlEscape(definition.formatVersion))
        append("\" source-file=\"")
        append(xmlEscape(sanitizeFileStem(definition.sourceName) + ".legacy.xml"))
        append("\" source-sha256=\"")
        append(xmlEscape(definition.sha256))
        append("\">\n")
        append("  <metadata>\n")
        append("    <title>").append(xmlEscape(adaptText(definition.title))).append("</title>\n")
        append("    <category>").append(xmlEscape(adaptText(definition.category))).append("</category>\n")
        append("    <description>").append(xmlEscape(adaptText(definition.description))).append("</description>\n")
        append("  </metadata>\n")

        append("  <data-containers>\n")
        definition.containers.forEach { container ->
            append("    <container size=\"").append(container.size).append("\"")
            container.clearGroup?.let { append(" clearGroup=\"").append(xmlEscape(it)).append("\"") }
            if (container.isStatic) append(" static=\"true\"")
            append(">").append(xmlEscape(container.name)).append("</container>\n")
        }
        append("  </data-containers>\n")

        append("  <sensors>\n")
        definition.sensors.forEach { sensor ->
            append("    <sensor type=\"").append(xmlEscape(sensor.type)).append("\"")
            sensor.frequencyHz?.let { append(" rate=\"").append(it).append("\"") }
            if (sensor.preferUncalibrated) append(" preferUncalibrated=\"true\"")
            append(">\n")
            sensor.outputs.forEach { (component, name) ->
                append("      <output component=\"").append(xmlEscape(component)).append("\">")
                    .append(xmlEscape(name)).append("</output>\n")
            }
            append("    </sensor>\n")
        }
        append("  </sensors>\n")

        append("  <analysis")
        definition.analysisSleepSeconds?.let { append(" sleep=\"").append(it).append("\"") }
        if (definition.analysisOnUserInput) append(" onUserInput=\"true\"")
        definition.requireFillBuffer?.let { append(" requireFill=\"").append(xmlEscape(it)).append("\"") }
        definition.requireFillThreshold?.let { append(" requireFillThreshold=\"").append(it).append("\"") }
        definition.requireFillDynamicBuffer?.let { append(" requireFillDynamic=\"").append(xmlEscape(it)).append("\"") }
        if (definition.timedRun) append(" timedRun=\"true\"")
        if (definition.analysisModules.isEmpty()) {
            append(" />\n")
        } else {
            append(">\n")
            definition.analysisModules.forEach { module ->
                append("    <operation type=\"").append(xmlEscape(module.type)).append("\">\n")
                if (module.inputs.isNotEmpty()) {
                    append("      <inputs>\n")
                    module.inputs.forEach { append("        <input>").append(xmlEscape(it)).append("</input>\n") }
                    append("      </inputs>\n")
                }
                if (module.outputs.isNotEmpty()) {
                    append("      <outputs>\n")
                    module.outputs.forEach { append("        <output>").append(xmlEscape(it)).append("</output>\n") }
                    append("      </outputs>\n")
                }
                append("    </operation>\n")
            }
            append("  </analysis>\n")
        }

        append("  <views>\n")
        definition.views.forEach { view ->
            append("    <view label=\"").append(xmlEscape(view.label)).append("\">\n")
            view.elementTypes.forEach { append("      <element type=\"").append(xmlEscape(it)).append("\" />\n") }
            append("    </view>\n")
        }
        append("  </views>\n")

        append("  <exports>\n")
        definition.exportSets.forEach { export ->
            append("    <set name=\"").append(xmlEscape(export.name)).append("\">\n")
            export.dataNames.forEach { append("      <data>").append(xmlEscape(it)).append("</data>\n") }
            append("    </set>\n")
        }
        append("  </exports>\n")

        append("  <translation-status>\n")
        append("    <mode>descriptor-only</mode>\n")
        append("    <execution>Only SensorMax-native runtime modules are eligible for execution; foreign analysis/network/device modules remain declarative metadata.</execution>\n")
        append("  </translation-status>\n")
        append("  <source-features>\n")
        definition.unsupportedFeatures.forEach { append("    <feature>").append(xmlEscape(it)).append("</feature>\n") }
        append("  </source-features>\n")
        append("</sensormax-experiment>\n")
    }

    fun toKotlin(definition: SensorMaxExperimentFile.Definition, classBase: String): String = buildString {
        append("package com.research.sensormax.generated\n\n")
        append("import com.research.sensormax.SensorMaxAnalysisSpec\n")
        append("import com.research.sensormax.SensorMaxContainerSpec\n")
        append("import com.research.sensormax.SensorMaxExperimentSpec\n")
        append("import com.research.sensormax.SensorMaxExportSpec\n")
        append("import com.research.sensormax.SensorMaxSensorSpec\n")
        append("import com.research.sensormax.SensorMaxViewSpec\n\n")
        append("/** Generated SensorMax descriptor; analysis is declarative, not executable foreign code. */\n")
        append("object ${toClassName(classBase)}Experiment : SensorMaxExperimentSpec {\n")
        append("    override val sourceName = ").append(kotlinString(sanitizeFileStem(definition.sourceName) + ".legacy.xml")).append("\n")
        append("    override val sourceSha256 = ").append(kotlinString(definition.sha256)).append("\n")
        append("    override val sourceVersion = ").append(kotlinString(definition.formatVersion)).append("\n")
        append("    override val title = ").append(kotlinString(adaptText(definition.title))).append("\n")
        append("    override val category = ").append(kotlinString(adaptText(definition.category))).append("\n")
        append("    override val description = ").append(kotlinString(adaptText(definition.description))).append("\n")
        append("    override val sensors = listOf(\n")
        definition.sensors.forEach { sensor ->
            append("        SensorMaxSensorSpec(type = ").append(kotlinString(sensor.type))
            append(", rateHz = ").append(sensor.frequencyHz?.toString() ?: "null")
            append(", preferUncalibrated = ").append(sensor.preferUncalibrated)
            append(", outputs = mapOf(")
            sensor.outputs.entries.forEachIndexed { index, entry ->
                if (index > 0) append(", ")
                append(kotlinString(entry.key)).append(" to ").append(kotlinString(entry.value))
            }
            append(")),\n")
        }
        append("    )\n")
        append("    override val containers = listOf(\n")
        definition.containers.forEach { container ->
            append("        SensorMaxContainerSpec(name = ").append(kotlinString(container.name))
            append(", size = ").append(container.size)
            append(", clearGroup = ").append(container.clearGroup?.let(::kotlinString) ?: "null")
            append(", isStatic = ").append(container.isStatic).append("),\n")
        }
        append("    )\n")
        append("    override val analysis = listOf(\n")
        definition.analysisModules.forEach { module ->
            append("        SensorMaxAnalysisSpec(type = ").append(kotlinString(module.type))
            append(", inputs = listOf(")
            module.inputs.forEachIndexed { index, value -> if (index > 0) append(", "); append(kotlinString(value)) }
            append("), outputs = listOf(")
            module.outputs.forEachIndexed { index, value -> if (index > 0) append(", "); append(kotlinString(value)) }
            append(")),\n")
        }
        append("    )\n")
        append("    override val views = listOf(\n")
        definition.views.forEach { view ->
            append("        SensorMaxViewSpec(label = ").append(kotlinString(view.label)).append(", elementTypes = listOf(")
            view.elementTypes.forEachIndexed { index, value -> if (index > 0) append(", "); append(kotlinString(value)) }
            append(")),\n")
        }
        append("    )\n")
        append("    override val exports = listOf(\n")
        definition.exportSets.forEach { export ->
            append("        SensorMaxExportSpec(name = ").append(kotlinString(export.name)).append(", data = mapOf(")
            export.dataNames.forEachIndexed { index, value ->
                if (index > 0) append(", ")
                append(kotlinString(value)).append(" to ").append(kotlinString(value))
            }
            append(")),\n")
        }
        append("    )\n")
        append("}\n")
    }

    private fun xmlEscape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun kotlinString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun adaptText(value: String): String =
        value.replace(Regex("phyphox", RegexOption.IGNORE_CASE), "SensorMax")

    private fun sanitizeFileStem(value: String): String =
        value.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "experiment" }

    private fun toClassName(value: String): String =
        value.split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "Experiment" }
            .let { if (it.first().isDigit()) "E$it" else it }
}
