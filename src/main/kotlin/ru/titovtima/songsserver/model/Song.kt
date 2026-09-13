package ru.titovtima.songsserver.model

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import ru.titovtima.songsserver.dbConnection
import ru.titovtima.songsserver.dbLock
import ru.titovtima.songsserver.rollbackDb
import java.sql.ResultSet
import java.sql.Types
import java.util.Collections
import java.util.UUID

@Serializable
data class Song (val id: Int, val name: String, val extra: String?, val key: Int?,
                 val owner: String, val public: Boolean, val inMainList: Boolean,
                 val parts: List<SongPart>, val performances: List<SongPerformance>) {
    companion object {
        fun readFromDb(id: Int, user: User?): Song? {
            if (user == null) {
                val query = dbConnection.prepareStatement("select * from public_songs() where id = ?;")
                query.setInt(1, id)
                return songFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement("select * from readable_songs(?) where id = ?;")
                query.setInt(1, user.id)
                query.setInt(2, id)
                return songFromResultSet(query.executeQuery())
            }
        }

        fun readAllFromDb(user: User?): List<Song> {
            if (user == null) {
                val query = dbConnection.prepareStatement("select * from public_songs() order by id;")
                return allSongsFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement("select * from readable_songs(?) order by id;")
                query.setInt(1, user.id)
                return allSongsFromResultSet(query.executeQuery())
            }
        }

        fun readMainListFromDb(user: User?): List<Song> {
            if (user == null) {
                val query = dbConnection.prepareStatement("select * from public_songs() where in_main_list = true;")
                return allSongsFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement("select * from readable_songs(?) where in_main_list = true;")
                query.setInt(1, user.id)
                return allSongsFromResultSet(query.executeQuery())
            }
        }

        fun songFromResultSet(resultSet: ResultSet): Song? {
            if (resultSet.next()) {
                val id = resultSet.getInt("id")
                val name = resultSet.getString("name")
                val extra = resultSet.getString("extra")
                var key: Int? = resultSet.getInt("key")
                if (resultSet.wasNull()) key = null
                val owner = resultSet.getString("owner")
                val public = resultSet.getBoolean("public")
                val inMainList = resultSet.getBoolean("in_main_list")
                val songParts = SongPart.getAllSongParts(id)
                val songPerformances = SongPerformance.getAllSongPerformances(id)
                return Song(id, name, extra, key, owner, public, inMainList, songParts, songPerformances)
            } else {
                return null
            }
        }

        fun allSongsFromResultSet(resultSet: ResultSet): List<Song> {
            val result = mutableListOf<Song>()
            var song = songFromResultSet(resultSet)
            while (song != null) {
                result.add(song)
                song = songFromResultSet(resultSet)
            }
            return result
        }
    }

    private fun checkWriteAccess(user: User): Boolean = SongRights.checkWriteAccess(id, user)

    suspend fun saveToDb(user: User, new: Boolean): Boolean {
        if (!new && !checkWriteAccess(user)) return false
        val userOwner = User.readFromDb(owner) ?: return false
        dbLock.withLock {
            dbConnection.autoCommit = false
            try {
                if (new) {
                    val queryInsert = dbConnection.prepareStatement(
                        "insert into song (id, name, extra, key, owner_id, public, in_main_list, created_at, updated_at) " +
                                "values (?, ?, ?, ?, ?, ?, ?, now(), now());")
                    queryInsert.setInt(1, id)
                    queryInsert.setString(2, name)
                    if (extra == null) queryInsert.setNull(3, Types.VARCHAR)
                    else queryInsert.setString(3, extra)
                    if (key == null) queryInsert.setNull(4, Types.INTEGER)
                    else queryInsert.setInt(4, key)
                    queryInsert.setInt(5, userOwner.id)
                    queryInsert.setBoolean(6, public)
                    queryInsert.setBoolean(7, inMainList)
                    queryInsert.executeUpdate()
                } else {
                    val queryUpdate = dbConnection.prepareStatement(
                        "update song set name = ?, extra = ?, key = ?, public = ?, in_main_list = ?, owner_id = ?, " +
                                "updated_at = now() where id = ?;")
                    queryUpdate.setString(1, name)
                    if (extra == null) queryUpdate.setNull(2, Types.VARCHAR)
                    else queryUpdate.setString(2, extra)
                    if (key == null) queryUpdate.setNull(3, Types.INTEGER)
                    else queryUpdate.setInt(3, key)
                    queryUpdate.setBoolean(4, public)
                    queryUpdate.setBoolean(5, inMainList)
                    queryUpdate.setInt(6, userOwner.id)
                    queryUpdate.setInt(7, id)
                    queryUpdate.executeUpdate()
                }

                val allAudios = performances.mapNotNull { it.audio }.toSet().toList()
                if (allAudios.isNotEmpty()) {
                    val questions = Collections.nCopies(allAudios.size, "?").joinToString(",")
                    val query = dbConnection.prepareStatement(
                        "update song_audio set song_id = ? where uuid in ($questions);")
                    query.setInt(1, id)
                    for (i in allAudios.indices) query.setString(i + 2, allAudios[i])
                    if (query.executeUpdate() != allAudios.size) {
                        rollbackDb()
                        return false
                    }
                }

                val queryDeleteSongParts = dbConnection.prepareStatement("delete from song_part where song_id = ?;")
                queryDeleteSongParts.setInt(1, id)
                queryDeleteSongParts.executeUpdate()
                for (part in parts) part.saveToDb(id)

                val queryDeleteSongPerformances =
                    dbConnection.prepareStatement("delete from song_performance where song_id = ?;")
                queryDeleteSongPerformances.setInt(1, id)
                queryDeleteSongPerformances.executeUpdate()
                for (performance in performances) performance.saveToDb(id)
            } catch (e: Exception) {
                println(e)
                rollbackDb()
                return false
            }
            dbConnection.commit()
            dbConnection.autoCommit = true
        }
        return true
    }
}

@Serializable
data class NewSongData (val name: String, val extra: String? = null, val key: Int? = null,
                        val public: Boolean = false, val inMainList: Boolean = false, val parts: List<SongPart>,
                        val performances: List<SongPerformance> = listOf()) {

    fun makeSong(user: User): Song? {
        val id = getId() ?: return null
        return Song(id, name, extra, key, user.username, public, inMainList, parts, performances)
    }

    private fun getId(): Int? {
        dbConnection.autoCommit = false
        try {
            val query = dbConnection.prepareStatement("select min_key from keys where name = 'song';")
            val resultSet = query.executeQuery()
            if (!resultSet.next()) throw Exception("No min key in database")
            val newId = resultSet.getInt("min_key")
            val queryUpdate = dbConnection.prepareStatement(
                "update keys set min_key = min_key + 1 where name = 'song';")
            queryUpdate.executeUpdate()
            dbConnection.commit()
            dbConnection.autoCommit = true
            return newId
        } catch (e: Exception) {
            println(e)
            rollbackDb()
            return null
        }
    }
}

enum class SongPartType(val int: Int) { Text(1), Chords(2), ChordsText(3) }

fun songPartTypeFromInt(int: Int) = when (int) {
    1 -> SongPartType.Text
    2 -> SongPartType.Chords
    3 -> SongPartType.ChordsText
    else -> SongPartType.Text
}

@Serializable
data class SongPart(val type: SongPartType, val ord: Int, val name: String? = null, val data: String, val key: Int? = null, val lang: String? = null) {
    companion object {
        fun getAllSongParts(songId: Int): List<SongPart> {
            val query = dbConnection.prepareStatement("select * from song_part where song_id = ? order by ord;")
            query.setInt(1, songId)
            val resultSet = query.executeQuery()
            val result = arrayListOf<SongPart>()
            while (resultSet.next()) {
                val type = resultSet.getInt("type")
                val ord = resultSet.getInt("ord")
                val name = resultSet.getString("name")
                val data = resultSet.getString("data")
                var key: Int? = resultSet.getInt("key")
                if (resultSet.wasNull()) key = null
                var lang: String? = resultSet.getString("lang")
                if (resultSet.wasNull()) lang = null
                result.add(SongPart(songPartTypeFromInt(type), ord, name, data, key, lang))
            }
            return result
        }
    }

    fun saveToDb(songId: Int) {
        val query = dbConnection.prepareStatement(
            "insert into song_part (song_id, type, ord, name, data, key, lang) values (?, ?, ?, ?, ?, ?, ?);")
        query.setInt(1, songId)
        query.setInt(2, type.int)
        query.setInt(3, ord)
        if (name == null) query.setNull(4, Types.VARCHAR)
        else query.setString(4, name)
        query.setString(5, data)
        if (key == null) query.setNull(6, Types.INTEGER)
        else query.setInt(6, key)
        if (lang == null) query.setNull(7, Types.VARCHAR)
        else query.setString(7, lang)
        query.executeUpdate()
    }
}

@Serializable
data class Artist(var id: Int, val name: String) {
    companion object {
        fun readFromDb(id: Int): Artist? {
            val query = dbConnection.prepareStatement("select name from artist where id = ?;")
            query.setInt(1, id)
            val resultSet = query.executeQuery()
            if (resultSet.next()) {
                val name = resultSet.getString("name")
                return Artist(id, name)
            } else
                return null
        }

        fun readAllFromDb(): List<Artist> {
            val query = dbConnection.prepareStatement("select id, name from artist;")
            val result = mutableListOf<Artist>()
            val resultSet = query.executeQuery()
            while (resultSet.next()) {
                val id = resultSet.getInt("id")
                val name = resultSet.getString("name")
                result.add(Artist(id, name))
            }
            return result
        }

        suspend fun saveNew(name: String): Int {
            if (dbConnection.autoCommit) {
                dbLock.withLock {
                    dbConnection.autoCommit = false
                    val result = saveNewInner(name)
                    dbConnection.commit()
                    dbConnection.autoCommit = true
                    return result
                }
            } else {
                return saveNewInner(name)
            }
        }

        private suspend fun saveNewInner(name: String): Int {
            val id = getNewId("artist")
            val queryCreate = dbConnection.prepareStatement("insert into artist(id, name) values (?, ?);")
            queryCreate.setInt(1, id)
            queryCreate.setString(2, name)
            if (queryCreate.executeUpdate() != 1) throw SavingToDbException("Insert into artist failed")
            return id
        }
    }

    fun saveToDb() {
        val query = dbConnection.prepareStatement("update artist set name = ? where id = ?;")
        query.setString(1, name)
        query.setInt(2, id)
        query.executeUpdate()
    }
}

@Serializable
data class SongPerformance(var id: Int, val artists: List<Artist>, val songName: String?, val link: String?,
                           val extra: String?, val album: String?, val lang: String?, val date: String?, val key: Int?,
                           val ord: Int, val bpm: Int?,
                           val isOriginal: Boolean = false, val isMain: Boolean = false, val audio: String? = null) {
    companion object {
        fun getAllSongPerformances(songId: Int): List<SongPerformance> {
            val query = dbConnection.prepareStatement("select * from song_performance where song_id = ? order by ord;")
            query.setInt(1, songId)
            val resultSet = query.executeQuery()
            val result = arrayListOf<SongPerformance>()
            while (resultSet.next()) {
                val id = resultSet.getInt("id")
                val songName = resultSet.getString("song_name")
                val link = resultSet.getString("link")
                val extra = resultSet.getString("extra")
                val album = resultSet.getString("album")
                val lang = resultSet.getString("lang")
                val date = resultSet.getString("date")
                var key: Int? = resultSet.getInt("key")
                if (resultSet.wasNull()) key = null
                val ord = resultSet.getInt("ord")
                var bpm: Int? = resultSet.getInt("bpm")
                if (resultSet.wasNull()) bpm = null
                val isOriginal = resultSet.getBoolean("is_original")
                val isMain = resultSet.getBoolean("is_main")
                val audio = resultSet.getString("audio_uuid")
                val queryArtists = dbConnection.prepareStatement(
                    "select a.id, a.name from performance_artist pa " +
                    "left join artist a on pa.artist_id = a.id " +
                    "where pa.performance_id = ?;"
                )
                queryArtists.setInt(1, id)
                val resultSetArtists = queryArtists.executeQuery()
                val artists = arrayListOf<Artist>()
                while (resultSetArtists.next()) {
                    val artistId = resultSetArtists.getInt("id")
                    val name = resultSetArtists.getString("name")
                    artists.add(Artist(artistId, name))
                }
                result.add(SongPerformance(
                    id, artists, songName, link, extra, album, lang, date, key, ord, bpm, isOriginal, isMain, audio))
            }
            return result
        }
    }

    suspend fun saveToDb(songId: Int) {
        artists.forEach { artist ->
            if (artist.id < 0) {
                artist.id = Artist.saveNew(artist.name)
            }
        }
        if (id < 0) {
            id = getNewId("performance")
        }
        val query = dbConnection.prepareStatement(
            "insert into song_performance " +
                "(id, song_id, song_name, link, extra, album, lang, date, key, ord, bpm, is_original, is_main, audio_uuid) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);")
        query.setInt(1, id)
        query.setInt(2, songId)
        if (songName == null) query.setNull(3, Types.VARCHAR)
        else query.setString(3, songName)
        if (link == null) query.setNull(4, Types.VARCHAR)
        else query.setString(4, link)
        if (extra == null) query.setNull(5, Types.VARCHAR)
        else query.setString(5, extra)
        if (album == null) query.setNull(6, Types.VARCHAR)
        else query.setString(6, album)
        if (lang == null) query.setNull(7, Types.VARCHAR)
        else query.setString(7, lang)
        if (date == null) query.setNull(8, Types.VARCHAR)
        else query.setString(8, date)
        if (key == null) query.setNull(9, Types.INTEGER)
        else query.setInt(9, key)
        query.setInt(10, ord)
        if (bpm == null) query.setNull(11, Types.INTEGER)
        else query.setInt(11, bpm)
        query.setBoolean(12, isOriginal)
        query.setBoolean(13, isMain)
        if (audio == null) query.setNull(14, Types.VARCHAR)
        else query.setString(14, audio)
        query.executeUpdate()
        if (artists.isNotEmpty()) {
            val artistsString = "insert into performance_artist(performance_id, artist_id) values " +
                Collections.nCopies(artists.size, "(?,?)").joinToString(",") + ";"
            val queryArtists = dbConnection.prepareStatement(artistsString)
            for (i in artists.indices) {
                queryArtists.setInt(i * 2 + 1, id)
                queryArtists.setInt(i * 2 + 2, artists[i].id)
            }
            queryArtists.executeUpdate()
        }
    }
}

@Serializable
data class SongRights(val songId: Int, val readers: List<String>, val writers: List<String>, val owner: String) {
    companion object {
        fun readFromDb(songId: Int, user: User?): SongRights? {
            if (user == null) return null
            if (!checkWriteAccess(songId, user)) return null
            val readers = arrayListOf<String>()
            val queryReaders = dbConnection.prepareStatement(
                "select username from users u right join song_reader r on u.id = r.user_id where r.song_id = ?;")
            queryReaders.setInt(1, songId)
            val resultReaders = queryReaders.executeQuery()
            while (resultReaders.next()) {
                val reader = resultReaders.getString("username")
                readers.add(reader)
            }
            val writers = arrayListOf<String>()
            val queryWriters = dbConnection.prepareStatement(
                "select username from users u right join song_writer w on u.id = w.user_id where w.song_id = ?;")
            queryWriters.setInt(1, songId)
            val resultWriters = queryWriters.executeQuery()
            while (resultWriters.next()) {
                val writer = resultWriters.getString("username")
                writers.add(writer)
            }
            val querySong = dbConnection.prepareStatement(
                "select username from song s left join users u on s.owner_id = u.id where s.id = ?;")
            querySong.setInt(1, songId)
            val resultSong = querySong.executeQuery()
            if (!resultSong.next()) {
                return null
            }
            val owner = resultSong.getString("username")
            return SongRights(songId, readers, writers, owner)
        }

        fun checkWriteAccess(songId: Int, user: User): Boolean {
            val queryCheckRights = dbConnection.prepareStatement("select id from writable_songs(?) where id = ?;")
            queryCheckRights.setInt(1, user.id)
            queryCheckRights.setInt(2, songId)
            val resultSet = queryCheckRights.executeQuery()
            return resultSet.next()
        }
    }

    fun checkWriteAccess(user: User): Boolean {
        if (!checkWriteAccess(songId, user)) return false
        val newOwnerId = User.readFromDb(owner)?.id ?: return false
        val queryCheckOldOwner = dbConnection.prepareStatement("select owner_id from song where id = ?;")
        queryCheckOldOwner.setInt(1, songId)
        val resultSet = queryCheckOldOwner.executeQuery()
        if (!resultSet.next()) return false
        val oldOwnerId = resultSet.getInt("owner_id")
        return newOwnerId == oldOwnerId || user.id == oldOwnerId || user.isAdmin
    }

    suspend fun writeToDb(user: User): Boolean {
        if (!checkWriteAccess(user)) return false
        val newOwnerId = User.readFromDb(owner)?.id ?: return false
        val readersIds = readers.map { User.readFromDb(it)?.id ?: return false }
        val writersIds = writers.map { User.readFromDb(it)?.id ?: return false }

        dbLock.withLock {
            dbConnection.autoCommit = false

            val queryDeleteReaders = dbConnection.prepareStatement("delete from song_reader where song_id = ?;")
            queryDeleteReaders.setInt(1, songId)
            queryDeleteReaders.executeUpdate()
            if (readersIds.isNotEmpty()) {
                val questions = Collections.nCopies(readersIds.size, "(?, ?)").joinToString(",")
                val queryAddReaders =
                    dbConnection.prepareStatement("insert into song_reader(user_id, song_id) values $questions;")
                for (i in readersIds.indices) {
                    queryAddReaders.setInt(i * 2 + 1, readersIds[i])
                    queryAddReaders.setInt(i * 2 + 2, songId)
                }
                if (queryAddReaders.executeUpdate() != readersIds.size) {
                    rollbackDb()
                    return false
                }
            }

            val queryDeleteWriters = dbConnection.prepareStatement("delete from song_writer where song_id = ?;")
            queryDeleteWriters.setInt(1, songId)
            queryDeleteWriters.executeUpdate()
            if (writersIds.isNotEmpty()) {
                val questions = Collections.nCopies(writersIds.size, "(?, ?)").joinToString(",")
                val queryAddWriters =
                    dbConnection.prepareStatement("insert into song_writer(user_id, song_id) values $questions;")
                for (i in writersIds.indices) {
                    queryAddWriters.setInt(i * 2 + 1, writersIds[i])
                    queryAddWriters.setInt(i * 2 + 2, songId)
                }
                if (queryAddWriters.executeUpdate() != writersIds.size) {
                    rollbackDb()
                    return false
                }
            }

            val queryUpdateOwner = dbConnection.prepareStatement("update song set owner_id = ? where id = ?;")
            queryUpdateOwner.setInt(1, newOwnerId)
            queryUpdateOwner.setInt(2, songId)
            queryUpdateOwner.executeUpdate()

            dbConnection.commit()
            dbConnection.autoCommit = true
        }

        return true
    }
}

class SongAudio {
    companion object {
        private val S3_BUCKET = System.getenv("S3_BUCKET") ?: throw error("Env S3_BUCKET not defined")
        private val endpointUrl = Url.parse(System.getenv("S3_ENDPOINT_URL") ?: throw error("Env S3_ENDPOINT_URL not defined"))
        private val region = System.getenv("S3_REGION") ?: throw error("Env S3_REGION not defined")

        suspend fun loadAudioFromS3(uuid: String): ByteArray? {
            val getRequest = GetObjectRequest {
                bucket = S3_BUCKET
                key = uuid
            }

            var resultStream: ByteArray? = null

            try {
                S3Client.fromEnvironment {
                    endpointUrl = SongAudio.endpointUrl
                    region = SongAudio.region
                }.use { s3 ->
                    s3.getObject(getRequest) { response ->
                        resultStream = response.body?.toByteArray()
                    }
                }
            } catch (_: Exception) {
            }

            return resultStream
        }

        suspend fun uploadAudioToS3(byteArray: ByteArray, songId: String = ""): String {
            val uuid = getFreeUuid(songId.toIntOrNull())

            val putRequest = PutObjectRequest {
                bucket = S3_BUCKET
                key = uuid
                metadata = mapOf("songId" to songId)
                body = ByteStream.fromBytes(byteArray)
            }

            S3Client.fromEnvironment {
                endpointUrl = SongAudio.endpointUrl
                region = SongAudio.region
            }.use { s3 ->
                println(s3.putObject(putRequest).eTag)
            }

            return uuid
        }

        private suspend fun getFreeUuid(songId: Int? = null): String {
            var free = false
            var uuid: String = UUID.randomUUID().toString()
            dbLock.withLock {
                dbConnection.autoCommit = false
                while (!free) {
                    uuid = UUID.randomUUID().toString()
                    val query = dbConnection.prepareStatement("select * from song_audio where uuid = ?;")
                    query.setString(1, uuid)
                    free = !query.executeQuery().next()
                }
                if (songId != null) {
                    val query = dbConnection.prepareStatement("insert into song_audio (uuid, song_id) values (?, ?);")
                    query.setString(1, uuid)
                    query.setInt(2, songId)
                    query.executeUpdate()
                } else {
                    val query = dbConnection.prepareStatement("insert into song_audio (uuid) values (?);")
                    query.setString(1, uuid)
                    query.executeUpdate()
                }
                dbConnection.commit()
                dbConnection.autoCommit = true
            }
            return uuid
        }
    }
}

@Serializable
data class SongInfo(val id: Int, val name: String, val public: Boolean, val inMainList: Boolean) {
    companion object {
        fun readFromDb(id: Int, user: User?): SongInfo? {
            if (user == null) {
                val query = dbConnection.prepareStatement(
                    "select id, name, public, in_main_list from public_songs() where id = ?;")
                query.setInt(1, id)
                return songInfoFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement(
                    "select id, name, public, in_main_list from readable_songs(?) where id = ?;")
                query.setInt(1, user.id)
                query.setInt(2, id)
                return songInfoFromResultSet(query.executeQuery())
            }
        }

        fun readAllFromDb(user: User?): List<SongInfo> {
            if (user == null) {
                val query = dbConnection.prepareStatement("select * from public_songs();")
                return allSongsInfoFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement("select * from readable_songs(?);")
                query.setInt(1, user.id)
                return allSongsInfoFromResultSet(query.executeQuery())
            }
        }

        fun readMainListFromDb(user: User?): List<SongInfo> {
            if (user == null) {
                val query = dbConnection.prepareStatement("select * from public_songs() where in_main_list = true;")
                return allSongsInfoFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement("select * from readable_songs(?) where in_main_list = true;")
                query.setInt(1, user.id)
                return allSongsInfoFromResultSet(query.executeQuery())
            }
        }

        private fun songInfoFromResultSet(resultSet: ResultSet): SongInfo? {
            if (!resultSet.next()) return null
            val id = resultSet.getInt("id")
            val name = resultSet.getString("name")
            val public = resultSet.getBoolean("public")
            val inMainList = resultSet.getBoolean("in_main_list")
            return SongInfo(id, name, public, inMainList)
        }

        fun allSongsInfoFromResultSet(resultSet: ResultSet): List<SongInfo> {
            val result = mutableListOf<SongInfo>()
            var songInfo = songInfoFromResultSet(resultSet)
            while (songInfo != null) {
                result.add(songInfo)
                songInfo = songInfoFromResultSet(resultSet)
            }
            return result
        }
    }
}

suspend fun getNewId(name: String): Int {
    return MutexByString.withLock("minKey:$name") {
        val queryGetId = dbConnection.prepareStatement("select min_key from keys where name=?;")
        queryGetId.setString(1, name)
        val resultSet = queryGetId.executeQuery()
        if (!resultSet.next()) throw SavingToDbException("Min key for $name not found")
        val id = resultSet.getInt("min_key")
        val queryUpdateMinKey = dbConnection.prepareStatement("update keys set min_key = ? where name=?;")
        queryUpdateMinKey.setInt(1, id + 1)
        queryUpdateMinKey.setString(2, name)
        if (queryUpdateMinKey.executeUpdate() != 1) throw SavingToDbException("Update min key for $name failed")
        return@withLock id
    }
}
