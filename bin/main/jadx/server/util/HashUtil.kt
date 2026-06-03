package jadx.server.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object HashUtil {

    fun md5(path: Path): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = Files.readAllBytes(path)
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun md5Short(path: Path): String = md5(path).take(7)
}
