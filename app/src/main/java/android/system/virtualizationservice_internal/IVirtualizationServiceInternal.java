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
