package land.moex.nx809j.als

import android.app.Application
import android.os.UserHandle
import android.util.Log

internal const val TAG = "AlsCompensation"

/** Debug logging: adb shell setprop log.tag.AlsCompensation DEBUG */
internal fun debug() = Log.isLoggable(TAG, Log.DEBUG)

/**
 * Starts the controller in the system user's process, the one the activity manager keeps
 * running. It follows whichever user is in the foreground.
 */
class AlsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) AlsController(this).start()
    }
}
