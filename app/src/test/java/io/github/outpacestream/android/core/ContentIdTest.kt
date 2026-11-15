package io.github.outpacestream.android.core

import io.github.outpacestream.android.core.ContentId.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentIdTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"
    private val other = "f".repeat(40)

    @Test fun contentIdLinkIsAcestreamUrl() = assertEquals("acestream://$id", StreamRef.contentId(id).link)

    @Test fun contentIdLinkLowercasesHex() = assertEquals("acestream://$id", StreamRef.contentId(id.uppercase()).link)

    @Test fun infohashHasNoLink() = assertEquals(null, StreamRef.infohash(id).link)

    @Test fun copiedLinkReadsBackAsTheSameId() =
        assertEquals(Result.Id(id), ContentId.extract(StreamRef.contentId(id).link!!))

    @Test fun acceptsSchemeLink() = assertEquals(Result.Id(id), ContentId.extract("acestream://$id"))

    @Test fun acceptsSchemeLinkWithCidPrefix() = assertEquals(Result.Id(id), ContentId.extract("acestream://cid:$id"))

    @Test fun acceptsQueryLink() = assertEquals(Result.Id(id), ContentId.extract("acestream:?content_id=$id"))

    @Test fun acceptsBareHex() = assertEquals(Result.Id(id), ContentId.extract("  $id\n"))

    @Test fun extractsFromQueryInsideText() =
        assertEquals(Result.Id(id), ContentId.extract("Watch: https://example.org/play?x=1&content_id=$id, enjoy!"))

    @Test fun extractsBareHexInsideText() = assertEquals(Result.Id(id), ContentId.extract("id ($id)."))

    @Test fun normalisesCase() = assertEquals(Result.Id(id), ContentId.extract("acestream://${id.uppercase()}"))

    @Test fun rejectsInfohashQuery() = assertEquals(Result.Infohash, ContentId.extract("acestream:?infohash=$id"))

    @Test fun rejectsSharedGetstreamInfohash() =
        assertEquals(Result.Infohash, ContentId.extract("http://127.0.0.1:6878/ace/getstream?infohash=$id"))

    @Test fun sharedGetstreamIdStillWins() =
        assertEquals(Result.Id(id), ContentId.extract("http://127.0.0.1:6878/ace/getstream?infohash=$other&id=$id"))

    @Test fun sharedGetstreamDecodesParameterNames() {
        assertEquals(Result.Infohash, ContentId.extract("see http://127.0.0.1:6878/ace/getstream?%69nfohash=$other"))
        assertEquals(Result.Id(id), ContentId.extract("http://localhost/ace/getstream?%69d=$id&infohash=$other"))
    }

    @Test fun decodesEncodedParameterNamesInOtherLinks() {
        assertEquals(Result.Infohash, ContentId.extract("acestream:?%69nfohash=$other"))
        assertEquals(Result.Id(id), ContentId.extract("acestream:?%69d=$id&infohash=$other"))
    }

    @Test fun escapedDelimitersInValuesDoNotFormParameters() = assertEquals(
        Result.Infohash,
        ContentId.extract("http://127.0.0.1:6878/ace/getstream?infohash=$other&note=%26id%3D$id"),
    )

    @Test fun idElsewhereWinsOverGetstreamInfohash() = assertEquals(
        Result.Id(id),
        ContentId.extract("http://127.0.0.1:6878/ace/getstream?infohash=$other acestream://$id"),
    )

    @Test fun rejectsMagnet() = assertEquals(Result.Infohash, ContentId.extract("magnet:?xt=urn:btih:$id&dn=x"))

    @Test fun rejectsIhPrefix() = assertEquals(Result.Infohash, ContentId.extract("ih:$id"))

    @Test fun noneForGarbage() = assertEquals(Result.None, ContentId.extract("hello acestream://not-an-id"))

    @Test fun ignoresLongerHexRuns() = assertEquals(Result.None, ContentId.extract(id + id.take(24)))

    @Test fun validatesNormalisedIds() {
        assertEquals(true, ContentId.isValid(id))
        assertEquals(false, ContentId.isValid(id.uppercase()))
        assertEquals(false, ContentId.isValid(id.drop(1)))
    }
}
