package ru.titovtima.songsserver.model

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import ru.titovtima.songsserver.dbConnection
import ru.titovtima.songsserver.dbLock
import ru.titovtima.songsserver.rollbackDb
import java.sql.ResultSet
import java.util.*

@Serializable
data class SongsList(val id: Int, val name: String, val owner: String, val public: Boolean, val list: List<Int>) {
    constructor(listInfo: SongsListInfo, list: List<Int>) :
            this(listInfo.id, listInfo.name, listInfo.owner, listInfo.public, list)

    companion object {
        fun readFromDb(listId: Int, user: User? = null): SongsList? {
            val listInfo = SongsListInfo.readFromDb(listId, user) ?: return null
            val list = getSongsIdsFromDb(listId, user)
            return SongsList(listInfo, list)
        }

        private fun getSongsIdsFromDb(listId: Int, user: User?): List<Int> {
            if (user == null) {
                val query = dbConnection.prepareStatement(
                    "select s.id from public_songs() s left join song_in_list sl on sl.song_id = s.id where sl.list_id = ?;")
                query.setInt(1, listId)
                val resultSet = query.executeQuery()
                val resultList = arrayListOf<Int>()
                while (resultSet.next()) {
                    resultList.add(resultSet.getInt("id"))
                }
                return resultList
            } else {
                val query = dbConnection.prepareStatement(
                    "select s.id from readable_songs(?) s left join song_in_list sl on sl.song_id = s.id where sl.list_id = ?;")
                query.setInt(1, user.id)
                query.setInt(2, listId)
                val resultSet = query.executeQuery()
                val resultList = arrayListOf<Int>()
                while (resultSet.next()) {
                    resultList.add(resultSet.getInt("id"))
                }
                return resultList
            }
        }

        fun checkWriteAccess(listId: Int, user: User): Boolean {
            val query = dbConnection.prepareStatement("select id from writable_lists(?) where id = ?;")
            query.setInt(1, user.id)
            query.setInt(2, listId)
            val resultSet = query.executeQuery()
            return resultSet.next()
        }
    }

    fun checkWriteAccess(user: User) = checkWriteAccess(id, user)

    fun saveToDb(user: User, new: Boolean): Boolean {
        if (!new && !checkWriteAccess(user)) return false
        val userOwner = User.readFromDb(owner) ?: return false
        dbConnection.autoCommit = false
        try {
            if (new) {
                val query = dbConnection.prepareStatement("insert into songs_list (id, name, public, owner_id) " +
                        "values (?, ?, ?, ?);")
                query.setInt(1, id)
                query.setString(2, name)
                query.setBoolean(3, public)
                query.setInt(4, userOwner.id)
                query.executeUpdate()
            } else {
                val query = dbConnection.prepareStatement("update songs_list set name = ?, public = ?, owner_id = ? " +
                        "where id = ?;")
                query.setString(1, name)
                query.setBoolean(2, public)
                query.setInt(3, userOwner.id)
                query.setInt(4, id)
                query.executeUpdate()
            }

            if (list.isEmpty()) {
                val queryDelete = dbConnection.prepareStatement(
                    "delete from song_in_list where list_id = ?;")
                queryDelete.setInt(1, id)
                queryDelete.executeUpdate()
            } else {

                val questions = Collections.nCopies(list.size, "?").joinToString(",")
                val queryDelete = dbConnection.prepareStatement(
                    "delete from song_in_list where list_id = ? and song_id not in ($questions);")
                queryDelete.setInt(1, id)
                for (i in list.indices)
                    queryDelete.setInt(i + 2, list[i])
                queryDelete.executeUpdate()

                val questions2 = Collections.nCopies(list.size, "(?,?)").joinToString(",")
                val queryInsert = dbConnection.prepareStatement(
                    "insert into song_in_list (song_id, list_id) values $questions2 on conflict do nothing;")
                for (i in list.indices) {
                    queryInsert.setInt(i*2 + 1, list[i])
                    queryInsert.setInt(i*2 + 2, id)
                }
                queryInsert.executeUpdate()
            }
        } catch (e: Exception) {
            println(e)
            rollbackDb()
            return false
        }
        dbConnection.commit()
        dbConnection.autoCommit = true
        return true
    }
}

@Serializable
data class SongsListInfo(val id: Int, val name: String, val owner: String, val public: Boolean) {
    companion object {
        fun readFromDb(listId: Int, user: User? = null): SongsListInfo? {
            if (user == null) {
                val query = dbConnection.prepareStatement(
                    "select * from songs_list_with_username where public and id = ?;"
                )
                query.setInt(1, listId)
                return songsListInfoFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement("select * from readable_lists(?) where id = ?;")
                query.setInt(1, user.id)
                query.setInt(2, listId)
                return songsListInfoFromResultSet(query.executeQuery())
            }
        }

        fun readAllFromDb(user: User?): List<SongsListInfo> {
            if (user == null) {
                val query = dbConnection.prepareStatement(
                    "select * from songs_list_with_username where public;"
                )
                return allSongsListsInfoFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement("select * from readable_lists(?);")
                query.setInt(1, user.id)
                return allSongsListsInfoFromResultSet(query.executeQuery())
            }
        }

        private fun songsListInfoFromResultSet(resultSet: ResultSet): SongsListInfo? {
            if (!resultSet.next()) return null
            val id = resultSet.getInt("id")
            val name = resultSet.getString("name")
            val owner = resultSet.getString("owner")
            val public = resultSet.getBoolean("public")
            return SongsListInfo(id, name, owner, public)
        }

        private fun allSongsListsInfoFromResultSet(resultSet: ResultSet): List<SongsListInfo> {
            val result = mutableListOf<SongsListInfo>()
            var listInfo = songsListInfoFromResultSet(resultSet)
            while (listInfo != null) {
                result.add(listInfo)
                listInfo = songsListInfoFromResultSet(resultSet)
            }
            return result
        }
    }
}

@Serializable
data class SongsListSongsInfo(val id: Int, val name: String, val owner: String, val public: Boolean,
                              val list: List<SongInfo>) {
    constructor(listInfo: SongsListInfo, list: List<SongInfo>) :
            this(listInfo.id, listInfo.name, listInfo.owner, listInfo.public, list)

    companion object {
        fun readFromDb(listId: Int, user: User? = null): SongsListSongsInfo? {
            val listInfo = SongsListInfo.readFromDb(listId, user) ?: return null
            val list = getSongsFromDb(listId, user)
            return SongsListSongsInfo(listInfo, list)
        }

        private fun getSongsFromDb(listId: Int, user: User?): List<SongInfo> {
            if (user == null) {
                val query = dbConnection.prepareStatement(
                    "select s.id, s.name, s.extra, s.key, s.owner, s.public, s.in_main_list, s.created_at, s.updated_at " +
                            "from public_songs() s left join song_in_list sl on sl.song_id = s.id where sl.list_id = ?;")
                query.setInt(1, listId)
                return SongInfo.allSongsInfoFromResultSet(query.executeQuery())
            } else {
                val query = dbConnection.prepareStatement(
                    "select s.id, s.name, s.extra, s.key, s.owner, s.public, s.in_main_list, s.created_at, s.updated_at " +
                            "from readable_songs(?) s left join song_in_list sl on sl.song_id = s.id where sl.list_id = ?;")
                query.setInt(1, user.id)
                query.setInt(2, listId)
                return SongInfo.allSongsInfoFromResultSet(query.executeQuery())
            }
        }
    }
}

@Serializable
data class NewSongsList(val name: String, val public: Boolean, val list: List<Int>) {
    suspend fun makeList(user: User): SongsList {
        val id = getNewId("songs_list")
        return SongsList(id, name, user.username, public, list)
    }
}

@Serializable
data class SongsListRights(val listId: Int, val readers: List<String>, val writers: List<String>, val owner: String) {
    companion object {
        fun readFromDb(listId: Int, user: User?): SongsListRights? {
            if (user == null) return null
            if (!checkWriteAccess(listId, user)) return null
            val readers = arrayListOf<String>()
            val queryReaders = dbConnection.prepareStatement(
                "select username from users u right join list_reader r on u.id = r.user_id where r.list_id = ?;")
            queryReaders.setInt(1, listId)
            val resultReaders = queryReaders.executeQuery()
            while (resultReaders.next()) {
                val reader = resultReaders.getString("username")
                readers.add(reader)
            }
            val writers = arrayListOf<String>()
            val queryWriters = dbConnection.prepareStatement(
                "select username from users u right join list_writer w on u.id = w.user_id where w.list_id = ?;")
            queryWriters.setInt(1, listId)
            val resultWriters = queryWriters.executeQuery()
            while (resultWriters.next()) {
                val writer = resultWriters.getString("username")
                writers.add(writer)
            }
            val queryOwner = dbConnection.prepareStatement(
                "select username from songs_list l left join users u on l.owner_id = u.id where l.id = ?;")
            queryOwner.setInt(1, listId)
            val resultSong = queryOwner.executeQuery()
            if (!resultSong.next()) {
                return null
            }
            val owner = resultSong.getString("username")
            return SongsListRights(listId, readers, writers, owner)
        }

        fun checkWriteAccess(listId: Int, user: User): Boolean {
            val queryCheckRights = dbConnection.prepareStatement("select id from writable_lists(?) where id = ?;")
            queryCheckRights.setInt(1, user.id)
            queryCheckRights.setInt(2, listId)
            return queryCheckRights.executeQuery().next()
        }
    }

    fun checkWriteAccess(user: User): Boolean {
        if (!checkWriteAccess(listId, user)) return false
        val newOwnerId = User.readFromDb(owner)?.id ?: return false
        val queryCheckOldOwner = dbConnection.prepareStatement("select owner_id from songs_list where id = ?;")
        queryCheckOldOwner.setInt(1, listId)
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

            val queryDeleteReaders = dbConnection.prepareStatement("delete from list_reader where list_id = ?;")
            queryDeleteReaders.setInt(1, listId)
            queryDeleteReaders.executeUpdate()
            if (readersIds.isNotEmpty()) {
                val questions = Collections.nCopies(readersIds.size, "(?, ?)").joinToString(",")
                val queryAddReaders =
                    dbConnection.prepareStatement("insert into list_reader(user_id, list_id) values $questions;")
                for (i in readersIds.indices) {
                    queryAddReaders.setInt(i * 2 + 1, readersIds[i])
                    queryAddReaders.setInt(i * 2 + 2, listId)
                }
                if (queryAddReaders.executeUpdate() != readersIds.size) {
                    rollbackDb()
                    return false
                }
            }

            val queryDeleteWriters = dbConnection.prepareStatement("delete from list_writer where list_id = ?;")
            queryDeleteWriters.setInt(1, listId)
            queryDeleteWriters.executeUpdate()
            if (writersIds.isNotEmpty()) {
                val questions = Collections.nCopies(writersIds.size, "(?, ?)").joinToString(",")
                val queryAddWriters =
                    dbConnection.prepareStatement("insert into list_writer(user_id, list_id) values $questions;")
                for (i in writersIds.indices) {
                    queryAddWriters.setInt(i * 2 + 1, writersIds[i])
                    queryAddWriters.setInt(i * 2 + 2, listId)
                }
                if (queryAddWriters.executeUpdate() != writersIds.size) {
                    rollbackDb()
                    return false
                }
            }

            val queryUpdateOwner = dbConnection.prepareStatement("update songs_list set owner_id = ? where id = ?;")
            queryUpdateOwner.setInt(1, newOwnerId)
            queryUpdateOwner.setInt(2, listId)
            queryUpdateOwner.executeUpdate()

            dbConnection.commit()
            dbConnection.autoCommit = true
        }

        return true
    }
}
