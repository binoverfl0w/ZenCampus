package io.github.binoverfl0w.zencampus.data.digicampus

import io.github.binoverfl0w.zencampus.model.*
import kotlinx.serialization.json.*

/** Maps the learner's task copy; never infers a deadline from titles or content progress. */
internal object CourseworkMapper {
    fun isSolvable(resource: JsonObject): Boolean {
        requireResource(resource, "courseware-tasks")
        return (resource.attrs()["can-solve"] as? JsonPrimitive)?.booleanOrNull != false
    }

    fun task(resource: JsonObject, included: List<JsonObject>, courseId: String): CourseworkTask {
        requireResource(resource, "courseware-tasks")
        val group = related(resource, "task-group", "courseware-task-groups", included) ?: invalid()
        if (relation(group, "course").singleOrNull()?.let { it.text("type") == "courses" && it.text("id") == courseId } != true) invalid()
        val attrs = resource.attrs()
        val title = JsonApiMapper.plainText(group.attrs().text("title")).takeIf { it.isNotBlank() } ?: invalid()
        val submitted = (attrs["submitted"] as? JsonPrimitive)?.booleanOrNull ?: invalid()
        val renewal = attrs.text("renewal")
        // submission-date is a computed deadline, not a timestamp of the student's submission.
        val published = date(attrs, "submission-date") ?: date(group.attrs(), "end-date")
        val due = if (renewal == "granted") date(attrs, "renewal-date") else published
        val progress = (attrs["progress"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..100.0 }
        return CourseworkTask(resource.text("id"), courseId, title, due, submitted, progress, renewal, published, (attrs["can-solve"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    fun detail(resource: JsonObject, included: List<JsonObject>, courseId: String): CourseworkDetail {
        val task = task(resource, included, courseId)
        val feedback = related(resource, "task-feedback", "courseware-task-feedback", included)
            ?.attrs()?.text("content")?.let(JsonApiMapper::plainText).orEmpty()
        val element = related(resource, "structural-element", "courseware-structural-elements", included)
            ?: return CourseworkDetail(task, hasInteractiveContent = true, feedback = feedback)
        if ((element.attrs()["can-visit"] as? JsonPrimitive)?.booleanOrNull == false) return CourseworkDetail(task, hasInteractiveContent = true, feedback = feedback)
        val sections = mutableListOf<CourseworkSection>()
        var interactive = relation(element, "children").isNotEmpty()
        for (containerRef in relation(element, "containers").sortedBy { position(it, included) }) {
            val container = find(containerRef, included)
            if (containerRef.text("type") != "courseware-containers") invalid()
            if (container == null) { interactive = true; continue }
            if ((container.attrs()["visible"] as? JsonPrimitive)?.booleanOrNull == false) continue
            for (blockRef in relation(container, "blocks").sortedBy { position(it, included) }) {
                val block = find(blockRef, included)
                if (blockRef.text("type") != "courseware-blocks") invalid()
                if (block == null) { interactive = true; continue }
                val attrs = block.attrs()
                if ((attrs["visible"] as? JsonPrimitive)?.booleanOrNull == false) continue
                val payload = attrs["payload"] as? JsonObject
                val html = if (attrs.text("block-type") == "text") payload?.text("text").orEmpty() else ""
                val text = JsonApiMapper.plainText(html)
                if (Regex("<(img|table|video|audio)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) interactive = true
                if (text.isNotBlank()) sections += CourseworkSection(text = text) else interactive = true
            }
        }
        return CourseworkDetail(task, sections, interactive, feedback)
    }

    private fun position(ref: JsonObject, included: List<JsonObject>): Int =
        ((find(ref, included)?.get("attributes") as? JsonObject)?.get("position") as? JsonPrimitive)?.intOrNull ?: 0

    private fun date(attrs: JsonObject, key: String): Long? {
        val raw = attrs.text(key)
        if (raw.isBlank()) return null
        return JsonApiMapper.timestamp(raw).takeIf { it > 0 }
    }
    private fun related(resource: JsonObject, name: String, type: String, included: List<JsonObject>): JsonObject? {
        val ref = relation(resource, name).singleOrNull() ?: return null
        if (ref.text("type") != type) invalid()
        return find(ref, included)
    }
    private fun find(ref: JsonObject, included: List<JsonObject>): JsonObject? =
        included.firstOrNull { it.text("id") == ref.text("id") && it.text("type") == ref.text("type") }
    private fun relation(resource: JsonObject, name: String): List<JsonObject> =
        when (val data = ((resource["relationships"] as? JsonObject)?.get(name) as? JsonObject)?.get("data")) {
            is JsonObject -> listOf(data)
            is JsonArray -> data.map { it as? JsonObject ?: invalid() }
            else -> emptyList()
        }
    private fun JsonObject.attrs(): JsonObject = this["attributes"] as? JsonObject ?: invalid()
    private fun requireResource(resource: JsonObject, type: String) {
        if (resource.text("type") != type || resource.text("id").isBlank()) invalid()
    }
    private fun invalid(): Nothing = throw CampusException(FailureKind.INVALID_RESPONSE)
}
