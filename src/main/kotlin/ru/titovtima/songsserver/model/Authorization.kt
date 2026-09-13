package ru.titovtima.songsserver.model

import kotlinx.serialization.Serializable
import ru.titovtima.songsserver.Encoder
import ru.titovtima.songsserver.OldEncoder
import ru.titovtima.songsserver.dbConnection
import ru.titovtima.songsserver.rollbackDb
import java.sql.Types

@Serializable
data class UserLogin(val username: String, val password: String)

@Serializable
data class UserRegisterData(val username: String, val password: String, val email: String? = null) {
    fun checkRegex() = Authorization.usernameRegex.matches(username) && Authorization.passwordRegex.matches(password)
}

class Authorization {
    companion object {
        val usernameRegex = Regex("[a-zA-Zа-яА-Я0-9_.@-]{3,64}")
        val passwordRegex = Regex("[a-zA-Zа-яА-Я0-9_#?!@\$%^&*-]{6,128}")

        fun register(registerData: UserRegisterData): Int {
            if (!checkUsernameFree(registerData.username))
                return 1
            val encodedPassword = Encoder.encodeString(registerData.password).toString()
            val newId = getNewUserId() ?: return 2
            val query = dbConnection.prepareStatement(
                "insert into users(id, username, password, email) values (?, ?, ?, ?);")
            query.setInt(1, newId)
            query.setString(2, registerData.username)
            query.setString(3, encodedPassword)
            if (registerData.email == null)
                query.setNull(4, Types.VARCHAR)
            else
                query.setString(4, registerData.email)
            query.executeUpdate()
            return 0
        }

        private fun checkUsernameFree(username: String): Boolean {
            val query = dbConnection.prepareStatement("select id from users where username = ?;")
            query.setString(1, username)
            val result = query.executeQuery()
            return !result.next()
        }

        fun checkCredentials(userLogin: UserLogin): Boolean {
            val encodedPassword = Encoder.encodeString(userLogin.password).toString()
            val query = dbConnection.prepareStatement("select password from users where username = ?;")
            query.setString(1, userLogin.username)
            val result = query.executeQuery()
            if (!result.next()) return false
            if (result.getString("password") == encodedPassword) {
                val queryUpdate = dbConnection.prepareStatement(
                    "update users set last_login = now() where username = ?;")
                queryUpdate.setString(1, userLogin.username)
                queryUpdate.executeUpdate()
                return true
            } else {
                return checkPasswordInOldEncoding(userLogin)
            }
        }

        private fun checkPasswordInOldEncoding(userLogin: UserLogin): Boolean {
            val oldEncodedPassword = OldEncoder.encodeString(userLogin.password).toString()
            val query = dbConnection.prepareStatement(
                "select password from old_encoded_password where user_id = (select id from users where username = ?);")
            query.setString(1, userLogin.username)
            val result = query.executeQuery()
            if (!result.next()) return false
            if (result.getString("password") == oldEncodedPassword) {
                val queryUpdate = dbConnection.prepareStatement(
                    "update users set last_login = now() where username = ?;")
                queryUpdate.setString(1, userLogin.username)
                queryUpdate.executeUpdate()
                val encodedPassword = Encoder.encodeString(userLogin.password).toString()
                val queryWritePassword = dbConnection.prepareStatement(
                    "update users set password = ? where username = ?;")
                queryWritePassword.setString(1, encodedPassword)
                queryWritePassword.setString(2, userLogin.username)
                queryWritePassword.executeUpdate()
                return true
            } else {
                return false
            }
        }

        fun changePassword(userId: Int, newPassword: String) {
            val encodedPassword = Encoder.encodeString(newPassword).toString()
            val query = dbConnection.prepareStatement(
                "update users set password = ?, last_change_password = now() where id = ?;")
            query.setString(1, encodedPassword)
            query.setInt(2, userId)
            query.executeUpdate()
        }

        fun revokeAllTokens(userId: Int) {
            val query = dbConnection.prepareStatement("delete from auth_tokens where user_id = ?;")
            query.setInt(1, userId)
            query.executeUpdate()
        }

        private fun getNewUserId(): Int? {
            dbConnection.autoCommit = false
            try {
                val query = dbConnection.prepareStatement("select min_key from keys where name = 'users';")
                val resultSet = query.executeQuery()
                if (!resultSet.next()) throw Exception("No min key in database")
                val newId = resultSet.getInt("min_key")
                val queryUpdate = dbConnection.prepareStatement(
                    "update keys set min_key = min_key + 1 where name = 'users';")
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
}
