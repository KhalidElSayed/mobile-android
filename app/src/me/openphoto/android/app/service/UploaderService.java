
package me.openphoto.android.app.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import me.openphoto.android.app.MainActivity;
import me.openphoto.android.app.Preferences;
import me.openphoto.android.app.R;
import me.openphoto.android.app.net.HttpEntityWithProgress.ProgressListener;
import me.openphoto.android.app.net.IOpenPhotoApi;
import me.openphoto.android.app.provider.PhotoUpload;
import me.openphoto.android.app.provider.UploadsProviderAccessor;
import me.openphoto.android.app.util.ImageUtils;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.RemoteViews;

public class UploaderService extends Service {
    private static final int NOTIFICATION_UPLOAD_PROGRESS = 1;
    private static final String TAG = UploaderService.class.getSimpleName();
    private IOpenPhotoApi mApi;

    private static ArrayList<NewPhotoObserver> sNewPhotoObservers;
    private static ConnectivityChangeReceiver sReceiver;

    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;

    private NotificationManager mNotificationManager;

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            handleIntent((Intent) msg.obj);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);

        mApi = Preferences.getApi(this);
        startFileObserver();
        setUpConnectivityWatcher();
        Log.d(TAG, "Service created");
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(sReceiver);
        for (NewPhotoObserver observer : sNewPhotoObservers) {
            observer.stopWatching();
        }
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        mServiceHandler.sendMessage(msg);
    }

    private void handleIntent(Intent intent) {
        if (!isOnline(getBaseContext())) {
            return;
        }
        UploadsProviderAccessor uploads = new UploadsProviderAccessor(this);
        List<PhotoUpload> pendingUploads = uploads.getPendingUploads();
        for (PhotoUpload photoUpload : pendingUploads) {
            Log.i(TAG, "Starting upload to OpenPhoto: " + photoUpload.getPhotoUri());
            File file = new File(ImageUtils.getRealPathFromURI(this, photoUpload.getPhotoUri()));
            stopErrorNotification(file);
            final Notification notification = showUploadNotification(file);
            try {
                mApi.uploadPhoto(file, photoUpload.getMetaData(), new ProgressListener() {
                    private int mLastProgress = -1;

                    @Override
                    public void transferred(long transferedBytes, long totalBytes) {
                        int newProgress = (int) (transferedBytes * 100 / totalBytes);
                        if (mLastProgress < newProgress) {
                            mLastProgress = newProgress;
                            updateUploadNotification(notification, mLastProgress, 100);
                        }
                    }
                });
                Log.i(TAG, "Upload to OpenPhoto completed for: " + photoUpload.getPhotoUri());
                uploads.setUploaded(photoUpload.getId());
            } catch (Exception e) {
                uploads.setError(photoUpload.getId(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                Log.e(TAG, "Could not upload the photo taken", e);
                showErrorNotification(photoUpload, file);
            }

            stopUploadNotification();
        }
    }

    private Notification showUploadNotification(File file) {
        int icon = R.drawable.icon;
        CharSequence tickerText = getString(R.string.notification_uploading_photo, file.getName());
        long when = System.currentTimeMillis();
        CharSequence contentMessageTitle = getString(R.string.notification_uploading_photo,
                file.getName());

        // TODO adjust this to show the upload manager
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification = new Notification(icon, tickerText, when);

        RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.notification_upload);
        contentView.setImageViewResource(R.id.image, icon);
        contentView.setTextViewText(R.id.title, contentMessageTitle);
        contentView.setProgressBar(R.id.progress, 100, 0, true);
        notification.contentView = contentView;
        notification.contentIntent = contentIntent;

        mNotificationManager.notify(NOTIFICATION_UPLOAD_PROGRESS, notification);

        return notification;
    }

    protected void updateUploadNotification(final Notification notification, int progress, int max) {
        if (progress < max) {
            notification.contentView.setProgressBar(R.id.progress, max, progress, false);
        } else {
            notification.contentView.setProgressBar(R.id.progress, 0, 0, true);
        }
        mNotificationManager.notify(NOTIFICATION_UPLOAD_PROGRESS, notification);
    }

    private void stopUploadNotification() {
        mNotificationManager.cancel(NOTIFICATION_UPLOAD_PROGRESS);
    }

    private void showErrorNotification(PhotoUpload photoUpload, File file) {
        int icon = R.drawable.icon;
        CharSequence titleText = getString(R.string.notification_upload_failed_title);
        long when = System.currentTimeMillis();
        CharSequence contentMessageTitle = getString(R.string.notification_upload_failed_text,
                file.getName());

        // TODO show upload activity and send URI of failed upload. in upload
        // activity prefill fields
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new Notification(icon, titleText, when);
        notification.setLatestEventInfo(this, titleText, contentMessageTitle, contentIntent);

        mNotificationManager.notify(file.hashCode(), notification);
    }

    private void stopErrorNotification(File file) {
        mNotificationManager.cancel(file.hashCode());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setUpConnectivityWatcher() {
        sReceiver = new ConnectivityChangeReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(sReceiver, filter);
    }

    private void startFileObserver() {
        sNewPhotoObservers = new ArrayList<NewPhotoObserver>();
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        if (dcim.isDirectory()) {
            for (String dir : dcim.list()) {
                if (!dir.startsWith(".")) {
                    dir = dcim.getAbsolutePath() + "/" + dir;
                    NewPhotoObserver observer = new NewPhotoObserver(this, dir);
                    sNewPhotoObservers.add(observer);
                    observer.startWatching();
                    Log.d(TAG, "Started watching " + dir);
                }
            }
        }
    }

    private static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.getActiveNetworkInfo().isConnectedOrConnecting();
        } catch (Exception e) {
            return false;
        }
    }

    private class ConnectivityChangeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            boolean online = isOnline(context);
            Log.d(TAG, "Connectivity changed to " + (online ? "online" : "offline"));
            if (online) {
                context.startService(new Intent(context, UploaderService.class));
            }
        }
    }
}
