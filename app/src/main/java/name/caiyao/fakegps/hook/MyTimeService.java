package name.caiyao.fakegps.hook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;

import name.caiyao.fakegps.R;

public class MyTimeService extends Service {

    private static final String CHANNEL_ID = "fakegps_service";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "FakeGPS Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FakeGPS")
                .setContentText("Service running")
                .setSmallIcon(R.drawable.ic_lan)
                .build();
        startForeground(1, notification);
        TimeChangeReciver reciver=new TimeChangeReciver();
        registerReceiver(reciver,new IntentFilter(Intent.ACTION_TIME_TICK));

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();

        Intent intent1=new Intent(this,StopReceiver.class);
        sendBroadcast(intent1);

    }
}
