package nb.pingpong.ping;

import android.os.Parcel;
import android.os.Parcelable;

/** A discovered TV app. */
public class DeviceInfo implements Parcelable {
    public final String serviceName;
    public final String host;
    public final int port;

    public DeviceInfo(String serviceName, String host, int port) {
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
    }

    protected DeviceInfo(Parcel in) {
        serviceName = in.readString();
        host = in.readString();
        port = in.readInt();
    }

    public static final Creator<DeviceInfo> CREATOR = new Creator<DeviceInfo>() {
        @Override
        public DeviceInfo createFromParcel(Parcel in) {
            return new DeviceInfo(in);
        }

        @Override
        public DeviceInfo[] newArray(int size) {
            return new DeviceInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(serviceName);
        dest.writeString(host);
        dest.writeInt(port);
    }
}
