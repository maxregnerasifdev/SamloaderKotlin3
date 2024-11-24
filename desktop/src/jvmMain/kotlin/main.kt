@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bugsnag.Severity
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.mayakapps.compose.windowstyler.NativeLookWindow
import com.mayakapps.compose.windowstyler.WindowBackdrop
import com.mayakapps.compose.windowstyler.WindowFrameStyle
import com.sun.jna.ptr.IntByReference
import dev.icerock.moko.resources.compose.painterResource
import dev.zwander.compose.alertdialog.LocalWindowDecorations
import dev.zwander.compose.rememberThemeInfo
import korlibs.platform.Platform
import kotlinx.coroutines.launch
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import tk.zwander.common.GradleConfig
import tk.zwander.common.util.BifrostSettings
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.isWindows11
import tk.zwander.common.util.jna.windows.DwmImpl
import tk.zwander.common.util.jna.windows.DwmWindowAttribute
import tk.zwander.common.util.jna.windows.hwnd
import tk.zwander.common.util.jna.windows.toBgr
import tk.zwander.commonCompose.MainView
import tk.zwander.commonCompose.util.jna.Kernel32
import tk.zwander.commonCompose.util.toAwtColor
import tk.zwander.commonCompose.view.LocalMenuBarHeight
import tk.zwander.commonCompose.view.LocalPagerState
import tk.zwander.commonCompose.view.MacMenuBar
import tk.zwander.commonCompose.view.components.Page
import tk.zwander.commonCompose.view.keyCodeHandler
import tk.zwander.samloaderkotlin.resources.MR
import java.awt.Desktop
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.WindowEvent
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalComposeUiApi::class)
@ExperimentalTime
fun main() {
    BugsnagUtils.create()

    val exceptionHandlerFactory = WindowExceptionHandlerFactory { window ->
        WindowExceptionHandler { throwable ->
            SwingUtilities.invokeLater {
                showErrorDialog(window.takeIf { it.isDisplayable }, throwable)
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))

                BugsnagUtils.notify(throwable, Severity.ERROR)
                BugsnagUtils.destroy()
                exitProcess(0)
            }
        }
    }

    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", GradleConfig.appName)

    when {
        Platform.isLinux -> {
            val context = try {
                DirectContext.makeGL()
            } catch (e: Throwable) {
                null
            }

            try {
                context?.flush()
            } catch (e: Throwable) {
                BugsnagUtils.notify(
                    IllegalStateException(
                        "Unable to flush OpenGL context, using software rendering.",
                        e
                    )
                )
                System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
            } finally {
                try {
                    context?.close()
                } catch (_: Throwable) {
                }
            }
        }

        Platform.isWindows -> {
            if (Kernel32.isEmulatedX86()) {
                EventQueue.invokeAndWait {
                    val layer = SkiaLayer()
                    try {
                        layer.inDrawScope {
                            throw RenderException()
                        }
                    } catch (_: Throwable) {}

                    if (layer.renderApi == GraphicsApi.OPENGL) {
                        BugsnagUtils.notify(IllegalStateException("Skiko chose OpenGL on ARM, falling back to software rendering."))
                        System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
                    }

                    layer.dispose()
                }
            }
        }
    }

    application(
        exitProcessOnExit = false,
    ) {
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides exceptionHandlerFactory,
        ) {
            val mainWindowState = rememberWindowState()
            val themeInfo = rememberThemeInfo()
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val pagerState = LocalPagerState.current
            val useMicaEffect by BifrostSettings.Keys.useMicaEffect.collectAsMutableState()

            val captionColor =
                if (useMicaEffect) Color.Unspecified else themeInfo.colors.onBackground
            val titleBarColor =
                if (useMicaEffect) Color.Unspecified else themeInfo.colors.background

            val iconPainter = painterResource(MR.images.icon_rounded)

            DisposableEffect(null) {
                if (Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
                    Desktop.getDesktop().setAboutHandler {
                        scope.launch {
                            pagerState.animateScrollToPage(Page.SettingsAbout)
                        }
                    }
                }

                onDispose {
                    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
                        Desktop.getDesktop().setAboutHandler(null)
                    }
                }
            }

            NativeLookWindow(
                onCloseRequest = ::exitApplication,
                title = GradleConfig.appName,
                icon = iconPainter,
                state = mainWindowState,
                preferredBackdropType = WindowBackdrop.MicaTabbed(themeInfo.isDarkMode),
                frameStyle = WindowFrameStyle(
                    borderColor = themeInfo.colors.background,
                    captionColor = captionColor,
                    titleBarColor = titleBarColor,
                ),
                onPreviewKeyEvent = keyCodeHandler(),
            ) {
                val menuBarHeight = remember(window.height) {
                    if (Platform.isMac) window.height.dp else 0.dp
                }
                LaunchedEffect(window) {
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.background = themeInfo.colors.background.toAwtColor()

                    val map = mutableMapOf<String, String>()

                    themeInfo.colors.primary.toAwtColor().let {
                        map.put(
                            "@accentColor",
                            String.format("#%06x", (it.rgb and 0xffffff)),
                        )
                    }
                    themeInfo.colors.background.toAwtColor().let {
                        map.put(
                            "@background",
                            String.format("#%06x", (it.rgb and 0xffffff)),
                        )
                    }

                    FlatLaf.setGlobalExtraDefaults(map)
                    FlatDarkLaf.setup()
                }

                LaunchedEffect(density) {
                    window.minimumSize = with(density) {
                        Dimension(200.dp.roundToPx(), 200.dp.roundToPx())
                    }
                }

                LaunchedEffect(captionColor, titleBarColor) {
                    if (isWindows11) {
                        DwmImpl.DwmSetWindowAttribute(
                            hwnd = window.hwnd,
                            attribute = DwmWindowAttribute.DWMWA_TEXT_COLOR.value,
                            value = IntByReference(captionColor.toBgr()),
                            valueSize = 4,
                        )
                        DwmImpl.DwmSetWindowAttribute(
                            hwnd = window.hwnd,
                            attribute = DwmWindowAttribute.DWMWA_CAPTION_COLOR.value,
                            value = IntByReference(titleBarColor.toBgr()),
                            valueSize = 4,
                        )
                    }
                }

                MacMenuBar(
                    mainWindowState = mainWindowState,
                    applicationScope = this@application,
                )

                CompositionLocalProvider(
                    LocalMenuBarHeight provides menuBarHeight,
                    LocalWindowDecorations provides LocalWindowDecorations.current.copy(top = menuBarHeight),
                ) {
                    MainView(androidVersionOverride = "14") // Pass the override value to MainView
                }
            }
        }
    }
    BugsnagUtils.destroy()
    exitProcess(0)
}
