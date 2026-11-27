package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.json.Json
import kotlin.test.*

class CourseGroupsTest {
    private val lecture = Course("lecture", "Algorithmen und Datenstrukturen", semesterId = "winter")
    private fun grouped(vararg courses: Course, preferences: AccountPreferences = AccountPreferences()) =
        courseGroups(courses.toList(), preferences).filter { it.courses.size > 1 }

    @Test fun pairsGermanAndEnglishRoleMarkersWithoutRequiringTheSameTeacher() {
        for (title in listOf("Übung zu Algorithmen und Datenstrukturen", "Übungen zur Vorlesung Algorithmen und Datenstrukturen",
            "Algorithmen und Datenstrukturen (Übung)", "Algorithmen und Datenstrukturen – Tutorium Gruppe A")) {
            val exercise = lecture.copy(id = "exercise", title = title, teacher = "A different tutor")
            val group = grouped(lecture, exercise).single()
            assertEquals(lecture.title, group.title)
            assertEquals(listOf("lecture", "exercise"), group.courses.map { it.id }, title)
            assertTrue(group.automatic)
        }
        val english = lecture.copy(title = "Lecture: Data Structures")
        assertEquals(1, grouped(english, lecture.copy(id = "exercise", title = "Exercises in Data Structures")).size)
    }

    @Test fun includedSourceTypesCanRelateCoursesWithIdenticalTitles() {
        val a = lecture.copy(typeName = "Vorlesung")
        val b = lecture.copy(id = "exercise", typeName = "Übung")
        assertEquals(1, grouped(a, b).size)
        assertTrue(grouped(a, b.copy(typeName = "Seminar")).isEmpty())
    }

    @Test fun dedicatedSubtitleRolesWorkWithoutTreatingAnExamAsTheLecture() {
        val a = lecture.copy(subtitle = "Vorlesung")
        val b = lecture.copy(id = "exercise", subtitle = "Übung")
        assertEquals(1, grouped(a, b).size)
        assertTrue(grouped(a.copy(subtitle = "Klausur"), b).isEmpty())
    }

    @Test fun numberedExerciseGroupsKeepTheirIdentityWithoutChangingSubjectNumbers() {
        val a = lecture.copy(title = "Analysis II")
        val one = a.copy(id = "one", title = "Übung zu Analysis II Gruppe 1")
        val two = a.copy(id = "two", title = "Übung zu Analysis II (Gruppe 2)")
        assertEquals(listOf("lecture", "two", "one").toSet(), grouped(a, one, two).single().courses.map { it.id }.toSet())
        assertTrue(grouped(a.copy(title = "Analysis I"), one, two).isEmpty())
    }

    @Test fun preservesSubjectNumbersAndDoesNotUseTeacherOrVagueTitleSimilarity() {
        val a = lecture.copy(title = "Analysis I", teacher = "Professor Same")
        assertTrue(grouped(a, a.copy(id = "exercise", title = "Übung zu Analysis II")).isEmpty())
        assertTrue(grouped(a, a.copy(id = "exercise", title = "Übung zu Numerische Analysis I")).isEmpty())
        assertEquals(1, grouped(a, a.copy(id = "exercise", title = "Analysis I (Übung)")).size)
    }

    @Test fun neverCombinesUnknownOrDifferentSemestersInTheAllSemesterView() {
        val exercise = lecture.copy(id = "exercise", title = "Übung zu ${lecture.title}")
        assertTrue(grouped(lecture, exercise.copy(semesterId = "summer")).isEmpty())
        assertTrue(grouped(lecture.copy(semesterId = ""), exercise.copy(semesterId = "")).isEmpty())
        // The selected API collection can supply the semester for an older cached course record.
        assertEquals(1, grouped(lecture.copy(semesterId = ""), exercise.copy(semesterId = ""),
            preferences = AccountPreferences(semesterId = "winter")).size)
    }

    @Test fun ambiguityAndExerciseOnlyBucketsStaySeparate() {
        val exercise = lecture.copy(id = "exercise", title = "Übung zu ${lecture.title}")
        assertTrue(grouped(lecture, lecture.copy(id = "other-lecture"), exercise).isEmpty())
        assertTrue(grouped(exercise, exercise.copy(id = "tutorial", title = "${lecture.title} (Tutorium)")).isEmpty())
    }

    @Test fun severalExerciseClassesStayDistinctWithinTheGroup() {
        val exercise = lecture.copy(id = "exercise", title = "Übung zu ${lecture.title}")
        val tutorial = lecture.copy(id = "tutorial", title = "${lecture.title} (Tutorium A)")
        val groups = courseGroups(listOf(lecture, exercise, tutorial, lecture), AccountPreferences())
        assertEquals(listOf("lecture", "exercise", "tutorial"), groups.single().courses.map { it.id })
    }

    @Test fun manualGroupsOverrideMatchingAndDoNotDuplicateCourses() {
        val exercise = lecture.copy(id = "exercise", title = "Übung zu ${lecture.title}")
        val other = lecture.copy(id = "other", title = "Lineare Algebra")
        val prefs = AccountPreferences().saveCourseGroup("custom", "Foundations", setOf("lecture", "other"))
        val groups = courseGroups(listOf(lecture, exercise, other), prefs)
        assertEquals(setOf("lecture", "other"), groups.first { it.id == "custom" }.courses.map { it.id }.toSet())
        assertEquals(3, groups.sumOf { it.courses.size })
        assertEquals(1, groups.count { it.courses.size > 1 })
    }

    @Test fun editingAnAutomaticGroupKeepsRemovedMembersSeparateAfterRefresh() {
        val exercise = lecture.copy(id = "exercise", title = "Übung zu ${lecture.title}")
        val tutorial = lecture.copy(id = "tutorial", title = "${lecture.title} (Tutorium)")
        val courses = listOf(lecture, exercise, tutorial)
        val original = courseGroups(courses, AccountPreferences()).single()
        val prefs = AccountPreferences().saveCourseGroup("custom", "Algorithms", setOf("lecture", "exercise"), original.courses.map { it.id }.toSet())
        assertEquals(setOf("tutorial"), prefs.separateCourseIds)
        assertEquals(listOf(1, 2), courseGroups(courses, prefs).map { it.courses.size }.sorted())
        val separated = prefs.separateCourses(setOf("lecture", "exercise"))
        assertTrue(separated.courseGroups.isEmpty())
        assertEquals(3, courseGroups(courses, separated).size)
    }

    @Test fun movingBetweenManualGroupsDoesNotSilentlyRegroupLeftoverMembers() {
        val original = AccountPreferences().saveCourseGroup("one", "One", setOf("a", "b"))
        val moved = original.saveCourseGroup("two", "Two", setOf("b", "c"))
        assertEquals(listOf("two"), moved.courseGroups.map { it.id })
        assertEquals(setOf("a"), moved.separateCourseIds)
        assertEquals(setOf("b", "c"), moved.courseGroups.single().courseIds)
    }

    @Test fun pinsOrderGroupsWithoutChangingMembershipAndMissingCoursesKeepTheirSavedMembership() {
        val exercise = lecture.copy(id = "exercise", title = "Übung zu ${lecture.title}")
        val other = lecture.copy(id = "other", title = "AAA unrelated")
        val pinned = AccountPreferences(pinnedCourses = setOf("exercise"))
        assertTrue(courseGroups(listOf(other, lecture, exercise), pinned).first().automatic)
        val manual = pinned.saveCourseGroup("saved", "Algorithms", setOf("lecture", "exercise"))
        assertEquals(listOf("lecture"), courseGroups(listOf(lecture), manual).single().courses.map { it.id })
        assertEquals(setOf("lecture", "exercise"), manual.courseGroups.single().courseIds)
    }

    @Test fun legacyPreferencesAndCoursesRemainDecodableAndCorrectionsRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = json.decodeFromString<AccountPreferences>("""{"pinnedCourses":["lecture"],"semesterId":"winter"}""")
        assertTrue(legacy.courseGroups.isEmpty()); assertTrue(legacy.separateCourseIds.isEmpty())
        val updated = legacy.saveCourseGroup("saved", "Algorithms", setOf("lecture", "exercise"))
        assertEquals(updated, json.decodeFromString<AccountPreferences>(json.encodeToString(updated)))
        assertEquals("", json.decodeFromString<Course>("""{"id":"lecture","title":"Algorithms"}""").typeName)
    }
}
