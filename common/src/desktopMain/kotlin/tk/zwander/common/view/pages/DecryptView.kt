package tk.zwander.common.view.pages

import com.soywiz.korio.stream.toAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import tk.zwander.common.data.DecryptFileInfo
import tk.zwander.common.data.PlatformFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import tk.zwander.common.util.flushingAsync as toAsync1

actual object PlatformDecryptView {
    actual suspend fun getInput(callback: suspend CoroutineScope.(DecryptFileInfo?) -> Unit) {
        coroutineScope {
            val dialog = FileDialog(Frame())
            dialog.mode = FileDialog.LOAD
            dialog.isVisible = true

            if (dialog.file != null) {
                val input = File(dialog.directory, dialog.file)

                callback(
                    DecryptFileInfo(
                        PlatformFile(input),
                        PlatformFile(input.parentFile, input.nameWithoutExtension)
                    )
                )
            }
        }
    }

    actual fun onStart() {}
    actual fun onFinish() {}
    actual fun onProgress(status: String, current: Long, max: Long) {}
}