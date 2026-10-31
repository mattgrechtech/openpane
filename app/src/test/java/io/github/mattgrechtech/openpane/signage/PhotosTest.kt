package io.github.mattgrechtech.openpane.signage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhotosTest {

    @Test
    fun parsesFoldersAndPictures() {
        val (folders, pictures) = Photos.parse(
            """
            /media/local/photos/
            media-source://immich/album/abc
            /local/frame/1.jpg
            https://example.com/2.png
            """.trimIndent()
        )
        assertEquals(listOf("media-source://media_source/local/photos", "media-source://immich/album/abc"), folders)
        assertEquals(listOf("/local/frame/1.jpg", "https://example.com/2.png"), pictures.map { it.path })
        assertNull(Photos.problem("/media/local/photos"))
        assertNotNull(Photos.problem("my photos"))
    }

    @Test
    fun localFilesAreServedUnderMedia() {
        assertEquals("/media/local/Holiday%202024/a%20b.jpg", Photos.localPath("media-source://media_source/local/Holiday 2024/a b.jpg"))
        assertNull(Photos.localPath("media-source://immich/asset/1"))
    }

    @Test
    fun readsBrowseResults() {
        val result = JSONObject(
            """{"children": [
              {"media_content_id": "media-source://media_source/local/photos/2024", "can_expand": true, "media_class": "directory"},
              {"media_content_id": "media-source://media_source/local/photos/a.jpg", "can_expand": false, "media_class": "image"},
              {"media_content_id": "media-source://media_source/local/photos/b.heic", "can_expand": false, "media_content_type": "image/heic"},
              {"media_content_id": "media-source://media_source/local/photos/c.mp4", "can_expand": false, "media_class": "video"}
            ]}"""
        )
        val (folders, images) = Photos.children(result)
        assertEquals(listOf("media-source://media_source/local/photos/2024"), folders)
        assertEquals(2, images.size)
    }

    @Test
    fun cacheNamesStayInsideTheFolder() {
        val name = MediaCache.name("../../etc/passwd")
        assertEquals(36, name.length)
        assertNotEquals(MediaCache.name("/local/a.jpg"), MediaCache.name("/local/b.jpg"))
        assertEquals(".video", MediaCache.name("/local/a.mp4").takeLast(6))
    }
}
