package land.moex.nx809j.gesture

import android.app.Application
import android.os.UserHandle

internal const val TAG = "TouchGesture"

/**
 * Starts the controller in the system user's process, the one the activity manager keeps
 * running. It follows whichever user is in the foreground.
 */
class GestureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) DoubleTapController(this).start()
    }
}
