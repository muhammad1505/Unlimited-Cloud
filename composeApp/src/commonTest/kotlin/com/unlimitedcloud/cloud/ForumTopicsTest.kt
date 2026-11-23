package com.unlimitedcloud.cloud

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForumTopicsTest {

    @Test
    fun blankFolderNamesHaveNoTopic() {
        assertNull(forumTopicName(""))
        assertNull(forumTopicName("   "))
        assertNull(forumTopicName("\n\n"))
    }

    @Test
    fun folderNamesAreTrimmedAndKeptOnOneLine() {
        assertEquals("Foto 2024", forumTopicName("  Foto\n2024  "))
    }

    @Test
    fun longFolderNamesAreCutToTheTdlibLimit() {
        val name = forumTopicName("a".repeat(300))

        assertEquals(128, name?.length)
        assertTrue(name!!.isNotEmpty())
    }

    @Test
    fun topicJsonTargetsTheForumTopicAndFallsBackToNull() {
        assertEquals(JsonNull, forumTopicJson(null))

        val topic = forumTopicJson(42).jsonObject
        assertEquals("messageTopicForum", topic["@type"]?.jsonPrimitive?.content)
        assertEquals(42, topic["forum_topic_id"]?.jsonPrimitive?.int)
    }
}
