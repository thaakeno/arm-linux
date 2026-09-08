package android.system.virtualizationservice_internal;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Minimal client-only view of AVF's internal virtualization service.
 *
 * The platform does not expose the generated Java Stub to ordinary apps, but current Android 16
 * keeps waitDisplayService() as transaction #21 in IVirtualizationServiceInternal.aidl. We only
 * need that one method to obtain the crosvm Android display Binder. Keeping this tiny proxy avoids
 * copying the internal interface's large dependency graph into the APK.
 */
public interface IVirtualizationServiceInternal extends IInterface {
    IBinder waitDisplayService() throws RemoteException;

    abstract class Stub extends Binder implements IVirtualizationServiceInternal {
        private static final String DESCRIPTOR =
                "android.system.virtualizationservice_internal.IVirtualizationServiceInternal";
        private static final int TRANSACTION_waitDisplayService =
                IBinder.FIRST_CALL_TRANSACTION + 20;

        /*
         * AOSP Terminal's DisplayProvider uses ServiceManager.waitForService(), not checkService().
         * virtualizationservice is a lazy Binder service on some builds, so checkService() can
         * legitimately return null until something asks ServiceManager to wait/start it. VmBridge
         * loads this Stub immediately before its compatibility checkService() call, therefore this
         * initializer reproduces AOSP's waitForService behavior without making the rest of the
         * Shizuku bridge depend directly on hidden SDK classes.
         */
        static {
            try {
                Class<?> serviceManager = Class.forName("android.os.ServiceManager");
                serviceManager
                        .getMethod("waitForService", String.class)
                        .invoke(null, "android.system.virtualizationservice");
            } catch (Throwable ignored) {
                // VmBridge will report the concrete Binder/display failure if the service really
                // is unavailable. Never crash class loading just because this compatibility nudge
                // is unsupported on an OEM build.
            }
        }

        public static IVirtualizationServiceInternal asInterface(IBinder binder) {
            if (binder == null) return null;
            return new Proxy(binder);
        }

        private static final class Proxy implements IVirtualizationServiceInternal {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public IBinder waitDisplayService() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    boolean handled = remote.transact(TRANSACTION_waitDisplayService, data, reply, 0);
                    if (!handled) {
                        throw new RemoteException("virtualizationservice did not handle waitDisplayService transaction");
                    }
                    reply.readException();
                    return reply.readStrongBinder();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
