package land.moex.nx809j.als

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log

/**
 * A client of the stock vendor HAL vendor.zte.subsys.ISubsys/subsys, served by ssdaemon.
 *
 * Only two calls are needed, so they are made by hand rather than through generated stubs.
 * The codes and layouts are those of the stock stubs in system_ext/framework/subsys-service.jar
 * (stable AIDL, version 1, hash [HASH]):
 *
 * - 1, oneway setCallback(ISubsysResponse, ISubsysIndication);
 * - 86, oneway setLCDparam(int serial, int r, int g, int b, int brightness, int temp, int mode).
 *
 * The HAL answers setLCDparam with ISubsysResponse code 85, respondsetLCDparam(ResponseInfo),
 * where ResponseInfo is {int type, int serial, int error}. Stock registers its callbacks before
 * the first request, and so does this, with callbacks that accept everything, since what the
 * HAL does without them is unknown.
 */
internal class SubsysHal {
    private val lock = Any()
    private var remote: IBinder? = null
    private var serial = 0
    private val response = Callback(RESPONSE)
    private val indication = Callback(INDICATION)

    /** Connects when the service is up; returns false, without logging, while it isn't. */
    fun connect(): Boolean = synchronized(lock) {
        if (remote?.isBinderAlive == true) return true
        remote = null
        val binder = ServiceManager.checkService(SERVICE) ?: return false
        try {
            binder.linkToDeath({ forget(binder) }, 0)
            call(binder, SET_CALLBACK) {
                it.writeStrongBinder(response)
                it.writeStrongBinder(indication)
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "Cannot register the callbacks of $SERVICE", e)
            return false
        }
        remote = binder
        Log.i(TAG, "Connected to $SERVICE")
        true
    }

    fun setLcdParam(params: AlsMath.LcdParams): Boolean {
        val (binder, request) = synchronized(lock) { (remote ?: return false) to ++serial }
        try {
            call(binder, SET_LCD_PARAM) {
                it.writeInt(request)
                it.writeInt(params.red)
                it.writeInt(params.green)
                it.writeInt(params.blue)
                it.writeInt(params.brightness)
                it.writeInt(params.temperature)
                it.writeInt(params.mode)
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "setLCDparam failed", e)
            forget(binder)
            return false
        }
        if (debug()) Log.d(TAG, "setLCDparam serial=$request $params")
        return true
    }

    private fun forget(binder: IBinder) = synchronized(lock) { if (remote === binder) remote = null }

    private inline fun call(binder: IBinder, code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain(binder)
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!binder.transact(code, data, null, IBinder.FLAG_ONEWAY)) {
                throw RemoteException("$DESCRIPTOR doesn't implement code $code")
            }
        } finally {
            data.recycle()
        }
    }

    /** Accepts every call; logs the setLCDparam results when debug logging is on. */
    private class Callback(private val descriptor: String) : Binder(descriptor) {
        init {
            // The HAL is a vendor process, so the binders it receives must be VINTF stable.
            markVintfStability()
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION, DUMP_TRANSACTION, SHELL_COMMAND_TRANSACTION,
                SYSPROPS_TRANSACTION -> return super.onTransact(code, data, reply, flags)
                GET_INTERFACE_VERSION -> {
                    data.enforceInterface(descriptor)
                    reply?.writeNoException()
                    reply?.writeInt(VERSION)
                }
                GET_INTERFACE_HASH -> {
                    data.enforceInterface(descriptor)
                    reply?.writeNoException()
                    reply?.writeString(HASH)
                }
                else -> {
                    if (descriptor == RESPONSE && code == RESPOND_SET_LCD_PARAM && debug()) {
                        data.enforceInterface(descriptor)
                        logResponse(data)
                    }
                    reply?.writeNoException()
                }
            }
            return true
        }

        private fun logResponse(data: Parcel) {
            if (data.readInt() == 0) return // null ResponseInfo
            data.readInt() // parcelable size
            Log.d(TAG, "respondsetLCDparam type=${data.readInt()} serial=${data.readInt()} " +
                "error=${data.readInt()}")
        }
    }

    private companion object {
        const val SERVICE = "vendor.zte.subsys.ISubsys/subsys"
        const val DESCRIPTOR = "vendor.zte.subsys.ISubsys"
        const val RESPONSE = "vendor.zte.subsys.ISubsysResponse"
        const val INDICATION = "vendor.zte.subsys.ISubsysIndication"
        const val VERSION = 1
        const val HASH = "3bae3a21657a180d7e5b5a71f33bcdefb75e9dbb"
        const val SET_CALLBACK = IBinder.FIRST_CALL_TRANSACTION
        const val SET_LCD_PARAM = IBinder.FIRST_CALL_TRANSACTION + 85
        const val RESPOND_SET_LCD_PARAM = IBinder.FIRST_CALL_TRANSACTION + 84
        const val GET_INTERFACE_VERSION = IBinder.LAST_CALL_TRANSACTION
        const val GET_INTERFACE_HASH = IBinder.LAST_CALL_TRANSACTION - 1
    }
}
